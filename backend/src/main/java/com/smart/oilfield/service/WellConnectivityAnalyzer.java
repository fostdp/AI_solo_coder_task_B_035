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

        Map<String, double[]> injectionPressureMap = new HashMap<>();
        Map<String, double[]> productionPressureMap = new HashMap<>();

        for (Well injectionWell : injectionWells) {
            List<WaterInjectionData> data = injectionDataRepository
                    .findByWellIdAndReportDateBetweenOrderByReportDate(
                            injectionWell.getWellId(), startDate, analysisDate);
            if (data.size() >= config.getMinAnalysisWindowDays()) {
                double[] pressures = data.stream()
                        .mapToDouble(d -> d.getInjectionPressure() != null ? d.getInjectionPressure() : 0.0)
                        .toArray();
                injectionPressureMap.put(injectionWell.getWellId(), pressures);
            }
        }

        for (Well productionWell : productionWells) {
            List<WaterInjectionData> data = injectionDataRepository
                    .findByWellIdAndReportDateBetweenOrderByReportDate(
                            productionWell.getWellId(), startDate, analysisDate);
            if (data.size() >= config.getMinAnalysisWindowDays()) {
                double[] pressures = data.stream()
                        .mapToDouble(d -> d.getInjectionPressure() != null ? d.getInjectionPressure() : 0.0)
                        .toArray();
                productionPressureMap.put(productionWell.getWellId(), pressures);
            }
        }

        List<WellConnectivity> preliminaryResults = new ArrayList<>();

        for (Well injectionWell : injectionWells) {
            double[] injPressure = injectionPressureMap.get(injectionWell.getWellId());
            if (injPressure == null) continue;

            for (Well productionWell : productionWells) {
                double[] prodPressure = productionPressureMap.get(productionWell.getWellId());
                if (prodPressure == null) continue;

                Optional<WellConnectivity> existing = connectivityRepository
                        .findByInjectionWellIdAndProductionWellIdAndAnalysisDate(
                                injectionWell.getWellId(), productionWell.getWellId(), analysisDate);
                if (existing.isPresent()) {
                    preliminaryResults.add(existing.get());
                    continue;
                }

                try {
                    WellConnectivity connectivity = analyzeWellPairConnectivity(
                            injectionWell, productionWell, injPressure, prodPressure,
                            startDate, analysisDate, windowDays,
                            maxTimeLagHours, significanceThreshold, config);
                    if (connectivity != null) {
                        preliminaryResults.add(connectivity);
                    }
                } catch (Exception e) {
                    log.error("Failed to analyze connectivity between {} and {}",
                            injectionWell.getWellId(), productionWell.getWellId(), e);
                }
            }
        }

        if (config.isEnableGraphModelScreening() && injectionWells.size() > 1) {
            preliminaryResults = performGraphModelScreening(
                    preliminaryResults, injectionPressureMap, productionPressureMap, config);
            long spuriousCount = preliminaryResults.stream()
                    .filter(WellConnectivity::getIsSpuriousEdge)
                    .count();
            long retainedCount = preliminaryResults.stream()
                    .filter(c -> !c.getIsSpuriousEdge())
                    .count();
            log.info("Graph model screening completed, retained {} of {} connections, filtered {} spurious edges",
                    retainedCount, preliminaryResults.size(), spuriousCount);
        }

        List<WellConnectivity> significantResults = preliminaryResults.stream()
                .filter(c -> request.getIncludeWeakConnections() || c.getIsSignificant())
                .filter(c -> !c.getIsSpuriousEdge())
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
            Well injectionWell, Well productionWell, double[] injPressure, double[] prodPressure,
            LocalDate startDate, LocalDate analysisDate, int windowDays,
            int maxTimeLagHours, double significanceThreshold,
            AdvancedFeaturesProperties.Connectivity config) {

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
        connectivity.setPartialCorrelation(null);
        connectivity.setConditionedWellCount(0);
        connectivity.setIsSpuriousEdge(false);
        connectivity.setGraphModelPValue(null);

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

    public double calculatePartialCorrelation(double[] x, double[] y, List<double[]> controls) {
        if (x.length < 3 || y.length < 3 || x.length != y.length) {
            return 0.0;
        }

        int n = x.length;
        int k = controls.size();

        if (k == 0) {
            return calculatePearsonCorrelation(x, y);
        }

        try {
            double[][] data = new double[n][k + 2];
            for (int i = 0; i < n; i++) {
                data[i][0] = x[i];
                data[i][1] = y[i];
                for (int j = 0; j < k; j++) {
                    data[i][j + 2] = controls.get(j)[i];
                }
            }

            double[][] corrMatrix = calculateCorrelationMatrix(data);
            double[][] invCorrMatrix = invertMatrix(corrMatrix);

            if (invCorrMatrix == null) {
                return calculatePearsonCorrelation(x, y);
            }

            double partialCorr = -invCorrMatrix[0][1] /
                    Math.sqrt(Math.abs(invCorrMatrix[0][0] * invCorrMatrix[1][1]));

            return Double.isNaN(partialCorr) ? 0.0 : partialCorr;

        } catch (Exception e) {
            log.warn("Failed to calculate partial correlation: {}", e.getMessage());
            return calculatePearsonCorrelation(x, y);
        }
    }

    private double[][] calculateCorrelationMatrix(double[][] data) {
        int nVars = data[0].length;
        int nObs = data.length;
        double[][] corrMatrix = new double[nVars][nVars];

        for (int i = 0; i < nVars; i++) {
            for (int j = i; j < nVars; j++) {
                if (i == j) {
                    corrMatrix[i][j] = 1.0;
                } else {
                    double[] x = new double[nObs];
                    double[] y = new double[nObs];
                    for (int k = 0; k < nObs; k++) {
                        x[k] = data[k][i];
                        y[k] = data[k][j];
                    }
                    double corr = calculatePearsonCorrelation(x, y);
                    corrMatrix[i][j] = corr;
                    corrMatrix[j][i] = corr;
                }
            }
        }
        return corrMatrix;
    }

    private double[][] invertMatrix(double[][] matrix) {
        int n = matrix.length;
        double[][] augmented = new double[n][2 * n];

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                augmented[i][j] = matrix[i][j];
            }
            augmented[i][i + n] = 1.0;
        }

        for (int i = 0; i < n; i++) {
            double max = Math.abs(augmented[i][i]);
            int maxRow = i;
            for (int k = i + 1; k < n; k++) {
                if (Math.abs(augmented[k][i]) > max) {
                    max = Math.abs(augmented[k][i]);
                    maxRow = k;
                }
            }

            double[] temp = augmented[i];
            augmented[i] = augmented[maxRow];
            augmented[maxRow] = temp;

            if (Math.abs(augmented[i][i]) < 1e-10) {
                return null;
            }

            double div = augmented[i][i];
            for (int j = i; j < 2 * n; j++) {
                augmented[i][j] /= div;
            }

            for (int k = 0; k < n; k++) {
                if (k != i) {
                    double factor = augmented[k][i];
                    for (int j = i; j < 2 * n; j++) {
                        augmented[k][j] -= factor * augmented[i][j];
                    }
                }
            }
        }

        double[][] inverse = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                inverse[i][j] = augmented[i][j + n];
            }
        }
        return inverse;
    }

    private List<WellConnectivity> performGraphModelScreening(
            List<WellConnectivity> preliminaryResults,
            Map<String, double[]> injectionPressureMap,
            Map<String, double[]> productionPressureMap,
            AdvancedFeaturesProperties.Connectivity config) {

        Map<String, Map<String, WellConnectivity>> connectivityGraph = new HashMap<>();
        for (WellConnectivity conn : preliminaryResults) {
            connectivityGraph
                    .computeIfAbsent(conn.getProductionWellId(), k -> new HashMap<>())
                    .put(conn.getInjectionWellId(), conn);
        }

        for (Map.Entry<String, Map<String, WellConnectivity>> prodEntry : connectivityGraph.entrySet()) {
            String prodWellId = prodEntry.getKey();
            Map<String, WellConnectivity> connections = prodEntry.getValue();

            if (connections.size() < 2) continue;

            double[] prodPressure = productionPressureMap.get(prodWellId);
            if (prodPressure == null) continue;

            List<Map.Entry<String, WellConnectivity>> sortedConnections =
                    connections.entrySet().stream()
                            .sorted((a, b) -> Double.compare(
                                    b.getValue().getConnectivityStrength(),
                                    a.getValue().getConnectivityStrength()))
                            .collect(Collectors.toList());

            for (int i = 0; i < sortedConnections.size(); i++) {
                Map.Entry<String, WellConnectivity> targetEntry = sortedConnections.get(i);
                String targetInjId = targetEntry.getKey();
                WellConnectivity targetConn = targetEntry.getValue();

                double[] targetInjPressure = injectionPressureMap.get(targetInjId);
                if (targetInjPressure == null) continue;

                int minLen = Math.min(targetInjPressure.length, prodPressure.length);
                double[] xAligned = Arrays.copyOfRange(targetInjPressure, targetInjPressure.length - minLen, targetInjPressure.length);
                double[] yAligned = Arrays.copyOfRange(prodPressure, prodPressure.length - minLen, prodPressure.length);

                List<double[]> controls = new ArrayList<>();
                int controlCount = 0;
                for (int j = 0; j < sortedConnections.size() && controlCount < config.getMaxConditioningVariables(); j++) {
                    if (j == i) continue;
                    String controlInjId = sortedConnections.get(j).getKey();
                    double[] controlPressure = injectionPressureMap.get(controlInjId);
                    if (controlPressure != null && controlPressure.length >= minLen) {
                        double[] controlAligned = Arrays.copyOfRange(
                                controlPressure, controlPressure.length - minLen, controlPressure.length);
                        controls.add(controlAligned);
                        controlCount++;
                    }
                }

                double partialCorr = calculatePartialCorrelation(xAligned, yAligned, controls);
                double pValue = calculatePartialCorrelationPValue(partialCorr, minLen, controls.size());

                targetConn.setPartialCorrelation(partialCorr);
                targetConn.setConditionedWellCount(controls.size());
                targetConn.setGraphModelPValue(pValue);

                double pearsonCorr = targetConn.getPearsonCorrelation() != null ?
                        Math.abs(targetConn.getPearsonCorrelation()) : 0;
                double partialCorrAbs = Math.abs(partialCorr);

                if (pearsonCorr > config.getSignificanceThreshold() &&
                        partialCorrAbs < config.getSpuriousEdgeThreshold()) {
                    targetConn.setIsSpuriousEdge(true);
                    targetConn.setIsSignificant(false);
                    log.info("Identified spurious edge: {} -> {}, pearson={}, partial={}",
                            targetInjId, prodWellId, String.format("%.4f", pearsonCorr),
                            String.format("%.4f", partialCorrAbs));
                } else if (partialCorrAbs < config.getPartialCorrelationThreshold()) {
                    targetConn.setIsSignificant(false);
                }
            }
        }

        return preliminaryResults;
    }

    private double calculatePartialCorrelationPValue(double partialCorr, int sampleSize, int numControls) {
        if (sampleSize <= numControls + 3) {
            return 1.0;
        }

        double absCorr = Math.abs(partialCorr);
        if (absCorr >= 1.0) {
            return 0.0;
        }

        int df = sampleSize - numControls - 3;
        double tStat = partialCorr * Math.sqrt(df / (1 - partialCorr * partialCorr));

        double pValue = 2.0 * (1 - calculateTStudentCDF(Math.abs(tStat), df));
        return Math.max(0.0, Math.min(1.0, pValue));
    }

    private double calculateTStudentCDF(double t, int df) {
        if (df <= 0) return 0.5;

        double x = (t + Math.sqrt(t * t + df)) / (2.0 * Math.sqrt(t * t + df));
        double a = df / 2.0;
        double b = df / 2.0;

        return regularizedIncompleteBeta(x, a, b);
    }

    private double regularizedIncompleteBeta(double x, double a, double b) {
        if (x <= 0.0) return 0.0;
        if (x >= 1.0) return 1.0;

        double bt = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b) +
                a * Math.log(x) + b * Math.log(1.0 - x));

        if (x < (a + 1.0) / (a + b + 2.0)) {
            return bt * calculateBetaCF(x, a, b) / a;
        } else {
            return 1.0 - bt * calculateBetaCF(1.0 - x, b, a) / b;
        }
    }

    private double logGamma(double x) {
        double[] coef = {
                76.18009172947146, -86.50532032941677,
                24.01409824083091, -1.231739572450155,
                0.1208650973866179e-2, -0.5395239384953e-5
        };
        double y = x;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (int j = 0; j < 6; j++) {
            ser += coef[j] / ++y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }

    private double calculateBetaCF(double x, double a, double b) {
        int maxIter = 100;
        double eps = 3.0e-7;
        double qab = a + b;
        double qap = a + 1.0;
        double qam = a - 1.0;
        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < 1e-30) d = 1e-30;
        d = 1.0 / d;
        double h = d;

        for (int m = 1; m <= maxIter; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < 1e-30) d = 1e-30;
            c = 1.0 + aa / c;
            if (Math.abs(c) < 1e-30) c = 1e-30;
            d = 1.0 / d;
            h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < 1e-30) d = 1e-30;
            c = 1.0 + aa / c;
            if (Math.abs(c) < 1e-30) c = 1e-30;
            d = 1.0 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1.0) < eps) break;
        }
        return h;
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
