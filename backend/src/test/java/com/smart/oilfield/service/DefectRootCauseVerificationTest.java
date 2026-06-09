package com.smart.oilfield.service;

import com.smart.oilfield.config.AdvancedFeaturesProperties;
import com.smart.oilfield.entity.*;
import com.smart.oilfield.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("缺陷根因验证测试")
class DefectRootCauseVerificationTest {

    @Mock
    private WellConnectivityRepository connectivityRepository;
    @Mock
    private ProductionDataRepository productionDataRepository;
    @Mock
    private WaterInjectionDataRepository injectionDataRepository;
    @Mock
    private WellRepository wellRepository;
    @Mock
    private InjectionProfileRepository profileRepository;
    @Mock
    private SmartWaterControllerApiClient waterControllerClient;
    @Mock
    private EOREvaluationRepository evaluationRepository;
    @Mock
    private BlockDailySummaryRepository summaryRepository;
    @Mock
    private FaultPredictionRepository faultPredictionRepository;
    @Mock
    private PumpingUnitDataRepository pumpingUnitDataRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private WellConnectivityAnalyzer connectivityAnalyzer;
    @InjectMocks
    private InjectionProfileOptimizer profileOptimizer;
    @InjectMocks
    private EOREvaluationService eorEvaluationService;
    @InjectMocks
    private PumpingUnitFaultPredictor faultPredictor;

    private AdvancedFeaturesProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AdvancedFeaturesProperties();
        properties.getConnectivity().setEnableGraphModelScreening(true);
        properties.getConnectivity().setPartialCorrelationThreshold(0.3);
        properties.getConnectivity().setMaxConditioningVariables(3);
        properties.getConnectivity().setSpuriousEdgeThreshold(0.15);

        properties.getSmartWaterController().setEnableSmithPredictor(true);
        properties.getSmartWaterController().setFeedbackDelayHours(6);
        properties.getSmartWaterController().setSmithPredictorGain(0.8);
        properties.getSmartWaterController().setMaxOvershootCompensation(0.3);

        properties.getEor().setEnableHistoryMatching(true);
        properties.getEor().setHistoryMatchingWindowMonths(12);
        properties.getEor().setHistoryMatchingTolerance(0.05);
        properties.getEor().setMaxOptimizationIterations(100);

        properties.getFault().setEnableAdaptiveThreshold(true);
        properties.getFault().setEnableTransferLearning(true);
        properties.getFault().setWorkingConditionStabilityThreshold(0.3);
        properties.getFault().setAdaptiveThresholdSensitivity(0.5);
    }

    @Nested
    @DisplayName("连通性分析模块 - 偏相关系数和图模型筛选验证")
    class ConnectivityPartialCorrelationVerification {

        @Test
        @DisplayName("根因验证: 多井干扰场景下伪边识别")
        void testSpuriousEdgeDetectionInComplexInjection() throws Exception {
            Method calculatePartialCorrelation = WellConnectivityAnalyzer.class
                    .getDeclaredMethod("calculatePartialCorrelation", double[].class, double[].class, List.class);
            calculatePartialCorrelation.setAccessible(true);

            int n = 100;
            Random random = new Random(42);
            double[] injection1 = new double[n];
            double[] injection2 = new double[n];
            double[] production = new double[n];

            for (int i = 0; i < n; i++) {
                injection1[i] = 100 + random.nextGaussian() * 10;
                injection2[i] = 120 + random.nextGaussian() * 12;
                production[i] = 0.7 * injection1[i] + 0.0 * injection2[i] + random.nextGaussian() * 5;
            }

            Method calculateCorrelation = WellConnectivityAnalyzer.class
                    .getDeclaredMethod("calculatePearsonCorrelation", double[].class, double[].class);
            calculateCorrelation.setAccessible(true);
            double pearsonInj2Prod = (double) calculateCorrelation.invoke(connectivityAnalyzer, injection2, production);

            assertTrue(pearsonInj2Prod > 0.4, "未加控制时，干扰井与采油井可能表现出虚假相关性");

            List<double[]> controls = Collections.singletonList(injection1);
            double partialCorrInj2Prod = (double) calculatePartialCorrelation.invoke(
                    connectivityAnalyzer, injection2, production, controls);

            assertTrue(Math.abs(partialCorrInj2Prod) < 0.2,
                    "控制真实注水井后，干扰井的偏相关系数应显著降低");
            assertTrue(Math.abs(partialCorrInj2Prod) < Math.abs(pearsonInj2Prod),
                    "偏相关系数应小于皮尔逊相关系数，证明伪边被识别");
        }

        @Test
        @DisplayName("根因验证: 真实连通井对不被误过滤")
        void testTrueConnectivityNotFiltered() throws Exception {
            Method calculatePartialCorrelation = WellConnectivityAnalyzer.class
                    .getDeclaredMethod("calculatePartialCorrelation", double[].class, double[].class, List.class);
            calculatePartialCorrelation.setAccessible(true);

            int n = 100;
            Random random = new Random(123);
            double[] injection = new double[n];
            double[] otherInjection = new double[n];
            double[] production = new double[n];

            for (int i = 0; i < n; i++) {
                injection[i] = 100 + random.nextGaussian() * 10;
                otherInjection[i] = 80 + random.nextGaussian() * 8;
                production[i] = 0.6 * injection[i] + 0.2 * otherInjection[i] + random.nextGaussian() * 5;
            }

            Method calculateCorrelation = WellConnectivityAnalyzer.class
                    .getDeclaredMethod("calculatePearsonCorrelation", double[].class, double[].class);
            calculateCorrelation.setAccessible(true);
            double pearsonCorr = (double) calculateCorrelation.invoke(connectivityAnalyzer, injection, production);

            List<double[]> controls = Collections.singletonList(otherInjection);
            double partialCorr = (double) calculatePartialCorrelation.invoke(
                    connectivityAnalyzer, injection, production, controls);

            assertTrue(partialCorr > 0.3, "真实连通井对的偏相关系数应保持较高值");
            assertTrue(partialCorr > properties.getConnectivity().getPartialCorrelationThreshold(),
                    "真实连通应超过偏相关阈值");
        }

        @Test
        @DisplayName("根因验证: 相关矩阵求逆正确性")
        void testCorrelationMatrixInversion() throws Exception {
            Method invertMatrix = WellConnectivityAnalyzer.class
                    .getDeclaredMethod("invertMatrix", double[][].class);
            invertMatrix.setAccessible(true);

            double[][] matrix = {
                    {1.0, 0.5, 0.3},
                    {0.5, 1.0, 0.4},
                    {0.3, 0.4, 1.0}
            };

            double[][] inverted = (double[][]) invertMatrix.invoke(connectivityAnalyzer, (Object) matrix);

            assertNotNull(inverted, "矩阵求逆不应返回null");
            assertEquals(3, inverted.length);
            assertEquals(3, inverted[0].length);

            double[][] identity = multiplyMatrices(matrix, inverted);
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    if (i == j) {
                        assertTrue(Math.abs(identity[i][j] - 1.0) < 0.001,
                                "对角线元素应接近1，误差: " + Math.abs(identity[i][j] - 1.0));
                    } else {
                        assertTrue(Math.abs(identity[i][j]) < 0.001,
                                "非对角线元素应接近0，误差: " + Math.abs(identity[i][j]));
                    }
                }
            }
        }

        private double[][] multiplyMatrices(double[][] a, double[][] b) {
            int n = a.length;
            double[][] result = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    for (int k = 0; k < n; k++) {
                        result[i][j] += a[i][k] * b[k][j];
                    }
                }
            }
            return result;
        }
    }

    @Nested
    @DisplayName("注水剖面调整模块 - 史密斯预估器补偿验证")
    class SmithPredictorVerification {

        @Test
        @DisplayName("根因验证: 延迟场景下超调被抑制")
        void testOvershootMitigationWithDelay() throws Exception {
            Method applySmithPredictor = InjectionProfileOptimizer.class
                    .getDeclaredMethod("applySmithPredictorCompensation", List.class,
                            AdvancedFeaturesProperties.SmartWaterController.class);
            applySmithPredictor.setAccessible(true);

            List<InjectionProfile> profiles = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                InjectionProfile profile = new InjectionProfile();
                profile.setLayerNumber(i + 1);
                profile.setSuggestedVolume(100.0 + i * 20);
                profile.setActualVolume(80.0 + i * 15);
                profile.setProfileDate(LocalDate.now().minusDays(i));
                profiles.add(profile);
            }

            AdvancedFeaturesProperties.SmartWaterController config = properties.getSmartWaterController();
            config.setFeedbackDelayHours(6);
            config.setMaxOvershootCompensation(0.3);

            @SuppressWarnings("unchecked")
            List<InjectionProfile> compensated = (List<InjectionProfile>) applySmithPredictor.invoke(
                    profileOptimizer, profiles, config);

            assertNotNull(compensated, "补偿后的剖面列表不应为null");
            assertEquals(profiles.size(), compensated.size());

            for (InjectionProfile profile : compensated) {
                assertNotNull(profile.getDelayCompensatedVolume(), "应设置延迟补偿体积");
                assertTrue(profile.getOvershootMitigationApplied(), "应标记超调抑制已应用");
                assertEquals(6, profile.getFeedbackDelayHours());

                double originalSuggested = profile.getSuggestedVolume();
                double compensatedVolume = profile.getDelayCompensatedVolume();
                double maxAllowed = originalSuggested * (1 + config.getMaxOvershootCompensation());
                double minAllowed = originalSuggested * (1 - config.getMaxOvershootCompensation());

                assertTrue(compensatedVolume <= maxAllowed,
                        "补偿后体积不应超过最大超调限制，补偿值: " + compensatedVolume + ", 最大允许: " + maxAllowed);
                assertTrue(compensatedVolume >= minAllowed,
                        "补偿后体积不应低于最小限制");
            }
        }

        @Test
        @DisplayName("根因验证: 过程模型预测准确性")
        void testProcessModelPrediction() throws Exception {
            Method suggestProcessModel = InjectionProfileOptimizer.class
                    .getDeclaredMethod("suggestProcessModel", double.class, double.class,
                            double.class, int.class, double.class);
            suggestProcessModel.setAccessible(true);

            double currentVolume = 80.0;
            double targetVolume = 120.0;
            double processGain = 0.8;
            int delaySteps = 3;
            double gain = 0.8;

            double predicted = (double) suggestProcessModel.invoke(
                    profileOptimizer, currentVolume, targetVolume, processGain, delaySteps, gain);

            assertTrue(predicted > currentVolume, "预测值应大于当前值");
            assertTrue(predicted <= targetVolume * 1.1, "预测值不应过度超调");

            double steadyStateError = targetVolume - currentVolume;
            double expectedPrediction = currentVolume + gain * processGain * steadyStateError / (1 + delaySteps * 0.1);
            assertTrue(Math.abs(predicted - expectedPrediction) / expectedPrediction < 0.2,
                    "预测值应在合理范围内");
        }

        @Test
        @DisplayName("根因验证: 历史误差校正应用")
        void testModelErrorCorrection() throws Exception {
            Method loadHistoricalData = InjectionProfileOptimizer.class
                    .getDeclaredMethod("loadHistoricalPredictionData", String.class,
                            AdvancedFeaturesProperties.SmartWaterController.class);
            loadHistoricalData.setAccessible(true);

            String wellId = "INJ-001";
            List<InjectionProfile> mockProfiles = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                InjectionProfile p = new InjectionProfile();
                p.setWellId(wellId);
                p.setLayerNumber(1);
                p.setPredictedVolume(100.0 + i * 5);
                p.setActualVolume(95.0 + i * 4.5);
                p.setProfileDate(LocalDate.now().minusDays(i));
                mockProfiles.add(p);
            }

            when(profileRepository.findByWellIdAndDateRange(eq(wellId), any(), any()))
                    .thenReturn(mockProfiles);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) loadHistoricalData.invoke(
                    profileOptimizer, wellId, properties.getSmartWaterController());

            assertNotNull(result, "历史数据加载结果不应为null");
            assertTrue(result.containsKey("avgPredictionError"), "应包含平均预测误差");
            assertTrue(result.containsKey("errorTrend"), "应包含误差趋势");

            double avgError = (double) result.get("avgPredictionError");
            double expectedError = (100 + 22.5) - (95 + 20.25);
            assertTrue(Math.abs(avgError - expectedError) / Math.abs(expectedError) < 0.3,
                    "平均误差计算应接近实际误差");
        }
    }

    @Nested
    @DisplayName("三次采油方案模块 - 历史拟合和参数优化验证")
    class HistoryMatchingVerification {

        @Test
        @DisplayName("根因验证: 历史数据加载和趋势分析")
        void testHistoricalDataTrendAnalysis() throws Exception {
            Method fitLinearRegression = EOREvaluationService.class
                    .getDeclaredMethod("fitLinearRegression", double[].class, double[].class);
            fitLinearRegression.setAccessible(true);

            int n = 30;
            double[] x = new double[n];
            double[] y = new double[n];
            double expectedSlope = -0.5;
            double expectedIntercept = 100.0;
            Random random = new Random(42);

            for (int i = 0; i < n; i++) {
                x[i] = i;
                y[i] = expectedIntercept + expectedSlope * i + random.nextGaussian() * 2;
            }

            double[] result = (double[]) fitLinearRegression.invoke(eorEvaluationService, x, y);

            assertNotNull(result, "线性回归结果不应为null");
            assertEquals(2, result.length);

            double slope = result[0];
            double intercept = result[1];

            assertTrue(Math.abs(slope - expectedSlope) < 0.1,
                    "斜率估计应接近真实值，估计值: " + slope);
            assertTrue(Math.abs(intercept - expectedIntercept) < 2,
                    "截距估计应接近真实值，估计值: " + intercept);
        }

        @Test
        @DisplayName("根因验证: 参数优化收敛性")
        void testParameterOptimizationConvergence() throws Exception {
            String blockName = "BLOCK-A";
            LocalDate evalDate = LocalDate.now();

            List<BlockDailySummary> summaries = new ArrayList<>();
            double baseProduction = 100.0;
            double baseWaterCut = 70.0;
            Random random = new Random(42);

            for (int i = 0; i < 60; i++) {
                BlockDailySummary summary = new BlockDailySummary();
                summary.setBlockName(blockName);
                summary.setSummaryDate(evalDate.minusDays(60 - i));
                double decline = -0.1 * i / 30.0;
                summary.setTotalOilProduction((baseProduction * 1000) * (1 + decline) + random.nextGaussian() * 5000);
                summary.setAverageWaterCut(Math.min(95, baseWaterCut + 0.05 * i + random.nextGaussian() * 1));
                summary.setTotalWaterInjection(500000.0);
                summaries.add(summary);
            }

            when(summaryRepository.findByBlockNameOrderBySummaryDateDesc(eq(blockName)))
                    .thenReturn(summaries);

            AdvancedFeaturesProperties.Eor config = properties.getEor();
            config.setHistoryMatchingTolerance(0.1);
            config.setMaxOptimizationIterations(100);

            Method performHistoryMatching = EOREvaluationService.class
                    .getDeclaredMethod("performHistoryMatching", String.class, LocalDate.class,
                            List.class, AdvancedFeaturesProperties.Eor.class);
            performHistoryMatching.setAccessible(true);

            List<String> eorTypes = Collections.singletonList("POLYMER_FLOODING");

            @SuppressWarnings("unchecked")
            Map<String, Object> results = (Map<String, Object>) performHistoryMatching.invoke(
                    eorEvaluationService, blockName, evalDate, eorTypes, config);

            assertNotNull(results, "历史拟合结果不应为null");
            assertTrue(results.containsKey("POLYMER_FLOODING"), "应包含聚合物驱的拟合结果");

            Object matchResultObj = results.get("POLYMER_FLOODING");
            assertNotNull(matchResultObj, "拟合结果对象不应为null");

            Method isMatchSuccessful = matchResultObj.getClass().getMethod("isMatchSuccessful");
            boolean success = (boolean) isMatchSuccessful.invoke(matchResultObj);

            assertTrue(success, "历史拟合应成功收敛");

            Method getMatchError = matchResultObj.getClass().getMethod("getMatchError");
            double matchError = (double) getMatchError.invoke(matchResultObj);

            assertTrue(matchError <= config.getMaximumAllowableDeviation(),
                    "拟合误差应小于最大允许偏差，实际误差: " + matchError);
        }

        @Test
        @DisplayName("根因验证: 校准参数应用效果")
        void testCalibratedParameterApplication() throws Exception {
            Class<?> matchResultClass = Class.forName(
                    "com.smart.oilfield.service.EOREvaluationService$HistoricalMatchResult");
            Class<?> scenarioParamClass = Class.forName(
                    "com.smart.oilfield.dto.EOREvaluationRequest$ScenarioParameter");

            Method evaluateScenario = EOREvaluationService.class.getDeclaredMethod(
                    "evaluateScenario", String.class, String.class, LocalDate.class, int.class,
                    BigDecimal.class, double.class, Map.class, scenarioParamClass,
                    matchResultClass, AdvancedFeaturesProperties.Eor.class);
            evaluateScenario.setAccessible(true);

            Object calibratedResult = matchResultClass.getConstructor(
                    boolean.class, double.class, double.class, double.class,
                    int.class, double.class, String.class
            ).newInstance(true, 0.25, 12.0, 0.03, 50, 0.15, null);

            Map<String, Object> blockData = new HashMap<>();
            blockData.put("currentOilProduction", 100.0);
            blockData.put("currentWaterCut", 80.0);
            blockData.put("remainingOilSaturation", 0.35);
            blockData.put("permeability", 200.0);
            blockData.put("porosity", 0.25);
            blockData.put("reservoirTemperature", 80.0);
            blockData.put("reservoirPressure", 25.0);

            EOREvaluation calibratedEval = (EOREvaluation) evaluateScenario.invoke(
                    eorEvaluationService, "BLOCK-A", "POLYMER_FLOODING", LocalDate.now(), 24,
                    BigDecimal.valueOf(70.0), 0.08, blockData, null,
                    calibratedResult, properties.getEor());

            Object defaultResult = matchResultClass.getConstructor(
                    boolean.class, double.class, double.class, double.class,
                    int.class, double.class, String.class
            ).newInstance(false, 0.0, 0.0, 0.0, 0, 0.0, "No data");

            EOREvaluation defaultEval = (EOREvaluation) evaluateScenario.invoke(
                    eorEvaluationService, "BLOCK-A", "POLYMER_FLOODING", LocalDate.now(), 24,
                    BigDecimal.valueOf(70.0), 0.08, blockData, null,
                    defaultResult, properties.getEor());

            assertNotNull(calibratedEval);
            assertNotNull(defaultEval);

            assertEquals(0.25, calibratedEval.getCalibratedOilIncrementFactor(), 0.001);
            assertEquals(12.0, calibratedEval.getCalibratedWaterCutReduction(), 0.001);
            assertTrue(calibratedEval.getIsHistoryMatched());
            assertFalse(defaultEval.getIsHistoryMatched());

            assertNotEquals(calibratedEval.getPredictedOilIncrement(),
                    defaultEval.getPredictedOilIncrement(),
                    "使用校准参数后，预测结果应不同");
        }
    }

    @Nested
    @DisplayName("抽油机故障模块 - 工况自适应阈值和迁移学习验证")
    class AdaptiveThresholdVerification {

        @Test
        @DisplayName("根因验证: 工况不稳定时阈值自动提高")
        void testThresholdIncreasesUnderUnstableConditions() throws Exception {
            Method calculateWorkingConditionStability = PumpingUnitFaultPredictor.class
                    .getDeclaredMethod("calculateWorkingConditionStability", List.class,
                            AdvancedFeaturesProperties.Fault.class);
            calculateWorkingConditionStability.setAccessible(true);

            Method calculateAdaptiveThreshold = PumpingUnitFaultPredictor.class
                    .getDeclaredMethod("calculateAdaptiveThreshold", double.class, double.class,
                            Map.class, AdvancedFeaturesProperties.Fault.class);
            calculateAdaptiveThreshold.setAccessible(true);

            List<PumpingUnitData> stableData = generateStableData(50, 30.0, 1000.0, 5.0);
            double stableStability = (double) calculateWorkingConditionStability.invoke(
                    faultPredictor, stableData, properties.getFault());

            List<PumpingUnitData> unstableData = generateUnstableData(50, 30.0, 1000.0, 5.0);
            double unstableStability = (double) calculateWorkingConditionStability.invoke(
                    faultPredictor, unstableData, properties.getFault());

            assertTrue(stableStability > 0.7, "稳定工况的稳定性应较高: " + stableStability);
            assertTrue(unstableStability < 0.5, "不稳定工况的稳定性应较低: " + unstableStability);
            assertTrue(unstableStability < stableStability,
                    "不稳定工况的稳定性应低于稳定工况");

            double baseThreshold = 0.6;
            Map<String, Object> analysisData = new HashMap<>();
            analysisData.put("currentDeviation", 0.5);
            analysisData.put("fluidLevelDeviation", 0.3);
            analysisData.put("dataQuality", 0.9);

            Class<?> thresholdResultClass = Class.forName(
                    "com.smart.oilfield.service.PumpingUnitFaultPredictor$AdaptiveThresholdResult");

            Object stableResult = calculateAdaptiveThreshold.invoke(faultPredictor,
                    baseThreshold, stableStability, analysisData, properties.getFault());
            Object unstableResult = calculateAdaptiveThreshold.invoke(faultPredictor,
                    baseThreshold, unstableStability, analysisData, properties.getFault());

            Method getAdjustedThreshold = thresholdResultClass.getMethod("adjustedThreshold");
            double stableAdjusted = (double) getAdjustedThreshold.invoke(stableResult);
            double unstableAdjusted = (double) getAdjustedThreshold.invoke(unstableResult);

            assertTrue(unstableAdjusted > stableAdjusted,
                    "不稳定工况下阈值应提高，稳定: " + stableAdjusted + ", 不稳定: " + unstableAdjusted);
            assertTrue(unstableAdjusted > baseThreshold,
                    "不稳定工况下阈值应高于基准值: " + unstableAdjusted);
            assertTrue(stableAdjusted <= baseThreshold,
                    "稳定工况下阈值应不高于基准值: " + stableAdjusted);
        }

        @Test
        @DisplayName("根因验证: 迁移学习知识融合")
        void testTransferLearningKnowledgeFusion() throws Exception {
            Method combineTransferKnowledge = PumpingUnitFaultPredictor.class
                    .getDeclaredMethod("combineTransferKnowledge", double.class, double.class,
                            double.class, double.class);
            combineTransferKnowledge.setAccessible(true);

            double originalProb = 0.7;
            double transferredProb = 0.3;
            double dataWeight = 0.8;
            double configWeight = 0.3;

            double combined = (double) combineTransferKnowledge.invoke(
                    faultPredictor, originalProb, transferredProb, dataWeight, configWeight);

            double effectiveWeight = configWeight * dataWeight;
            double expected = originalProb * (1 - effectiveWeight) + transferredProb * effectiveWeight;

            assertEquals(expected, combined, 0.001, "知识融合计算应正确");
            assertTrue(combined < originalProb, "低概率迁移知识应降低整体概率");
            assertTrue(combined > transferredProb, "融合结果应在两者之间");

            double highTransferred = 0.9;
            double combinedHigh = (double) combineTransferKnowledge.invoke(
                    faultPredictor, originalProb, highTransferred, dataWeight, configWeight);

            assertTrue(combinedHigh > originalProb,
                    "高概率迁移知识应提高整体概率: " + combinedHigh + " > " + originalProb);
        }

        @Test
        @DisplayName("根因验证: 工况稳定性计算")
        void testWorkingConditionStabilityCalculation() throws Exception {
            Method calculateCV = PumpingUnitFaultPredictor.class
                    .getDeclaredMethod("calculateCoefficientOfVariation", double[].class);
            calculateCV.setAccessible(true);

            double[] stableData = {10.0, 10.1, 9.9, 10.0, 10.1, 9.9, 10.0};
            double[] unstableData = {10.0, 15.0, 5.0, 12.0, 8.0, 14.0, 6.0};

            double stableCV = (double) calculateCV.invoke(faultPredictor, stableData);
            double unstableCV = (double) calculateCV.invoke(faultPredictor, unstableData);

            assertTrue(stableCV < 0.1, "稳定数据变异系数应很小: " + stableCV);
            assertTrue(unstableCV > 0.3, "不稳定数据变异系数应较大: " + unstableCV);

            Method calculateAbsoluteChanges = PumpingUnitFaultPredictor.class
                    .getDeclaredMethod("calculateAbsoluteChanges", double[].class);
            calculateAbsoluteChanges.setAccessible(true);

            double[] changes = (double[]) calculateAbsoluteChanges.invoke(faultPredictor, unstableData);
            assertEquals(6, changes.length);
            assertEquals(5.0, changes[0], 0.001, "变化量计算应正确");
            assertEquals(10.0, changes[1], 0.001, "变化量计算应正确");
        }

        @Test
        @DisplayName("根因验证: 相似井匹配")
        void testWellSimilarityCalculation() throws Exception {
            Method calculateWellSimilarity = PumpingUnitFaultPredictor.class
                    .getDeclaredMethod("calculateWellSimilarity", Well.class, Well.class);
            calculateWellSimilarity.setAccessible(true);

            Well well1 = new Well();
            well1.setArtificialLiftType("ROD_PUMP");
            well1.setTotalDepth(2000.0);
            well1.setProductionZone("ZONE-A");

            Well well2 = new Well();
            well2.setArtificialLiftType("ROD_PUMP");
            well2.setTotalDepth(2100.0);
            well2.setProductionZone("ZONE-A");

            Well well3 = new Well();
            well3.setArtificialLiftType("ESP");
            well3.setTotalDepth(3000.0);
            well3.setProductionZone("ZONE-B");

            double similaritySameType = (double) calculateWellSimilarity.invoke(faultPredictor, well1, well2);
            double similarityDifferent = (double) calculateWellSimilarity.invoke(faultPredictor, well1, well3);

            assertTrue(similaritySameType > 0.7, "同类型井相似度应较高: " + similaritySameType);
            assertTrue(similarityDifferent < 0.5, "不同类型井相似度应较低: " + similarityDifferent);
            assertTrue(similaritySameType > similarityDifferent,
                    "同类型井相似度应高于不同类型井");
        }

        private List<PumpingUnitData> generateStableData(int count, double baseCurrent,
                                                          double baseFluidLevel, double basePressure) {
            List<PumpingUnitData> data = new ArrayList<>();
            Random random = new Random(42);
            LocalDateTime time = LocalDateTime.now();

            for (int i = 0; i < count; i++) {
                PumpingUnitData d = new PumpingUnitData();
                d.setRecordTime(time.minusHours(count - i));
                d.setMotorCurrent(baseCurrent + random.nextGaussian() * 0.5);
                d.setDynamicFluidLevel(baseFluidLevel + random.nextGaussian() * 10);
                d.setCasingPressure(basePressure + random.nextGaussian() * 0.2);
                d.setPumpEfficiency(80.0 + random.nextGaussian() * 2);
                data.add(d);
            }
            return data;
        }

        private List<PumpingUnitData> generateUnstableData(int count, double baseCurrent,
                                                            double baseFluidLevel, double basePressure) {
            List<PumpingUnitData> data = new ArrayList<>();
            Random random = new Random(123);
            LocalDateTime time = LocalDateTime.now();

            for (int i = 0; i < count; i++) {
                PumpingUnitData d = new PumpingUnitData();
                d.setRecordTime(time.minusHours(count - i));
                double regime = random.nextInt(3);
                if (regime == 0) {
                    d.setMotorCurrent(baseCurrent * 1.3 + random.nextGaussian() * 2);
                    d.setDynamicFluidLevel(baseFluidLevel * 0.7 + random.nextGaussian() * 30);
                } else if (regime == 1) {
                    d.setMotorCurrent(baseCurrent * 0.7 + random.nextGaussian() * 2);
                    d.setDynamicFluidLevel(baseFluidLevel * 1.3 + random.nextGaussian() * 30);
                } else {
                    d.setMotorCurrent(baseCurrent + random.nextGaussian() * 3);
                    d.setDynamicFluidLevel(baseFluidLevel + random.nextGaussian() * 30);
                }
                d.setCasingPressure(basePressure + random.nextGaussian() * 1.0);
                d.setPumpEfficiency(60.0 + random.nextGaussian() * 15);
                data.add(d);
            }
            return data;
        }
    }

    @Test
    @DisplayName("综合验证: 所有缺陷修复功能启用状态")
    void testAllDefectFixesEnabled() {
        assertTrue(properties.getConnectivity().isEnableGraphModelScreening(),
                "连通性分析图模型筛选应启用");
        assertTrue(properties.getSmartWaterController().isEnableSmithPredictor(),
                "史密斯预估器应启用");
        assertTrue(properties.getEor().isEnableHistoryMatching(),
                "历史拟合应启用");
        assertTrue(properties.getFault().isEnableAdaptiveThreshold(),
                "自适应阈值应启用");
        assertTrue(properties.getFault().isEnableTransferLearning(),
                "迁移学习应启用");
    }
}
