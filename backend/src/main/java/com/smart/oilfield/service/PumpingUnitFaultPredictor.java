package com.smart.oilfield.service;

import com.smart.oilfield.config.AdvancedFeaturesProperties;
import com.smart.oilfield.dto.FaultPredictionRequest;
import com.smart.oilfield.entity.FaultPrediction;
import com.smart.oilfield.entity.PumpingUnitData;
import com.smart.oilfield.entity.Well;
import com.smart.oilfield.event.AlarmTriggeredEvent;
import com.smart.oilfield.event.FaultPredictedEvent;
import com.smart.oilfield.event.WellDataReceivedEvent;
import com.smart.oilfield.repository.FaultPredictionRepository;
import com.smart.oilfield.repository.PumpingUnitDataRepository;
import com.smart.oilfield.repository.WellRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PumpingUnitFaultPredictor {

    private final FaultPredictionRepository faultPredictionRepository;
    private final PumpingUnitDataRepository pumpingUnitDataRepository;
    private final WellRepository wellRepository;
    private final AdvancedFeaturesProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    private static final List<String> DEFAULT_FAULT_TYPES = Arrays.asList(
            "ROD_BREAK",
            "PUMP_LEAK",
            "GAS_LOCK",
            "VALVE_LEAK"
    );

    @Async
    @EventListener
    @Transactional
    public void onDataReceived(WellDataReceivedEvent event) {
        log.info("Checking fault prediction for well: {}", event.getWellId());
        Well well = wellRepository.findByWellId(event.getWellId()).orElse(null);
        if (well == null || !"production".equals(well.getWellType())) {
            return;
        }
        FaultPredictionRequest request = new FaultPredictionRequest();
        request.setWellId(event.getWellId());
        request.setAnalysisEndTime(LocalDateTime.now());
        request.setGenerateMaintenanceRecommendation(true);
        request.setAutoPublishAlarm(properties.getFault().isEnableAutoAlarm());
        predictWellFaults(request);
    }

    @Scheduled(cron = "${advanced.fault.auto-prediction-schedule:0 0 */6 * * ?}")
    @Transactional
    public void autoPredictAllWells() {
        log.info("Starting scheduled fault prediction for all production wells");
        List<Well> productionWells = wellRepository.findByWellType("production");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoffTime = now.minusHours(24);
        List<String> activeWells = pumpingUnitDataRepository.findWellsWithRecentData(cutoffTime);

        for (Well well : productionWells) {
            if (!activeWells.contains(well.getWellId())) {
                continue;
            }
            try {
                FaultPredictionRequest request = new FaultPredictionRequest();
                request.setWellId(well.getWellId());
                request.setAnalysisEndTime(now);
                request.setGenerateMaintenanceRecommendation(true);
                request.setAutoPublishAlarm(properties.getFault().isEnableAutoAlarm());
                predictWellFaults(request);
            } catch (Exception e) {
                log.error("Failed to predict faults for well: {}", well.getWellId(), e);
            }
        }
    }

    @Transactional
    public List<FaultPrediction> predictWellFaults(FaultPredictionRequest request) {
        log.info("Starting fault prediction for well: {}", request.getWellId());

        AdvancedFeaturesProperties.Fault config = properties.getFault();
        LocalDateTime endTime = request.getAnalysisEndTime() != null ? request.getAnalysisEndTime() : LocalDateTime.now();
        int windowHours = request.getAnalysisWindowHours() != null ?
                Math.max(request.getAnalysisWindowHours(), config.getMinAnalysisWindowHours()) :
                config.getDefaultAnalysisWindowHours();
        LocalDateTime startTime = endTime.minusHours(windowHours);
        double probabilityThreshold = request.getFaultProbabilityThreshold() != null ?
                request.getFaultProbabilityThreshold() : config.getFaultProbabilityThreshold();
        List<String> faultTypes = request.getFaultTypesToCheck() != null && !request.getFaultTypesToCheck().isEmpty() ?
                request.getFaultTypesToCheck() : DEFAULT_FAULT_TYPES;

        List<PumpingUnitData> data = pumpingUnitDataRepository
                .findByWellIdAndTimeRange(request.getWellId(), startTime, endTime);

        if (data.size() < config.getMinAnalysisWindowHours() / 2) {
            log.warn("Insufficient data for fault prediction on well: {}. Data points: {}",
                    request.getWellId(), data.size());
            return Collections.emptyList();
        }

        Map<String, Object> analysisData = analyzeTimeSeriesData(data, config);

        List<FaultPrediction> predictions = new ArrayList<>();

        for (String faultType : faultTypes) {
            try {
                Optional<FaultPrediction> existing = faultPredictionRepository
                        .findLatestUnacknowledgedByWellAndType(request.getWellId(), faultType);

                if (existing.isPresent()) {
                    FaultPrediction pred = existing.get();
                    if (pred.getPredictionTime().isAfter(endTime.minusHours(6))) {
                        predictions.add(pred);
                        continue;
                    }
                }

                FaultPrediction prediction = analyzeFaultType(
                        request.getWellId(), faultType, analysisData,
                        endTime, windowHours, probabilityThreshold, config);

                if (prediction != null) {
                    if (request.getGenerateMaintenanceRecommendation()) {
                        addMaintenanceRecommendation(prediction, config);
                    }
                    prediction.setModelVersion(request.getModelVersion() != null ?
                            request.getModelVersion() : config.getModelVersion());
                    predictions.add(prediction);
                }
            } catch (Exception e) {
                log.error("Failed to analyze fault type {} for well: {}", faultType, request.getWellId(), e);
            }
        }

        List<FaultPrediction> significantPredictions = predictions.stream()
                .filter(p -> p.getFaultProbability() >= probabilityThreshold)
                .collect(Collectors.toList());

        if (!significantPredictions.isEmpty()) {
            List<FaultPrediction> saved = faultPredictionRepository.saveAll(significantPredictions);

            eventPublisher.publishEvent(new FaultPredictedEvent(
                    this, request.getWellId(), saved));

            if (request.getAutoPublishAlarm()) {
                publishFaultAlarms(saved, config);
            }

            log.info("Saved {} fault predictions for well: {}. Critical: {}, Warning: {}",
                    saved.size(), request.getWellId(),
                    saved.stream().filter(p -> "CRITICAL".equals(p.getSeverityLevel())).count(),
                    saved.stream().filter(p -> "WARNING".equals(p.getSeverityLevel())).count());

            return saved;
        }

        return Collections.emptyList();
    }

    private Map<String, Object> analyzeTimeSeriesData(
            List<PumpingUnitData> data, AdvancedFeaturesProperties.Fault config) {

        Map<String, Object> result = new HashMap<>();

        double[] fluidLevels = data.stream()
                .mapToDouble(d -> d.getDynamicFluidLevel() != null ? d.getDynamicFluidLevel() : 0)
                .toArray();
        double[] currents = data.stream()
                .mapToDouble(d -> d.getMotorCurrent() != null ? d.getMotorCurrent() : 0)
                .toArray();
        double[] pressures = data.stream()
                .mapToDouble(d -> d.getCasingPressure() != null ? d.getCasingPressure() : 0)
                .toArray();
        double[] efficiencies = data.stream()
                .mapToDouble(d -> d.getPumpEfficiency() != null ? d.getPumpEfficiency() : 0)
                .toArray();

        DescriptiveStatistics fluidStats = new DescriptiveStatistics(fluidLevels);
        DescriptiveStatistics currentStats = new DescriptiveStatistics(currents);

        double avgFluidLevel = fluidStats.getMean();
        double avgCurrent = currentStats.getMean();
        double stdFluidLevel = fluidStats.getStandardDeviation();
        double stdCurrent = currentStats.getStandardDeviation();

        double fluidLevelTrend = calculateTrend(fluidLevels);
        double currentTrend = calculateTrend(currents);
        double pressureTrend = calculateTrend(pressures);
        double efficiencyTrend = calculateTrend(efficiencies);

        double fluidLevelRateOfChange = calculateRateOfChange(fluidLevels);
        double currentRateOfChange = calculateRateOfChange(currents);

        int dataSize = data.size();
        int recentSize = Math.min(dataSize / 3, 24);
        double recentAvgFluid = Arrays.stream(fluidLevels, dataSize - recentSize, dataSize).average().orElse(avgFluidLevel);
        double earlierAvgFluid = Arrays.stream(fluidLevels, 0, dataSize - recentSize).average().orElse(avgFluidLevel);
        double recentAvgCurrent = Arrays.stream(currents, dataSize - recentSize, dataSize).average().orElse(avgCurrent);
        double earlierAvgCurrent = Arrays.stream(currents, 0, dataSize - recentSize).average().orElse(avgCurrent);
        double recentAvgEfficiency = Arrays.stream(efficiencies, dataSize - recentSize, dataSize).average().orElse(0);
        double earlierAvgEfficiency = Arrays.stream(efficiencies, 0, dataSize - recentSize).average().orElse(0);

        double currentDeviationFromBaseline = Math.abs(avgCurrent - earlierAvgCurrent) /
                Math.max(earlierAvgCurrent, 1.0);
        double fluidLevelDeviationFromBaseline = Math.abs(recentAvgFluid - earlierAvgFluid) /
                Math.max(earlierAvgFluid, 1.0);

        result.put("avgFluidLevel", avgFluidLevel);
        result.put("avgCurrent", avgCurrent);
        result.put("stdFluidLevel", stdFluidLevel);
        result.put("stdCurrent", stdCurrent);
        result.put("fluidLevelTrend", fluidLevelTrend);
        result.put("currentTrend", currentTrend);
        result.put("pressureTrend", pressureTrend);
        result.put("efficiencyTrend", efficiencyTrend);
        result.put("fluidLevelRateOfChange", fluidLevelRateOfChange);
        result.put("currentRateOfChange", currentRateOfChange);
        result.put("recentAvgFluid", recentAvgFluid);
        result.put("earlierAvgFluid", earlierAvgFluid);
        result.put("recentAvgCurrent", recentAvgCurrent);
        result.put("earlierAvgCurrent", earlierAvgCurrent);
        result.put("recentAvgEfficiency", recentAvgEfficiency);
        result.put("earlierAvgEfficiency", earlierAvgEfficiency);
        result.put("currentDeviation", currentDeviationFromBaseline);
        result.put("fluidLevelDeviation", fluidLevelDeviationFromBaseline);
        result.put("dataPointCount", dataSize);
        result.put("dataQuality", calculateDataQuality(data));

        return result;
    }

    private FaultPrediction analyzeFaultType(
            String wellId, String faultType, Map<String, Object> analysisData,
            LocalDateTime predictionTime, int windowHours, double probabilityThreshold,
            AdvancedFeaturesProperties.Fault config) {

        double probability = 0;
        double anomalyScore = 0;
        String symptoms = "";

        double currentDeviation = (double) analysisData.getOrDefault("currentDeviation", 0.0);
        double fluidLevelDeviation = (double) analysisData.getOrDefault("fluidLevelDeviation", 0.0);
        double fluidLevelTrend = (double) analysisData.getOrDefault("fluidLevelTrend", 0.0);
        double currentTrend = (double) analysisData.getOrDefault("currentTrend", 0.0);
        double efficiencyTrend = (double) analysisData.getOrDefault("efficiencyTrend", 0.0);
        double recentAvgFluid = (double) analysisData.getOrDefault("recentAvgFluid", 0.0);
        double earlierAvgFluid = (double) analysisData.getOrDefault("earlierAvgFluid", 0.0);
        double recentAvgCurrent = (double) analysisData.getOrDefault("recentAvgCurrent", 0.0);
        double earlierAvgCurrent = (double) analysisData.getOrDefault("earlierAvgCurrent", 0.0);
        double dataQuality = (double) analysisData.getOrDefault("dataQuality", 1.0);

        switch (faultType) {
            case "ROD_BREAK":
                double rodBreakProbability = 0;
                if (currentDeviation > config.getRodBreakCurrentDeviationThreshold()) {
                    rodBreakProbability += 0.4;
                    symptoms += "电流偏差超过阈值, ";
                }
                if (currentTrend < -0.1) {
                    rodBreakProbability += 0.3;
                    symptoms += "电流呈下降趋势, ";
                }
                if (fluidLevelTrend > 0.05) {
                    rodBreakProbability += 0.2;
                    symptoms += "动液面呈上升趋势, ";
                }
                if (recentAvgCurrent < earlierAvgCurrent * 0.5) {
                    rodBreakProbability += 0.1;
                    symptoms += "电流骤降超过50%, ";
                }
                probability = Math.min(rodBreakProbability * dataQuality, 0.99);
                anomalyScore = currentDeviation;
                break;

            case "PUMP_LEAK":
                double pumpLeakProbability = 0;
                double fluidLevelRise = recentAvgFluid - earlierAvgFluid;
                if (fluidLevelRise > config.getPumpLeakFluidLevelRiseThreshold()) {
                    pumpLeakProbability += 0.4;
                    symptoms += String.format("动液面上升%.1fm, ", fluidLevelRise);
                }
                if (efficiencyTrend < -0.05) {
                    pumpLeakProbability += 0.3;
                    symptoms += "泵效呈下降趋势, ";
                }
                if (currentTrend > 0.05) {
                    pumpLeakProbability += 0.2;
                    symptoms += "电流呈上升趋势, ";
                }
                if (fluidLevelTrend > 0.1) {
                    pumpLeakProbability += 0.1;
                    symptoms += "动液面快速上升, ";
                }
                probability = Math.min(pumpLeakProbability * dataQuality, 0.99);
                anomalyScore = fluidLevelDeviation;
                break;

            case "GAS_LOCK":
                double gasLockProbability = 0;
                if (currentTrend < -config.getGasLockCurrentDropThreshold()) {
                    gasLockProbability += 0.4;
                    symptoms += "电流显著下降, ";
                }
                if (efficiencyTrend < -0.1) {
                    gasLockProbability += 0.3;
                    symptoms += "泵效快速下降, ";
                }
                if (fluidLevelTrend > 0.05) {
                    gasLockProbability += 0.2;
                    symptoms += "动液面上升, ";
                }
                if (currentDeviation > 0.3) {
                    gasLockProbability += 0.1;
                    symptoms += "电流波动大, ";
                }
                probability = Math.min(gasLockProbability * dataQuality, 0.99);
                anomalyScore = Math.abs(currentTrend) + Math.abs(efficiencyTrend);
                break;

            case "VALVE_LEAK":
                double valveLeakProbability = 0;
                double efficiencyDrop = (double) analysisData.getOrDefault("earlierAvgEfficiency", 0.0) -
                        (double) analysisData.getOrDefault("recentAvgEfficiency", 0.0);
                if (efficiencyDrop > config.getValveLeakEfficiencyDropThreshold()) {
                    valveLeakProbability += 0.4;
                    symptoms += String.format("泵效下降%.1f%%, ", efficiencyDrop * 100);
                }
                if (fluidLevelTrend > 0.03) {
                    valveLeakProbability += 0.25;
                    symptoms += "动液面缓慢上升, ";
                }
                if (currentTrend > 0.03) {
                    valveLeakProbability += 0.2;
                    symptoms += "电流缓慢上升, ";
                }
                if (efficiencyTrend < -0.03) {
                    valveLeakProbability += 0.15;
                    symptoms += "泵效持续下降, ";
                }
                probability = Math.min(valveLeakProbability * dataQuality, 0.99);
                anomalyScore = efficiencyDrop;
                break;

            default:
                return null;
        }

        if (probability < probabilityThreshold * 0.5) {
            return null;
        }

        String predictedFaultTimeStr = "";
        String severityLevel = "";
        LocalDateTime predictedFaultTime = null;

        if (probability >= config.getCriticalFaultThreshold()) {
            severityLevel = "CRITICAL";
            predictedFaultTime = predictionTime.plusHours(Math.max(1, (int) (24 / (probability * 2))));
        } else if (probability >= config.getWarningFaultThreshold()) {
            severityLevel = "WARNING";
            predictedFaultTime = predictionTime.plusHours(Math.max(6, (int) (48 / (probability * 1.5))));
        } else if (probability >= config.getNoticeFaultThreshold()) {
            severityLevel = "NOTICE";
            predictedFaultTime = predictionTime.plusHours(Math.max(24, (int) (72 / probability)));
        } else {
            severityLevel = "LOW";
            predictedFaultTime = predictionTime.plusHours(72);
        }

        String fluidLevelTrendStr = fluidLevelTrend > 0.01 ? "RISING" :
                fluidLevelTrend < -0.01 ? "FALLING" : "STABLE";
        String currentTrendStr = currentTrend > 0.01 ? "RISING" :
                currentTrend < -0.01 ? "FALLING" : "STABLE";

        FaultPrediction prediction = new FaultPrediction();
        prediction.setPredictionId(UUID.randomUUID().toString());
        prediction.setWellId(wellId);
        prediction.setFaultType(faultType);
        prediction.setFaultProbability(probability);
        prediction.setPredictionTime(predictionTime);
        prediction.setPredictedFaultTime(predictedFaultTime);
        prediction.setSeverityLevel(severityLevel);
        prediction.setAnomalyScore(anomalyScore);
        prediction.setFluidLevelTrend(fluidLevelTrendStr);
        prediction.setCurrentTrend(currentTrendStr);
        prediction.setAverageFluidLevel((Double) analysisData.get("avgFluidLevel"));
        prediction.setAverageCurrent((Double) analysisData.get("avgCurrent"));
        prediction.setFluidLevelDeviation((Double) analysisData.get("stdFluidLevel"));
        prediction.setCurrentDeviation((Double) analysisData.get("stdCurrent"));
        prediction.setAnalysisWindowHours(windowHours);
        prediction.setModelConfidence(dataQuality * Math.min(probability + 0.2, 1.0));
        prediction.setSymptoms(symptoms.isEmpty() ? "无明显症状" : symptoms);
        prediction.setCreateTime(LocalDateTime.now());

        return prediction;
    }

    private void addMaintenanceRecommendation(FaultPrediction prediction, AdvancedFeaturesProperties.Fault config) {
        String faultType = prediction.getFaultType();
        double probability = prediction.getFaultProbability();
        String severity = prediction.getSeverityLevel();

        String recommendedAction;
        double estimatedCost;
        int estimatedDowntime;

        switch (faultType) {
            case "ROD_BREAK":
                estimatedCost = config.getRodBreakMaintenanceCost();
                estimatedDowntime = config.getRodBreakDowntimeHours();
                if ("CRITICAL".equals(severity)) {
                    recommendedAction = "立即停产检修，检查抽油杆连接部位，必要时更换抽油杆";
                } else if ("WARNING".equals(severity)) {
                    recommendedAction = "尽快安排修井作业，检查抽油杆磨损情况，预防性更换";
                } else {
                    recommendedAction = "密切关注电流和动液面变化，准备修井预案";
                }
                break;

            case "PUMP_LEAK":
                estimatedCost = config.getPumpLeakMaintenanceCost();
                estimatedDowntime = config.getPumpLeakDowntimeHours();
                if ("CRITICAL".equals(severity)) {
                    recommendedAction = "立即安排检泵作业，检查泵筒和柱塞磨损情况，更换密封件";
                } else if ("WARNING".equals(severity)) {
                    recommendedAction = "近期安排检泵，检查泵效，准备更换泵配件";
                } else {
                    recommendedAction = "监测泵效变化趋势，做好检泵准备";
                }
                break;

            case "GAS_LOCK":
                estimatedCost = config.getGasLockMaintenanceCost();
                estimatedDowntime = config.getGasLockDowntimeHours();
                if ("CRITICAL".equals(severity)) {
                    recommendedAction = "立即采取防气措施，检查气锚，调整抽汲参数，必要时下气锚";
                } else if ("WARNING".equals(severity)) {
                    recommendedAction = "优化抽汲参数，检查套管气回收系统，增加防气措施";
                } else {
                    recommendedAction = "监测套管压力变化，分析气油比，优化工作制度";
                }
                break;

            case "VALVE_LEAK":
                estimatedCost = config.getValveLeakMaintenanceCost();
                estimatedDowntime = config.getValveLeakDowntimeHours();
                if ("CRITICAL".equals(severity)) {
                    recommendedAction = "立即安排检泵作业，检查固定阀和游动阀，更换损坏阀球阀座";
                } else if ("WARNING".equals(severity)) {
                    recommendedAction = "近期安排检泵，检查阀门密封情况，准备阀总成";
                } else {
                    recommendedAction = "监测泵效变化，准备阀总成备件，适时安排检泵";
                }
                break;

            default:
                estimatedCost = 20000.0;
                estimatedDowntime = 12;
                recommendedAction = "请专业技术人员分析诊断，制定检修方案";
        }

        prediction.setRecommendedAction(recommendedAction);
        prediction.setEstimatedMaintenanceCost(estimatedCost);
        prediction.setEstimatedDowntimeHours(estimatedDowntime);
    }

    private void publishFaultAlarms(List<FaultPrediction> predictions, AdvancedFeaturesProperties.Fault config) {
        for (FaultPrediction prediction : predictions) {
            if (!"CRITICAL".equals(prediction.getSeverityLevel()) &&
                !"WARNING".equals(prediction.getSeverityLevel())) {
                continue;
            }

            String alarmType = "FAULT_PREDICTION_" + prediction.getFaultType();
            String alarmLevel = "CRITICAL".equals(prediction.getSeverityLevel()) ? "LEVEL_2" : "LEVEL_1";

            String description = String.format("抽油机故障预警: %s, 概率: %.1f%%, %s",
                    getFaultTypeName(prediction.getFaultType()),
                    prediction.getFaultProbability() * 100,
                    prediction.getSymptoms());

            AlarmTriggeredEvent alarmEvent = new AlarmTriggeredEvent(
                    this,
                    prediction.getWellId(),
                    alarmLevel,
                    alarmType,
                    description,
                    prediction.getPredictionTime()
            );
            eventPublisher.publishEvent(alarmEvent);
        }
    }

    private double calculateTrend(double[] data) {
        if (data.length < 2) return 0;
        double xMean = (data.length - 1) / 2.0;
        double yMean = Arrays.stream(data).average().orElse(0);

        double numerator = 0;
        double denominator = 0;

        for (int i = 0; i < data.length; i++) {
            numerator += (i - xMean) * (data[i] - yMean);
            denominator += (i - xMean) * (i - xMean);
        }

        return denominator > 0 ? numerator / denominator : 0;
    }

    private double calculateRateOfChange(double[] data) {
        if (data.length < 2) return 0;
        double firstHalfAvg = Arrays.stream(data, 0, data.length / 2).average().orElse(0);
        double secondHalfAvg = Arrays.stream(data, data.length / 2, data.length).average().orElse(0);

        if (firstHalfAvg == 0) return 0;
        return (secondHalfAvg - firstHalfAvg) / firstHalfAvg;
    }

    private double calculateDataQuality(List<PumpingUnitData> data) {
        int validCount = 0;
        for (PumpingUnitData d : data) {
            if (d.getDynamicFluidLevel() != null && d.getMotorCurrent() != null &&
                d.getDynamicFluidLevel() > 0 && d.getMotorCurrent() > 0) {
                validCount++;
            }
        }
        return data.isEmpty() ? 0 : (double) validCount / data.size();
    }

    private String getFaultTypeName(String faultType) {
        switch (faultType) {
            case "ROD_BREAK": return "抽油杆断脱";
            case "PUMP_LEAK": return "泵漏失";
            case "GAS_LOCK": return "气锁";
            case "VALVE_LEAK": return "阀漏失";
            default: return faultType;
        }
    }

    @Transactional(readOnly = true)
    public List<FaultPrediction> getLatestPredictions(String wellId) {
        return faultPredictionRepository.findLatestByWellId(wellId);
    }

    @Transactional(readOnly = true)
    public List<FaultPrediction> getUnacknowledgedPredictions() {
        return faultPredictionRepository.findByIsAcknowledgedFalseOrderByPredictionTimeDesc();
    }

    @Transactional(readOnly = true)
    public List<FaultPrediction> getHighPriorityPredictions() {
        return faultPredictionRepository.findHighPriorityUnacknowledged();
    }

    @Transactional(readOnly = true)
    public Map<String, Double> getAllWellFaultProbabilities() {
        Map<String, Double> probabilities = new HashMap<>();
        LocalDateTime startTime = LocalDateTime.now().minusHours(24);
        List<String> wells = faultPredictionRepository.findWellsWithActivePredictions(startTime);

        for (String wellId : wells) {
            Double maxProb = faultPredictionRepository.findMaxFaultProbabilityByWell(wellId, startTime);
            if (maxProb != null) {
                probabilities.put(wellId, maxProb);
            }
        }

        return probabilities;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPredictionSummary(String wellId) {
        List<FaultPrediction> predictions = getLatestPredictions(wellId);

        Map<String, Object> summary = new HashMap<>();
        summary.put("wellId", wellId);
        summary.put("predictions", predictions);

        if (!predictions.isEmpty()) {
            double maxProbability = predictions.stream()
                    .mapToDouble(p -> p.getFaultProbability() != null ? p.getFaultProbability() : 0)
                    .max().orElse(0);

            String highestSeverity = predictions.stream()
                    .map(FaultPrediction::getSeverityLevel)
                    .filter(s -> s != null)
                    .max((a, b) -> {
                        int aRank = "CRITICAL".equals(a) ? 4 : "WARNING".equals(a) ? 3 :
                                   "NOTICE".equals(a) ? 2 : 1;
                        int bRank = "CRITICAL".equals(b) ? 4 : "WARNING".equals(b) ? 3 :
                                   "NOTICE".equals(b) ? 2 : 1;
                        return Integer.compare(aRank, bRank);
                    }).orElse("NONE");

            summary.put("maxFaultProbability", maxProbability);
            summary.put("highestSeverity", highestSeverity);
            summary.put("predictionCount", predictions.size());
            summary.put("criticalCount", predictions.stream()
                    .filter(p -> "CRITICAL".equals(p.getSeverityLevel())).count());
            summary.put("warningCount", predictions.stream()
                    .filter(p -> "WARNING".equals(p.getSeverityLevel())).count());
        } else {
            summary.put("maxFaultProbability", 0.0);
            summary.put("highestSeverity", "NONE");
            summary.put("predictionCount", 0);
        }

        return summary;
    }

    @Transactional
    public FaultPrediction acknowledgePrediction(String predictionId, String acknowledgedBy) {
        Optional<FaultPrediction> opt = faultPredictionRepository.findByPredictionId(predictionId);
        if (opt.isPresent()) {
            FaultPrediction prediction = opt.get();
            prediction.setIsAcknowledged(true);
            prediction.setAcknowledgeTime(LocalDateTime.now());
            prediction.setAcknowledgedBy(acknowledgedBy);
            return faultPredictionRepository.save(prediction);
        }
        return null;
    }

    @Transactional
    public FaultPrediction recordActualFault(String predictionId, LocalDateTime actualFaultTime) {
        Optional<FaultPrediction> opt = faultPredictionRepository.findByPredictionId(predictionId);
        if (opt.isPresent()) {
            FaultPrediction prediction = opt.get();
            prediction.setActualFaultOccurred(true);
            prediction.setActualFaultTime(actualFaultTime);

            if (prediction.getPredictedFaultTime() != null) {
                long hoursDiff = Math.abs(
                        java.time.Duration.between(prediction.getPredictedFaultTime(), actualFaultTime).toHours());
                double accuracy = Math.max(0, 1.0 - (double) hoursDiff / 72.0);
                prediction.setPredictionAccuracy(accuracy);
            }

            return faultPredictionRepository.save(prediction);
        }
        return null;
    }

    @Transactional
    public FaultPrediction savePrediction(FaultPrediction prediction) {
        return faultPredictionRepository.save(prediction);
    }

    @Transactional
    public List<FaultPrediction> saveAllPredictions(List<FaultPrediction> predictions) {
        return faultPredictionRepository.saveAll(predictions);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getBlockPredictions(String blockName) {
        Map<String, Object> result = new HashMap<>();
        List<FaultPrediction> predictions;

        if (blockName == null || "ALL".equalsIgnoreCase(blockName)) {
            LocalDateTime startTime = LocalDateTime.now().minusHours(24);
            predictions = faultPredictionRepository.findAllLatestWithAcknowledged(startTime);
        } else {
            List<Well> productionWells = wellRepository.findByBlockNameAndWellType(blockName, "production");
            List<String> wellIds = productionWells.stream()
                    .map(Well::getWellId)
                    .collect(Collectors.toList());

            predictions = new ArrayList<>();
            LocalDateTime startTime = LocalDateTime.now().minusHours(24);
            for (String wellId : wellIds) {
                List<FaultPrediction> wellPredictions = faultPredictionRepository
                        .findByWellIdAndPredictionTimeAfterOrderByPredictionTimeDesc(wellId, startTime);
                if (!wellPredictions.isEmpty()) {
                    predictions.addAll(wellPredictions);
                }
            }
        }

        result.put("predictions", predictions);
        result.put("totalCount", predictions.size());

        Map<String, Object> stats = new HashMap<>();
        stats.put("criticalCount", predictions.stream()
                .filter(p -> "CRITICAL".equals(p.getSeverityLevel())).count());
        stats.put("warningCount", predictions.stream()
                .filter(p -> "WARNING".equals(p.getSeverityLevel())).count());
        stats.put("noticeCount", predictions.stream()
                .filter(p -> "NOTICE".equals(p.getSeverityLevel())).count());
        result.put("statistics", stats);

        return result;
    }
}
