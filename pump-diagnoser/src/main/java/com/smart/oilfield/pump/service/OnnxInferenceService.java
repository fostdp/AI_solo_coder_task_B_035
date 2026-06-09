package com.smart.oilfield.pump.service;

import com.smart.oilfield.common.entity.PumpingUnitData;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import ai.onnxruntime.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class OnnxInferenceService {

    @Value("${onnx.model.path:models/fault_detection_model.onnx}")
    private String modelPath;

    @Value("${onnx.num-threads:4}")
    private int numThreads;

    @Value("${onnx.execution-provider:CPU}")
    private String executionProvider;

    private OrtEnvironment environment;
    private OrtSession session;

    private final AtomicLong totalInferenceCount = new AtomicLong(0);
    private final AtomicLong totalInferenceTimeNs = new AtomicLong(0);
    private final ConcurrentHashMap<String, Long> inferenceCountByType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> inferenceTimeByType = new ConcurrentHashMap<>();

    private static final int INPUT_FEATURE_COUNT = 8;
    private static final int OUTPUT_CLASS_COUNT = 4;

    private static final List<String> FAULT_TYPES = Arrays.asList(
            "ROD_BREAK", "PUMP_LEAK", "GAS_LOCK", "VALVE_LEAK"
    );

    @PostConstruct
    public void init() {
        try {
            environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setInterOpNumThreads(numThreads);
            options.setIntraOpNumThreads(numThreads);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

            if ("CUDA".equalsIgnoreCase(executionProvider)) {
                try {
                    options.addCUDA(0);
                    log.info("ONNX Runtime using CUDA execution provider");
                } catch (OrtException e) {
                    log.warn("CUDA not available, falling back to CPU: {}", e.getMessage());
                }
            } else if ("TensorRT".equalsIgnoreCase(executionProvider)) {
                try {
                    options.addTensorrt(0);
                    log.info("ONNX Runtime using TensorRT execution provider");
                } catch (OrtException e) {
                    log.warn("TensorRT not available, falling back to CPU: {}", e.getMessage());
                }
            } else {
                log.info("ONNX Runtime using CPU execution provider");
            }

            try {
                session = environment.createSession(modelPath, options);
                log.info("ONNX model loaded successfully from: {}", modelPath);
                log.info("Model input info: {}", session.getInputInfo());
                log.info("Model output info: {}", session.getOutputInfo());
            } catch (OrtException e) {
                log.warn("Failed to load ONNX model from {}, using fallback mode: {}", modelPath, e.getMessage());
                session = null;
            }

        } catch (Exception e) {
            log.error("Failed to initialize ONNX Runtime: {}", e.getMessage());
            environment = null;
            session = null;
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (session != null) {
                session.close();
            }
            if (environment != null) {
                environment.close();
            }
            log.info("ONNX Runtime resources cleaned up");
        } catch (Exception e) {
            log.error("Error during ONNX Runtime cleanup: {}", e.getMessage());
        }
    }

    public float[] preprocessTimeSeries(List<PumpingUnitData> data) {
        if (data == null || data.isEmpty()) {
            return new float[INPUT_FEATURE_COUNT];
        }

        int dataSize = Math.min(data.size(), 100);
        List<PumpingUnitData> recentData = data.subList(Math.max(0, data.size() - dataSize), data.size());

        double[] fluidLevels = new double[dataSize];
        double[] currents = new double[dataSize];
        double[] pressures = new double[dataSize];
        double[] efficiencies = new double[dataSize];

        for (int i = 0; i < dataSize; i++) {
            PumpingUnitData d = recentData.get(i);
            fluidLevels[i] = d.getDynamicFluidLevel() != null ? d.getDynamicFluidLevel() : 0.0;
            currents[i] = d.getMotorCurrent() != null ? d.getMotorCurrent() : 0.0;
            pressures[i] = d.getCasingPressure() != null ? d.getCasingPressure() : 0.0;
            efficiencies[i] = d.getPumpEfficiency() != null ? d.getPumpEfficiency() : 0.0;
        }

        float[] features = new float[INPUT_FEATURE_COUNT];

        features[0] = (float) Arrays.stream(fluidLevels).average().orElse(0.0);
        features[1] = (float) Arrays.stream(currents).average().orElse(0.0);
        features[2] = (float) calculateTrend(fluidLevels);
        features[3] = (float) calculateTrend(currents);
        features[4] = (float) calculateStd(fluidLevels);
        features[5] = (float) calculateStd(currents);
        features[6] = (float) calculateRateOfChange(currents);
        features[7] = (float) calculateRateOfChange(efficiencies);

        return normalizeFeatures(features);
    }

    public Map<String, Double> inferFaultProbability(float[] input) {
        long startTime = System.nanoTime();
        Map<String, Double> result = new HashMap<>();

        if (session == null) {
            for (String faultType : FAULT_TYPES) {
                result.put(faultType, 0.0);
            }
            recordInference("fallback", System.nanoTime() - startTime);
            return result;
        }

        try (OrtSession s = session) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            long[] inputShape = new long[]{1, INPUT_FEATURE_COUNT};
            float[][] inputTensorData = new float[1][INPUT_FEATURE_COUNT];
            System.arraycopy(input, 0, inputTensorData[0], 0, INPUT_FEATURE_COUNT);

            try (OnnxTensor inputTensor = OnnxTensor.createTensor(environment, inputTensorData, inputShape)) {
                inputs.put("input", inputTensor);

                try (OrtSession.Result output = session.run(inputs)) {
                    float[][] outputData = (float[][]) output.get(0).getValue();

                    for (int i = 0; i < FAULT_TYPES.size() && i < outputData[0].length; i++) {
                        double probability = sigmoid(outputData[0][i]);
                        result.put(FAULT_TYPES.get(i), probability);
                    }

                    for (int i = result.size(); i < FAULT_TYPES.size(); i++) {
                        result.put(FAULT_TYPES.get(i), 0.0);
                    }
                }
            }

            long inferenceTime = System.nanoTime() - startTime;
            recordInference("onnx", inferenceTime);

            return result;

        } catch (OrtException e) {
            log.error("ONNX inference failed: {}", e.getMessage());
            for (String faultType : FAULT_TYPES) {
                result.put(faultType, 0.0);
            }
            recordInference("error", System.nanoTime() - startTime);
            return result;
        }
    }

    public Map<String, Object> getInferencePerformance() {
        Map<String, Object> performance = new LinkedHashMap<>();

        long totalCount = totalInferenceCount.get();
        long totalTimeNs = totalInferenceTimeNs.get();

        performance.put("totalInferenceCount", totalCount);
        performance.put("totalInferenceTimeMs", totalTimeNs / 1_000_000.0);
        performance.put("averageInferenceTimeMs", totalCount > 0 ? (totalTimeNs / (double) totalCount) / 1_000_000.0 : 0.0);

        Map<String, Object> byType = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : inferenceCountByType.entrySet()) {
            String type = entry.getKey();
            long count = entry.getValue();
            long timeNs = inferenceTimeByType.getOrDefault(type, 0L);

            Map<String, Object> typeStats = new LinkedHashMap<>();
            typeStats.put("count", count);
            typeStats.put("totalTimeMs", timeNs / 1_000_000.0);
            typeStats.put("avgTimeMs", count > 0 ? (timeNs / (double) count) / 1_000_000.0 : 0.0);
            byType.put(type, typeStats);
        }
        performance.put("inferenceByType", byType);

        performance.put("modelPath", modelPath);
        performance.put("executionProvider", executionProvider);
        performance.put("numThreads", numThreads);
        performance.put("modelLoaded", session != null);
        performance.put("inputFeatureCount", INPUT_FEATURE_COUNT);
        performance.put("outputClassCount", OUTPUT_CLASS_COUNT);
        performance.put("faultTypes", FAULT_TYPES);

        return performance;
    }

    public Map<String, Object> compareTraditionalVsOnnxInference() {
        Map<String, Object> comparison = new LinkedHashMap<>();

        List<PumpingUnitData> testData = generateTestData(100);
        float[] preprocessed = preprocessTimeSeries(testData);

        long traditionalStart = System.nanoTime();
        Map<String, Double> traditionalResult = traditionalInference(preprocessed);
        long traditionalTime = System.nanoTime() - traditionalStart;

        long onnxStart = System.nanoTime();
        Map<String, Double> onnxResult = inferFaultProbability(preprocessed);
        long onnxTime = System.nanoTime() - onnxStart;

        comparison.put("traditionalTimeMs", traditionalTime / 1_000_000.0);
        comparison.put("onnxTimeMs", onnxTime / 1_000_000.0);
        comparison.put("speedupRatio", traditionalTime / (double) Math.max(onnxTime, 1L));
        comparison.put("onnxFaster", traditionalTime > onnxTime ?
                String.format("%.2fx", traditionalTime / (double) Math.max(onnxTime, 1L)) : "1.00x");

        Map<String, Map<String, Double>> resultsComparison = new LinkedHashMap<>();
        for (String faultType : FAULT_TYPES) {
            Map<String, Double> values = new LinkedHashMap<>();
            values.put("traditional", traditionalResult.getOrDefault(faultType, 0.0));
            values.put("onnx", onnxResult.getOrDefault(faultType, 0.0));
            values.put("difference", Math.abs(
                    traditionalResult.getOrDefault(faultType, 0.0) -
                    onnxResult.getOrDefault(faultType, 0.0)));
            resultsComparison.put(faultType, values);
        }
        comparison.put("probabilityComparison", resultsComparison);

        return comparison;
    }

    private Map<String, Double> traditionalInference(float[] features) {
        Map<String, Double> result = new HashMap<>();

        double avgFluid = features[0];
        double avgCurrent = features[1];
        double fluidTrend = features[2];
        double currentTrend = features[3];
        double fluidStd = features[4];
        double currentStd = features[5];
        double currentROC = features[6];
        double efficiencyROC = features[7];

        double rodBreakProb = 0.0;
        if (currentROC < -0.2) rodBreakProb += 0.4;
        if (currentTrend < -0.1) rodBreakProb += 0.3;
        if (fluidTrend > 0.05) rodBreakProb += 0.2;
        if (avgCurrent < 0.5 * 50) rodBreakProb += 0.1;
        result.put("ROD_BREAK", Math.min(rodBreakProb, 0.99));

        double pumpLeakProb = 0.0;
        if (fluidTrend > 0.1) pumpLeakProb += 0.4;
        if (efficiencyROC < -0.05) pumpLeakProb += 0.3;
        if (currentTrend > 0.05) pumpLeakProb += 0.2;
        if (fluidStd > 20) pumpLeakProb += 0.1;
        result.put("PUMP_LEAK", Math.min(pumpLeakProb, 0.99));

        double gasLockProb = 0.0;
        if (currentTrend < -0.3) gasLockProb += 0.4;
        if (efficiencyROC < -0.1) gasLockProb += 0.3;
        if (fluidTrend > 0.05) gasLockProb += 0.2;
        if (currentStd > 0.3 * 30) gasLockProb += 0.1;
        result.put("GAS_LOCK", Math.min(gasLockProb, 0.99));

        double valveLeakProb = 0.0;
        if (efficiencyROC < -0.2) valveLeakProb += 0.4;
        if (fluidTrend > 0.03) valveLeakProb += 0.25;
        if (currentTrend > 0.03) valveLeakProb += 0.2;
        if (currentROC > 0.02) valveLeakProb += 0.15;
        result.put("VALVE_LEAK", Math.min(valveLeakProb, 0.99));

        return result;
    }

    private void recordInference(String type, long timeNs) {
        totalInferenceCount.incrementAndGet();
        totalInferenceTimeNs.addAndGet(timeNs);
        inferenceCountByType.merge(type, 1L, Long::sum);
        inferenceTimeByType.merge(type, timeNs, Long::sum);
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

    private double calculateStd(double[] data) {
        if (data.length < 2) return 0;
        double mean = Arrays.stream(data).average().orElse(0);
        double variance = Arrays.stream(data)
                .map(d -> (d - mean) * (d - mean))
                .average().orElse(0);
        return Math.sqrt(variance);
    }

    private double calculateRateOfChange(double[] data) {
        if (data.length < 2) return 0;
        double first = data[0];
        double last = data[data.length - 1];
        if (first == 0) return 0;
        return (last - first) / first;
    }

    private float[] normalizeFeatures(float[] features) {
        float[] normalized = new float[features.length];

        float[] means = {1000.0f, 30.0f, 0.0f, 0.0f, 50.0f, 5.0f, 0.0f, 0.0f};
        float[] scales = {500.0f, 20.0f, 1.0f, 1.0f, 100.0f, 10.0f, 1.0f, 1.0f};

        for (int i = 0; i < features.length; i++) {
            normalized[i] = (features[i] - means[i]) / scales[i];
            normalized[i] = Math.max(-1.0f, Math.min(1.0f, normalized[i]));
        }

        return normalized;
    }

    private double sigmoid(float x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private List<PumpingUnitData> generateTestData(int count) {
        List<PumpingUnitData> data = new ArrayList<>();
        Random random = new Random(42);

        for (int i = 0; i < count; i++) {
            PumpingUnitData d = new PumpingUnitData();
            d.setDynamicFluidLevel(800.0 + random.nextGaussian() * 50);
            d.setMotorCurrent(25.0 + random.nextGaussian() * 3);
            d.setCasingPressure(2.0 + random.nextGaussian() * 0.5);
            d.setPumpEfficiency(0.7 + random.nextGaussian() * 0.1);
            data.add(d);
        }

        return data;
    }

    public boolean isModelLoaded() {
        return session != null;
    }

    public List<String> getFaultTypes() {
        return FAULT_TYPES;
    }
}
