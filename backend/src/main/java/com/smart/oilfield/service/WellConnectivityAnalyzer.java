package com.smart.oilfield.service;

import com.smart.oilfield.config.AdvancedFeaturesProperties;
import com.smart.oilfield.dto.ConnectivityAnalysisRequest;
import com.smart.oilfield.entity.WaterInjectionData;
import com.smart.oilfield.entity.Well;
import com.smart.oilfield.entity.WellConnectivity;
import com.smart.oilfield.event.ConnectivityAnalysisCompletedEvent;
import com.smart.oilfield.event.WellDataReceivedEvent;
import com.smart.oilfield.repository.WaterInjectionDataRepository;
import com.smart.oilfield.repository.WellConnectivityRepository;
import com.smart.oilfield.repository.WellRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WellConnectivityAnalyzer {

    private final WellConnectivityRepository connectivityRepository;
    private final WellRepository wellRepository;
    private final WaterInjectionDataRepository injectionDataRepository;
    private final AdvancedFeaturesProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final PearsonsCorrelation pearsonsCorrelation = new PearsonsCorrelation();

    @Async
    @EventListener
    @Transactional
    public void onDataReceived(WellDataReceivedEvent event) {
        log.info("Triggering connectivity analysis for well: {}", event.getWellId());
        Well well = wellRepository.findByWellId(event.getWellId()).orElse(null);
        if (well == null || !"injection".equals(well.getWellType())) {
            return;
        }
        ConnectivityAnalysisRequest request = new ConnectivityAnalysisRequest();
        request.setBlockName(well.getBlockName());
        request.setAnalysisDate(LocalDate.now());
        analyzeBlockConnectivity(request);
    }

    @Scheduled(cron = "${advanced.connectivity.auto-analysis-schedule:0 0 3 * * ?}")
    @Transactional
    public void autoAnalyzeAllBlocks() {
        log.info("Starting scheduled connectivity analysis for all blocks");
        List<String> blockNames = wellRepository.findDistinctBlockNames();
        for (String blockName : blockNames) {
            try {
                ConnectivityAnalysisRequest request = new ConnectivityAnalysisRequest();
                request.setBlockName(blockName);
                request.setAnalysisDate(LocalDate.now());
                analyzeBlockConnectivity(request);
            } catch (Exception e) {
                log.error("Failed to analyze connectivity for block: {}", blockName, e);
            }
        }
    }

    @Transactional
    public List<WellConnectivity> analyzeBlockConnectivity(ConnectivityAnalysisRequest request) {
        log.info("Starting connectivity analysis for block: {}", request.getBlockName());

        AdvancedFeaturesProperties.Connectivity config = properties.getConnectivity();
        LocalDate analysisDate = request.getAnalysisDate() != null ? request.getAnalysisDate() : LocalDate.now();
        int windowDays = request.getAnalysisWindowDays() != null ?
                Math.max(request.getAnalysisWindowDays(), config.getMinAnalysisWindowDays()) :
                config.getDefaultAnalysisWindowDays();
        LocalDate startDate = analysisDate.minusDays(windowDays);
        double significanceThreshold = request.getSignificanceThreshold() != null ?
                request.getSignificanceThreshold() : config.getSignificanceThreshold();
        int maxTimeLagHours = request.getMaxTimeLagHours() != null ?
                request.getMaxTimeLagHours() : config.getMaxTimeLagHours();

        List<Well> injectionWells = wellRepository.findByBlockNameAndWellType(request.getBlockName(), "injection");
        List<Well> productionWells = wellRepository.findByBlockNameAndWellType(request.getBlockName(), "production");

        if (injectionWells.isEmpty() || productionWells.isEmpty()) {
            log.warn("No injection or production wells found for block: {}", request.getBlockName());
            return Collections.emptyList();
        }

        log.info("Analyzing {} injection wells and {} production wells", injectionWells.size(), productionWells.size());

        Map<String, List<WaterInjectionData>> injectionPressureData = new HashMap<>();
        for (Well injectionWell : injectionWells) {
            List<WaterInjectionData> data = injectionDataRepository
                    .findByWellIdAndReportDateBetweenOrderByReportDate(
                            injectionWell.getWellId(), startDate, analysisDate);
            injectionPressureData.put(injectionWell.getWellId(), data);
        }

        List<WellConnectivity> results = new ArrayList<>();

        for (Well injectionWell : injectionWells) {
            List<WaterInjectionData> injData = injectionPressureData.get(injectionWell.getWellId());
            if (injData == null || injData.size() < config.getMinAnalysisWindowDays()) {
                continue;
            }

            double[] injPressure = injData.stream()
                    .mapToDouble(d -> d.getInjectionPressure() != null ? d.getInjectionPressure() : 0.0)
                    .toArray();

            for (Well productionWell : productionWells) {
                Optional<WellConnectivity> existing = connectivityRepository
                        .findByInjectionWellIdAndProductionWellIdAndAnalysisDate(
                                injectionWell.getWellId(), productionWell.getWellId(), analysisDate);
                if (existing.isPresent()) {
                    results.add(existing.get());
                    continue;
                }

                try {
                    WellConnectivity connectivity = analyzeWellPairConnectivity(
                            injectionWell, productionWell, injPressure,
                            startDate, analysisDate, windowDays,
                            maxTimeLagHours, significanceThreshold, config);
                    if (connectivity != null) {
                        results.add(connectivity);
                    }
                } catch (Exception e) {
                    log.error("Failed to analyze connectivity between {} and {}",
                            injectionWell.getWellId(), productionWell.getWellId(), e);
                }
            }
        }

        List<WellConnectivity> significantResults = results.stream()
                .filter(c -> request.getIncludeWeakConnections() || c.getIsSignificant())
                .collect(Collectors.toList());

        if (!significantResults.isEmpty()) {
            connectivityRepository.saveAll(significantResults);
            eventPublisher.publishEvent(new ConnectivityAnalysisCompletedEvent(
                    this, request.getBlockName(), significantResults));
            log.info("Saved {} connectivity results for block: {}", significantResults.size(), request.getBlockName());
        }

        return significantResults;
    }

    private WellConnectivity analyzeWellPairConnectivity(
            Well injectionWell, Well productionWell, double[] injPressure,
            LocalDate startDate, LocalDate analysisDate, int windowDays,
            int maxTimeLagHours, double significanceThreshold,
            AdvancedFeaturesProperties.Connectivity config) {

        List<WaterInjectionData> prodDataList = injectionDataRepository
                .findByWellIdAndReportDateBetweenOrderByReportDate(
                        productionWell.getWellId(), startDate, analysisDate);

        if (prodDataList.size() < config.getMinAnalysisWindowDays()) {
            return null;
        }

        double[] prodPressure = prodDataList.stream()
                .mapToDouble(d -> d.getInjectionPressure() != null ? d.getInjectionPressure() : 0.0)
                .toArray();

        int minLength = Math.min(injPressure.length, prodPressure.length);
        if (minLength < config.getMinAnalysisWindowDays()) {
            return null;
        }

        double[] injPressureAligned = Arrays.copyOfRange(injPressure, injPressure.length - minLength, injPressure.length);
        double[] prodPressureAligned = Arrays.copyOfRange(prodPressure, prodPressure.length - minLength, prodPressure.length);

        double dataQuality = calculateDataQuality(injPressureAligned, prodPressureAligned);
        if (dataQuality < config.getMinDataQualityThreshold()) {
            return null;
        }

        double pearsonCorrelation = calculatePearsonCorrelation(injPressureAligned, prodPressureAligned);

        CrossCorrelationResult crossCorrResult = calculateTimeLagCrossCorrelation(
                injPressureAligned, prodPressureAligned, maxTimeLagHours);

        double connectivityStrength = calculateConnectivityStrength(
                pearsonCorrelation, crossCorrResult.maxCorrelation, dataQuality, config);

        String connectivityType = determineConnectivityType(connectivityStrength, config);
        boolean isSignificant = connectivityStrength >= significanceThreshold;

        WellConnectivity connectivity = new WellConnectivity();
        connectivity.setInjectionWellId(injectionWell.getWellId());
        connectivity.setProductionWellId(productionWell.getWellId());
        connectivity.setAnalysisDate(analysisDate);
        connectivity.setPearsonCorrelation(pearsonCorrelation);
        connectivity.setTimeLagHours(crossCorrResult.optimalLag);
        connectivity.setCrossCorrelation(crossCorrResult.maxCorrelation);
        connectivity.setConnectivityStrength(connectivityStrength);
        connectivity.setConnectivityType(connectivityType);
        connectivity.setConfidenceLevel(calculateConfidenceLevel(pearsonCorrelation, minLength));
        connectivity.setAnalysisWindowDays(windowDays);
        connectivity.setPressureDataQuality(dataQuality);
        connectivity.setIsSignificant(isSignificant);

        return connectivity;
    }

    public double calculatePearsonCorrelation(double[] x, double[] y) {
        if (x.length < 2 || y.length < 2 || x.length != y.length) {
            return 0.0;
        }
        try {
            return pearsonsCorrelation.correlation(x, y);
        } catch (Exception e) {
            log.warn("Failed to calculate Pearson correlation: {}", e.getMessage());
            return 0.0;
        }
    }

    public CrossCorrelationResult calculateTimeLagCrossCorrelation(double[] x, double[] y, int maxLagHours) {
        int maxLag = Math.min(maxLagHours, Math.min(x.length, y.length) / 2);
        double maxCorrelation = Double.NEGATIVE_INFINITY;
        int optimalLag = 0;

        for (int lag = 0; lag <= maxLag; lag++) {
            double[] xLagged = Arrays.copyOfRange(x, lag, x.length);
            double[] yAligned = Arrays.copyOfRange(y, 0, y.length - lag);

            if (xLagged.length < 5) break;

            double corr = calculatePearsonCorrelation(xLagged, yAligned);
            if (Math.abs(corr) > Math.abs(maxCorrelation)) {
                maxCorrelation = corr;
                optimalLag = lag;
            }
        }

        CrossCorrelationResult result = new CrossCorrelationResult();
        result.maxCorrelation = maxCorrelation != Double.NEGATIVE_INFINITY ? maxCorrelation : 0.0;
        result.optimalLag = optimalLag;
        return result;
    }

    private double calculateDataQuality(double[] x, double[] y) {
        int validCount = 0;
        for (int i = 0; i < x.length; i++) {
            if (x[i] > 0 && y[i] > 0) {
                validCount++;
            }
        }
        return (double) validCount / x.length;
    }

    private double calculateConnectivityStrength(double pearsonCorr, double crossCorr,
                                                  double dataQuality,
                                                  AdvancedFeaturesProperties.Connectivity config) {
        double pearsonWeight = 0.4;
        double crossCorrWeight = 0.4;
        double dataQualityWeight = 0.2;

        double normalizedPearson = Math.abs(pearsonCorr);
        double normalizedCrossCorr = Math.abs(crossCorr);

        return pearsonWeight * normalizedPearson +
               crossCorrWeight * normalizedCrossCorr +
               dataQualityWeight * dataQuality;
    }

    private String determineConnectivityType(double strength, AdvancedFeaturesProperties.Connectivity config) {
        if (strength >= config.getStrongConnectivityThreshold()) {
            return "STRONG";
        } else if (strength >= config.getModerateConnectivityThreshold()) {
            return "MODERATE";
        } else if (strength >= config.getWeakConnectivityThreshold()) {
            return "WEAK";
        }
        return "NONE";
    }

    private double calculateConfidenceLevel(double correlation, int sampleSize) {
        double tStatistic = correlation * Math.sqrt((sampleSize - 2) / (1 - correlation * correlation));
        double confidence = 1.0 - Math.exp(-Math.abs(tStatistic) / 3.0);
        return Math.min(Math.max(confidence, 0.0), 1.0);
    }

    @Transactional(readOnly = true)
    public List<WellConnectivity> getLatestConnectivityForWell(String wellId) {
        return connectivityRepository.findLatestByWellId(wellId);
    }

    @Transactional(readOnly = true)
    public List<WellConnectivity> getConnectivityForDate(LocalDate date) {
        return connectivityRepository.findByAnalysisDate(date);
    }

    @Transactional(readOnly = true)
    public List<WellConnectivity> getSignificantConnectivity(LocalDate startDate, LocalDate endDate) {
        return connectivityRepository.findSignificantInDateRange(startDate, endDate);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getConnectivityMapData(String blockName, LocalDate date) {
        List<WellConnectivity> connectivityList = getConnectivityForDate(date);

        List<Map<String, Object>> lines = connectivityList.stream()
                .filter(c -> {
                    Well injWell = wellRepository.findByWellId(c.getInjectionWellId()).orElse(null);
                    return injWell != null && blockName.equals(injWell.getBlockName());
                })
                .map(c -> {
                    Map<String, Object> line = new HashMap<>();
                    line.put("injectionWellId", c.getInjectionWellId());
                    line.put("productionWellId", c.getProductionWellId());
                    line.put("connectivityStrength", c.getConnectivityStrength());
                    line.put("connectivityType", c.getConnectivityType());
                    line.put("pearsonCorrelation", c.getPearsonCorrelation());
                    line.put("timeLagHours", c.getTimeLagHours());
                    line.put("isSignificant", c.getIsSignificant());
                    return line;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("lines", lines);
        result.put("analysisDate", date);
        result.put("totalConnections", lines.size());
        result.put("strongConnections", lines.stream()
                .filter(l -> "STRONG".equals(l.get("connectivityType"))).count());
        result.put("moderateConnections", lines.stream()
                .filter(l -> "MODERATE".equals(l.get("connectivityType"))).count());

        return result;
    }

    @Transactional
    public WellConnectivity saveConnectivity(WellConnectivity connectivity) {
        return connectivityRepository.save(connectivity);
    }

    @Transactional
    public List<WellConnectivity> saveAllConnectivity(List<WellConnectivity> connectivityList) {
        return connectivityRepository.saveAll(connectivityList);
    }

    public static class CrossCorrelationResult {
        public double maxCorrelation;
        public int optimalLag;
    }
}
