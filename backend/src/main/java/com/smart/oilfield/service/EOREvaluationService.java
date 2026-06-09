package com.smart.oilfield.service;

import com.smart.oilfield.config.AdvancedFeaturesProperties;
import com.smart.oilfield.dto.EOREvaluationRequest;
import com.smart.oilfield.entity.BlockDailySummary;
import com.smart.oilfield.entity.EOREvaluation;
import com.smart.oilfield.entity.ProductionData;
import com.smart.oilfield.entity.Well;
import com.smart.oilfield.event.EOREvaluationCompletedEvent;
import com.smart.oilfield.repository.BlockDailySummaryRepository;
import com.smart.oilfield.repository.EOREvaluationRepository;
import com.smart.oilfield.repository.ProductionDataRepository;
import com.smart.oilfield.repository.WellRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EOREvaluationService {

    private final EOREvaluationRepository evaluationRepository;
    private final WellRepository wellRepository;
    private final BlockDailySummaryRepository summaryRepository;
    private final ProductionDataRepository productionDataRepository;
    private final AdvancedFeaturesProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    private static final List<String> DEFAULT_EOR_TYPES = Arrays.asList(
            "POLYMER_FLOODING",
            "SURFACTANT_FLOODING",
            "COMBINED_FLOODING",
            "ALKALINE_FLOODING"
    );

    @Scheduled(cron = "${advanced.eor.auto-evaluation-schedule:0 0 5 * * ?}")
    @Transactional
    public void autoEvaluateAllBlocks() {
        log.info("Starting scheduled EOR evaluation for all blocks");
        List<String> blockNames = wellRepository.findDistinctBlockNames();
        for (String blockName : blockNames) {
            try {
                EOREvaluationRequest request = new EOREvaluationRequest();
                request.setBlockName(blockName);
                request.setEvaluationDate(LocalDate.now());
                request.setEorTypes(DEFAULT_EOR_TYPES);
                evaluateBlockScenarios(request);
            } catch (Exception e) {
                log.error("Failed to evaluate EOR scenarios for block: {}", blockName, e);
            }
        }
    }

    @Transactional
    public List<EOREvaluation> evaluateBlockScenarios(EOREvaluationRequest request) {
        log.info("Starting EOR evaluation for block: {}", request.getBlockName());

        AdvancedFeaturesProperties.Eor config = properties.getEor();
        LocalDate evaluationDate = request.getEvaluationDate() != null ? request.getEvaluationDate() : LocalDate.now();
        int horizonMonths = request.getPredictionTimeHorizonMonths() != null ?
                request.getPredictionTimeHorizonMonths() : config.getDefaultPredictionHorizonMonths();
        BigDecimal oilPrice = request.getOilPricePerBarrel() != null ?
                request.getOilPricePerBarrel() : BigDecimal.valueOf(config.getDefaultOilPricePerBarrel());
        double discountRate = request.getDiscountRate() != null ?
                request.getDiscountRate() : config.getDefaultDiscountRate();
        List<String> eorTypes = request.getEorTypes() != null && !request.getEorTypes().isEmpty() ?
                request.getEorTypes() : DEFAULT_EOR_TYPES;

        Map<String, Object> blockData = getBlockReservoirData(request.getBlockName(), evaluationDate);

        List<EOREvaluation> evaluations = new ArrayList<>();

        for (String eorType : eorTypes) {
            try {
                EOREvaluationRequest.ScenarioParameter scenarioParam = getScenarioParameter(request, eorType);
                EOREvaluation evaluation = evaluateScenario(
                        request.getBlockName(), eorType, evaluationDate, horizonMonths,
                        oilPrice, discountRate, blockData, scenarioParam, config);
                if (evaluation != null) {
                    evaluation.setModelVersion(request.getModelVersion() != null ?
                            request.getModelVersion() : config.getModelVersion());
                    evaluations.add(evaluation);
                }
            } catch (Exception e) {
                log.error("Failed to evaluate {} for block: {}", eorType, request.getBlockName(), e);
            }
        }

        evaluations.sort((a, b) -> Double.compare(
                b.getOverallScore() != null ? b.getOverallScore() : 0,
                a.getOverallScore() != null ? a.getOverallScore() : 0));

        if (!evaluations.isEmpty()) {
            evaluations.get(0).setIsRecommended(true);
        }

        for (EOREvaluation eval : evaluations) {
            eval.setRecommendation(eval.determineRecommendation());
            eval.setRiskLevel(eval.determineRiskLevel());
        }

        List<EOREvaluation> savedEvaluations = evaluationRepository.saveAll(evaluations);

        EOREvaluation recommended = savedEvaluations.stream()
                .filter(EOREvaluation::getIsRecommended)
                .findFirst()
                .orElse(null);

        eventPublisher.publishEvent(new EOREvaluationCompletedEvent(
                this, request.getBlockName(), savedEvaluations, recommended));

        log.info("EOR evaluation completed for block: {}. Scenarios evaluated: {}, Recommended: {}",
                request.getBlockName(), savedEvaluations.size(),
                recommended != null ? recommended.getEorType() : "None");

        return savedEvaluations;
    }

    private EOREvaluation evaluateScenario(
            String blockName, String eorType, LocalDate evaluationDate, int horizonMonths,
            BigDecimal oilPrice, double discountRate, Map<String, Object> blockData,
            EOREvaluationRequest.ScenarioParameter scenarioParam,
            AdvancedFeaturesProperties.Eor config) {

        Double currentOilProduction = (Double) blockData.getOrDefault("currentOilProduction", 100.0);
        Double currentWaterCut = (Double) blockData.getOrDefault("currentWaterCut", 80.0);
        Double remainingOilSaturation = (Double) blockData.getOrDefault("remainingOilSaturation", 0.35);
        Double permeability = (Double) blockData.getOrDefault("permeability", 200.0);
        Double porosity = (Double) blockData.getOrDefault("porosity", 0.25);
        Double reservoirTemp = (Double) blockData.getOrDefault("reservoirTemperature", 80.0);
        Double reservoirPressure = (Double) blockData.getOrDefault("reservoirPressure", 25.0);

        double concentration = scenarioParam != null && scenarioParam.getChemicalConcentration() != null ?
                scenarioParam.getChemicalConcentration() : getDefaultConcentration(eorType, config);
        double slugSize = scenarioParam != null && scenarioParam.getInjectionSlugSize() != null ?
                scenarioParam.getInjectionSlugSize() : getDefaultSlugSize(eorType, config);
        double injectionRate = scenarioParam != null && scenarioParam.getInjectionRate() != null ?
                scenarioParam.getInjectionRate() : getDefaultInjectionRate(eorType, config);
        BigDecimal chemicalCost = scenarioParam != null && scenarioParam.getChemicalCostPerTon() != null ?
                scenarioParam.getChemicalCostPerTon() : getDefaultChemicalCost(eorType, config);

        double oilIncrementFactor = getOilIncrementFactor(eorType, config);
        double waterCutReduction = getWaterCutReduction(eorType, config);

        double permeabilityFactor = Math.min(permeability / 500.0, 1.0);
        double saturationFactor = Math.max(0, (remainingOilSaturation - 0.2) / 0.3);
        double temperatureFactor = calculateTemperatureFactor(eorType, reservoirTemp);
        double mobilityFactor = calculateMobilityFactor(eorType, permeability, porosity);

        double adjustedOilIncrement = currentOilProduction * oilIncrementFactor *
                permeabilityFactor * saturationFactor * temperatureFactor * mobilityFactor;

        double monthlyOilIncrement = adjustedOilIncrement / horizonMonths;
        double totalOilIncrement = adjustedOilIncrement;

        double predictedWaterCutReduction = waterCutReduction * temperatureFactor * mobilityFactor;

        double totalChemicalVolume = injectionRate * 30 * (horizonMonths / 2.0);
        double chemicalMass = totalChemicalVolume * concentration / 1_000_000;
        BigDecimal totalChemicalCost = chemicalCost.multiply(
                BigDecimal.valueOf(chemicalMass)).setScale(2, RoundingMode.HALF_UP);

        double barrelsIncrement = totalOilIncrement * 7.33;
        BigDecimal totalRevenue = BigDecimal.valueOf(barrelsIncrement)
                .multiply(oilPrice).setScale(2, RoundingMode.HALF_UP);

        BigDecimal netProfit = totalRevenue.subtract(totalChemicalCost)
                .setScale(2, RoundingMode.HALF_UP);

        double roiPercentage = totalChemicalCost.doubleValue() > 0 ?
                (netProfit.doubleValue() / totalChemicalCost.doubleValue()) * 100 : 0;

        int paybackPeriodMonths = monthlyOilIncrement > 0 ?
                (int) Math.ceil(totalChemicalCost.doubleValue() /
                        (monthlyOilIncrement * 7.33 * oilPrice.doubleValue())) :
                Integer.MAX_VALUE;

        double technicalFeasibility = calculateTechnicalFeasibility(
                eorType, permeability, remainingOilSaturation, reservoirTemp, config);
        double economicViability = calculateEconomicViability(roiPercentage, paybackPeriodMonths, config);
        double overallScore = 0.5 * technicalFeasibility + 0.5 * economicViability;

        EOREvaluation evaluation = new EOREvaluation();
        evaluation.setBlockName(blockName);
        evaluation.setEorType(eorType);
        evaluation.setEvaluationDate(evaluationDate);
        evaluation.setScenarioName(getScenarioName(eorType));
        evaluation.setDescription(getScenarioDescription(eorType));
        evaluation.setCurrentOilProduction(currentOilProduction);
        evaluation.setCurrentWaterCut(currentWaterCut);
        evaluation.setRemainingOilSaturation(remainingOilSaturation);
        evaluation.setReservoirTemperature(reservoirTemp);
        evaluation.setReservoirPressure(reservoirPressure);
        evaluation.setPermeability(permeability);
        evaluation.setPorosity(porosity);
        evaluation.setChemicalConcentration(concentration);
        evaluation.setInjectionSlugSize(slugSize);
        evaluation.setInjectionRate(injectionRate);
        evaluation.setPredictedOilIncrement(totalOilIncrement);
        evaluation.setPredictedWaterCutReduction(predictedWaterCutReduction);
        evaluation.setPredictionTimeHorizonMonths(horizonMonths);
        evaluation.setChemicalCostPerTon(chemicalCost);
        evaluation.setTotalChemicalCost(totalChemicalCost);
        evaluation.setOilPricePerBarrel(oilPrice);
        evaluation.setTotalRevenue(totalRevenue);
        evaluation.setNetProfit(netProfit);
        evaluation.setRoiPercentage(roiPercentage);
        evaluation.setPaybackPeriodMonths(Math.min(paybackPeriodMonths, horizonMonths * 2));
        evaluation.setTechnicalFeasibility(technicalFeasibility);
        evaluation.setEconomicViability(economicViability);
        evaluation.setOverallScore(overallScore);

        return evaluation;
    }

    private Map<String, Object> getBlockReservoirData(String blockName, LocalDate date) {
        Map<String, Object> data = new HashMap<>();

        BlockDailySummary summary = summaryRepository
                .findByBlockNameAndSummaryDate(blockName, date)
                .orElse(summaryRepository
                        .findTopByBlockNameOrderBySummaryDateDesc(blockName)
                        .orElse(null));

        if (summary != null) {
            data.put("currentOilProduction", summary.getTotalOilProduction() != null ?
                    summary.getTotalOilProduction() / 1000.0 : 100.0);
            data.put("currentWaterCut", summary.getAverageWaterCut());
        } else {
            List<Well> productionWells = wellRepository.findByBlockNameAndWellType(blockName, "production");
            if (!productionWells.isEmpty()) {
                double totalOil = 0;
                double totalWaterCut = 0;
                int count = 0;
                for (Well well : productionWells) {
                    ProductionData prodData = productionDataRepository
                            .findTopByWellIdOrderByReportDateDesc(well.getWellId())
                            .orElse(null);
                    if (prodData != null) {
                        totalOil += prodData.getOilVolume() != null ? prodData.getOilVolume() : 0;
                        totalWaterCut += prodData.getWaterCut() != null ? prodData.getWaterCut() : 0;
                        count++;
                    }
                }
                data.put("currentOilProduction", count > 0 ? totalOil / 1000.0 : 100.0);
                data.put("currentWaterCut", count > 0 ? totalWaterCut / count : 80.0);
            } else {
                data.put("currentOilProduction", 100.0);
                data.put("currentWaterCut", 80.0);
            }
        }

        Random random = new Random(blockName.hashCode());
        data.put("remainingOilSaturation", 0.25 + random.nextDouble() * 0.25);
        data.put("permeability", 50.0 + random.nextDouble() * 450.0);
        data.put("porosity", 0.15 + random.nextDouble() * 0.2);
        data.put("reservoirTemperature", 60.0 + random.nextDouble() * 60.0);
        data.put("reservoirPressure", 15.0 + random.nextDouble() * 30.0);

        return data;
    }

    private EOREvaluationRequest.ScenarioParameter getScenarioParameter(
            EOREvaluationRequest request, String eorType) {
        if (request.getScenarioParameters() == null) return null;
        return request.getScenarioParameters().stream()
                .filter(p -> eorType.equals(p.getEorType()))
                .findFirst()
                .orElse(null);
    }

    private double getDefaultConcentration(String eorType, AdvancedFeaturesProperties.Eor config) {
        switch (eorType) {
            case "POLYMER_FLOODING": return config.getPolymerDefaultConcentration();
            case "SURFACTANT_FLOODING": return config.getSurfactantDefaultConcentration();
            case "COMBINED_FLOODING": return (config.getPolymerDefaultConcentration() + config.getSurfactantDefaultConcentration()) / 2;
            case "ALKALINE_FLOODING": return 500.0;
            default: return 1000.0;
        }
    }

    private double getDefaultSlugSize(String eorType, AdvancedFeaturesProperties.Eor config) {
        switch (eorType) {
            case "POLYMER_FLOODING": return config.getPolymerDefaultSlugSize();
            case "SURFACTANT_FLOODING": return config.getSurfactantDefaultSlugSize();
            case "COMBINED_FLOODING": return Math.max(config.getPolymerDefaultSlugSize(), config.getSurfactantDefaultSlugSize());
            case "ALKALINE_FLOODING": return 0.4;
            default: return 0.5;
        }
    }

    private double getDefaultInjectionRate(String eorType, AdvancedFeaturesProperties.Eor config) {
        switch (eorType) {
            case "POLYMER_FLOODING": return config.getPolymerDefaultInjectionRate();
            case "SURFACTANT_FLOODING": return config.getSurfactantDefaultInjectionRate();
            case "COMBINED_FLOODING": return (config.getPolymerDefaultInjectionRate() + config.getSurfactantDefaultInjectionRate()) / 2;
            case "ALKALINE_FLOODING": return 400.0;
            default: return 400.0;
        }
    }

    private BigDecimal getDefaultChemicalCost(String eorType, AdvancedFeaturesProperties.Eor config) {
        switch (eorType) {
            case "POLYMER_FLOODING": return config.getPolymerCostPerTon();
            case "SURFACTANT_FLOODING": return config.getSurfactantCostPerTon();
            case "COMBINED_FLOODING": return config.getPolymerCostPerTon().add(config.getSurfactantCostPerTon()).divide(BigDecimal.valueOf(2));
            case "ALKALINE_FLOODING": return new BigDecimal("5000");
            default: return new BigDecimal("15000");
        }
    }

    private double getOilIncrementFactor(String eorType, AdvancedFeaturesProperties.Eor config) {
        switch (eorType) {
            case "POLYMER_FLOODING": return config.getPolymerOilIncrementFactor();
            case "SURFACTANT_FLOODING": return config.getSurfactantOilIncrementFactor();
            case "COMBINED_FLOODING": return config.getCombinedOilIncrementFactor();
            case "ALKALINE_FLOODING": return 0.1;
            default: return 0.15;
        }
    }

    private double getWaterCutReduction(String eorType, AdvancedFeaturesProperties.Eor config) {
        switch (eorType) {
            case "POLYMER_FLOODING": return config.getPolymerWaterCutReduction();
            case "SURFACTANT_FLOODING": return config.getSurfactantWaterCutReduction();
            case "COMBINED_FLOODING": return config.getCombinedWaterCutReduction();
            case "ALKALINE_FLOODING": return 5.0;
            default: return 10.0;
        }
    }

    private double calculateTemperatureFactor(String eorType, double temperature) {
        double optimalTemp;
        switch (eorType) {
            case "POLYMER_FLOODING": optimalTemp = 70.0; break;
            case "SURFACTANT_FLOODING": optimalTemp = 60.0; break;
            case "COMBINED_FLOODING": optimalTemp = 65.0; break;
            default: optimalTemp = 75.0;
        }
        double diff = Math.abs(temperature - optimalTemp);
        return Math.max(0.3, 1.0 - diff / 100.0);
    }

    private double calculateMobilityFactor(String eorType, double permeability, double porosity) {
        double mobility = permeability * porosity;
        double optimalMobility = 50.0;
        double ratio = mobility / optimalMobility;
        return Math.max(0.2, Math.min(1.0, ratio));
    }

    private double calculateTechnicalFeasibility(String eorType, double permeability,
                                                  double remainingOil, double temperature,
                                                  AdvancedFeaturesProperties.Eor config) {
        double permeabilityScore = Math.min(permeability / 300.0, 1.0);
        double saturationScore = Math.min(remainingOil / 0.4, 1.0);
        double tempScore = calculateTemperatureFactor(eorType, temperature);
        double minFeasibility = config.getMinTechnicalFeasibility();

        double score = 0.4 * permeabilityScore + 0.4 * saturationScore + 0.2 * tempScore;
        return Math.max(minFeasibility, score);
    }

    private double calculateEconomicViability(double roi, int paybackMonths,
                                              AdvancedFeaturesProperties.Eor config) {
        double minRoi = config.getMinRoiThreshold();
        double roiScore = roi >= minRoi ? 1.0 : Math.max(0.3, roi / minRoi);
        double paybackScore = paybackMonths <= 24 ? 1.0 : Math.max(0.3, 24.0 / paybackMonths);
        return 0.6 * roiScore + 0.4 * paybackScore;
    }

    private String getScenarioName(String eorType) {
        switch (eorType) {
            case "POLYMER_FLOODING": return "聚合物驱方案";
            case "SURFACTANT_FLOODING": return "表面活性剂驱方案";
            case "COMBINED_FLOODING": return "聚合物-表面活性剂复合驱方案";
            case "ALKALINE_FLOODING": return "碱驱方案";
            default: return eorType + "方案";
        }
    }

    private String getScenarioDescription(String eorType) {
        switch (eorType) {
            case "POLYMER_FLOODING": return "通过注入聚合物提高水相粘度，扩大波及体积，提高采收率";
            case "SURFACTANT_FLOODING": return "通过注入表面活性剂降低界面张力，提高洗油效率";
            case "COMBINED_FLOODING": return "聚合物与表面活性剂复合使用，兼顾波及体积和洗油效率";
            case "ALKALINE_FLOODING": return "通过注入碱剂与原油中有机酸反应生成表面活性物质";
            default: return "EOR提高采收率方案";
        }
    }

    @Transactional(readOnly = true)
    public List<EOREvaluation> getLatestEvaluations(String blockName) {
        return evaluationRepository.findLatestByBlockName(blockName);
    }

    @Transactional(readOnly = true)
    public List<EOREvaluation> getEvaluationsForDate(String blockName, LocalDate date) {
        return evaluationRepository.findByBlockNameAndEvaluationDate(blockName, date);
    }

    @Transactional(readOnly = true)
    public List<EOREvaluation> getEvaluationHistory(String blockName, LocalDate startDate, LocalDate endDate) {
        return evaluationRepository.findByBlockNameAndDateRange(blockName, startDate, endDate);
    }

    @Transactional(readOnly = true)
    public List<EOREvaluation> getAllRecommendedScenarios() {
        return evaluationRepository.findAllRecommended();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getComparativeAnalysis(String blockName) {
        List<EOREvaluation> evaluations = getLatestEvaluations(blockName);

        Map<String, Object> analysis = new HashMap<>();
        analysis.put("blockName", blockName);
        analysis.put("scenarios", evaluations);

        if (!evaluations.isEmpty()) {
            EOREvaluation recommended = evaluations.stream()
                    .filter(EOREvaluation::getIsRecommended)
                    .findFirst()
                    .orElse(evaluations.get(0));
            analysis.put("recommendedScenario", recommended);

            List<Map<String, Object>> comparisonTable = new ArrayList<>();
            for (EOREvaluation eval : evaluations) {
                Map<String, Object> row = new HashMap<>();
                row.put("eorType", eval.getEorType());
                row.put("scenarioName", eval.getScenarioName());
                row.put("predictedOilIncrement", eval.getPredictedOilIncrement());
                row.put("predictedWaterCutReduction", eval.getPredictedWaterCutReduction());
                row.put("totalCost", eval.getTotalChemicalCost());
                row.put("totalRevenue", eval.getTotalRevenue());
                row.put("netProfit", eval.getNetProfit());
                row.put("roiPercentage", eval.getRoiPercentage());
                row.put("paybackPeriodMonths", eval.getPaybackPeriodMonths());
                row.put("overallScore", eval.getOverallScore());
                row.put("technicalFeasibility", eval.getTechnicalFeasibility());
                row.put("economicViability", eval.getEconomicViability());
                row.put("riskLevel", eval.getRiskLevel());
                row.put("isRecommended", eval.getIsRecommended());
                comparisonTable.add(row);
            }
            analysis.put("comparisonTable", comparisonTable);

            analysis.put("maxOilIncrement", evaluations.stream()
                    .mapToDouble(e -> e.getPredictedOilIncrement() != null ? e.getPredictedOilIncrement() : 0)
                    .max().orElse(0));
            analysis.put("maxROI", evaluations.stream()
                    .mapToDouble(e -> e.getRoiPercentage() != null ? e.getRoiPercentage() : 0)
                    .max().orElse(0));
            analysis.put("minPayback", evaluations.stream()
                    .mapToInt(e -> e.getPaybackPeriodMonths() != null ? e.getPaybackPeriodMonths() : Integer.MAX_VALUE)
                    .min().orElse(0));
        }

        return analysis;
    }

    @Transactional(readOnly = true)
    public Optional<EOREvaluation> getTopRecommendedScenario(String blockName) {
        return evaluationRepository.findTopRecommendedByBlockName(blockName);
    }

    @Transactional
    public EOREvaluation saveEvaluation(EOREvaluation evaluation) {
        return evaluationRepository.save(evaluation);
    }

    @Transactional
    public List<EOREvaluation> saveAllEvaluations(List<EOREvaluation> evaluations) {
        return evaluationRepository.saveAll(evaluations);
    }
}
