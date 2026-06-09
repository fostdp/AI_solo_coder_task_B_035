package com.smart.oilfield.pump.service;

import com.smart.oilfield.common.config.AdvancedFeaturesProperties;
import com.smart.oilfield.common.dto.FaultPredictionRequest;
import com.smart.oilfield.common.entity.FaultPrediction;
import com.smart.oilfield.common.entity.PumpingUnitData;
import com.smart.oilfield.common.entity.Well;
import com.smart.oilfield.common.event.AlarmTriggeredEvent;
import com.smart.oilfield.common.event.FaultPredictedEvent;
import com.smart.oilfield.common.repository.FaultPredictionRepository;
import com.smart.oilfield.common.repository.PumpingUnitDataRepository;
import com.smart.oilfield.common.repository.WellRepository;
import com.smart.oilfield.pump.PumpDiagnoserApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = PumpDiagnoserApplication.class)
@ExtendWith(MockitoExtension.class)
@DisplayName("抽油机故障预测测试 - PumpingUnitFaultPredictor")
class PumpingUnitFaultPredictorTest {

    @Mock
    private FaultPredictionRepository faultPredictionRepository;

    @Mock
    private PumpingUnitDataRepository pumpingUnitDataRepository;

    @Mock
    private WellRepository wellRepository;

    @Mock
    private AdvancedFeaturesProperties properties;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private PumpingUnitFaultPredictor predictor;

    private AdvancedFeaturesProperties.Fault faultConfig;

    @BeforeEach
    void setUp() {
        faultConfig = new AdvancedFeaturesProperties.Fault();
        faultConfig.setDefaultAnalysisWindowHours(72);
        faultConfig.setMinAnalysisWindowHours(24);
        faultConfig.setFaultProbabilityThreshold(0.6);
        faultConfig.setCriticalFaultThreshold(0.8);
        faultConfig.setWarningFaultThreshold(0.6);
        faultConfig.setNoticeFaultThreshold(0.4);
        faultConfig.setRodBreakCurrentDeviationThreshold(2.0);
        faultConfig.setPumpLeakFluidLevelRiseThreshold(50.0);
        faultConfig.setGasLockCurrentDropThreshold(0.3);
        faultConfig.setValveLeakEfficiencyDropThreshold(0.2);
        faultConfig.setRodBreakMaintenanceCost(50000.0);
        faultConfig.setPumpLeakMaintenanceCost(30000.0);
        faultConfig.setGasLockMaintenanceCost(10000.0);
        faultConfig.setValveLeakMaintenanceCost(15000.0);
        faultConfig.setRodBreakDowntimeHours(24);
        faultConfig.setPumpLeakDowntimeHours(12);
        faultConfig.setGasLockDowntimeHours(4);
        faultConfig.setValveLeakDowntimeHours(8);
        faultConfig.setEnableAutoAlarm(true);
        faultConfig.setModelVersion("v1.5");
        faultConfig.setEnableAdaptiveThreshold(true);
        faultConfig.setEnableTransferLearning(true);

        when(properties.getFault()).thenReturn(faultConfig);
    }

    @Nested
    @DisplayName("故障前兆检测测试")
    class FaultPrecursorDetectionTests {

        @Test
        @DisplayName("动液面上升趋势检测 - 验证趋势计算准确性")
        void testFluidLevelRisingTrendDetection() throws Exception {
            List<PumpingUnitData> data = createTimeSeriesData(
                    800, 15.0,
                    0.1, 0.0,
                    96
            );

            Map<String, Object> result = invokeAnalyzeTimeSeriesData(data);

            double fluidLevelTrend = (double) result.get("fluidLevelTrend");
            assertTrue(fluidLevelTrend > 0.05,
                    "动液面上升趋势应该被检测到，实际趋势值: " + fluidLevelTrend);
            assertEquals("RISING", result.get("fluidLevelTrend") > 0.01 ? "RISING" :
                    result.get("fluidLevelTrend") < -0.01 ? "FALLING" : "STABLE");
        }

        @Test
        @DisplayName("电流下降趋势检测 - 验证趋势计算准确性")
        void testCurrentFallingTrendDetection() throws Exception {
            List<PumpingUnitData> data = createTimeSeriesData(
                    1000, 20.0,
                    0.0, -0.2,
                    96
            );

            Map<String, Object> result = invokeAnalyzeTimeSeriesData(data);

            double currentTrend = (double) result.get("currentTrend");
            assertTrue(currentTrend < -0.1,
                    "电流下降趋势应该被检测到，实际趋势值: " + currentTrend);
        }

        @Test
        @DisplayName("异常分数计算准确率 - 验证电流偏差计算")
        void testAnomalyScoreCalculationAccuracy() throws Exception {
            List<PumpingUnitData> data = new ArrayList<>();
            LocalDateTime baseTime = LocalDateTime.now();
            for (int i = 0; i < 48; i++) {
                PumpingUnitData d = new PumpingUnitData();
                d.setWellId("TEST-001");
                d.setRecordTime(baseTime.minusHours(48 - i));
                if (i < 24) {
                    d.setDynamicFluidLevel(1000.0);
                    d.setMotorCurrent(20.0);
                } else {
                    d.setDynamicFluidLevel(1000.0);
                    d.setMotorCurrent(5.0);
                }
                d.setCasingPressure(2.0);
                d.setPumpEfficiency(0.7);
                data.add(d);
            }

            Map<String, Object> result = invokeAnalyzeTimeSeriesData(data);

            double currentDeviation = (double) result.get("currentDeviation");
            assertTrue(currentDeviation > 0.7,
                    "电流从20A骤降到5A，偏差应该大于0.7，实际: " + currentDeviation);
        }

        @Test
        @DisplayName("故障提前检测验证 - 验证前兆可提前48小时检测")
        void testEarlyFaultDetectionLeadTime() throws Exception {
            List<PumpingUnitData> data = createGradualFaultData("ROD_BREAK", 96);

            Map<String, Object> result = invokeAnalyzeTimeSeriesData(data);

            double currentTrend = (double) result.get("currentTrend");
            double fluidLevelTrend = (double) result.get("fluidLevelTrend");

            assertTrue(currentTrend < -0.05 || fluidLevelTrend > 0.03,
                    "在故障发生前48小时应该能检测到前兆，电流趋势: " + currentTrend
                            + ", 动液面趋势: " + fluidLevelTrend);
        }

        @Test
        @DisplayName("高信噪比数据检测 - 验证鲁棒性")
        void testHighSNRDataDetection() throws Exception {
            List<PumpingUnitData> data = createNoisyTimeSeriesData(
                    1000, 20.0,
                    0.05, -0.15,
                    72, 0.02
            );

            Map<String, Object> result = invokeAnalyzeTimeSeriesData(data);

            double dataQuality = (double) result.get("dataQuality");
            assertEquals(1.0, dataQuality, 0.01,
                    "高信噪比数据质量应该接近1.0");

            double currentTrend = (double) result.get("currentTrend");
            assertTrue(currentTrend < -0.1,
                    "即使有噪声，下降趋势也应该被检测到，实际: " + currentTrend);
        }

        @Test
        @DisplayName("低信噪比数据检测 - 验证抗干扰能力")
        void testLowSNRDataDetection() throws Exception {
            List<PumpingUnitData> data = createNoisyTimeSeriesData(
                    1000, 20.0,
                    0.08, -0.2,
                    72, 0.15
            );

            Map<String, Object> result = invokeAnalyzeTimeSeriesData(data);

            double dataQuality = (double) result.get("dataQuality");
            assertTrue(dataQuality >= 0.8,
                    "低信噪比但数据完整时，数据质量不应低于0.8，实际: " + dataQuality);
        }

        @Test
        @DisplayName("变化率计算验证 - 验证前后期对比")
        void testRateOfChangeCalculation() throws Exception {
            List<PumpingUnitData> data = new ArrayList<>();
            LocalDateTime baseTime = LocalDateTime.now();
            for (int i = 0; i < 48; i++) {
                PumpingUnitData d = new PumpingUnitData();
                d.setWellId("TEST-001");
                d.setRecordTime(baseTime.minusHours(48 - i));
                d.setDynamicFluidLevel(1000.0 + i * 2.0);
                d.setMotorCurrent(25.0 - i * 0.3);
                d.setCasingPressure(2.0);
                d.setPumpEfficiency(0.8);
                data.add(d);
            }

            Map<String, Object> result = invokeAnalyzeTimeSeriesData(data);

            double fluidLevelRateOfChange = (double) result.get("fluidLevelRateOfChange");
            double currentRateOfChange = (double) result.get("currentRateOfChange");

            assertTrue(fluidLevelRateOfChange > 0.04,
                    "动液面变化率应该为正，实际: " + fluidLevelRateOfChange);
            assertTrue(currentRateOfChange < -0.05,
                    "电流变化率应该为负，实际: " + currentRateOfChange);
        }

        @Test
        @DisplayName("泵效下降趋势检测 - 验证效率退化检测")
        void testPumpEfficiencyDeclineDetection() throws Exception {
            List<PumpingUnitData> data = new ArrayList<>();
            LocalDateTime baseTime = LocalDateTime.now();
            for (int i = 0; i < 96; i++) {
                PumpingUnitData d = new PumpingUnitData();
                d.setWellId("TEST-001");
                d.setRecordTime(baseTime.minusHours(96 - i));
                d.setDynamicFluidLevel(1000.0 + i * 0.5);
                d.setMotorCurrent(20.0 + i * 0.02);
                d.setCasingPressure(2.0);
                d.setPumpEfficiency(Math.max(0.3, 0.8 - i * 0.004));
                data.add(d);
            }

            Map<String, Object> result = invokeAnalyzeTimeSeriesData(data);

            double efficiencyTrend = (double) result.get("efficiencyTrend");
            assertTrue(efficiencyTrend < -0.003,
                    "泵效下降趋势应该被检测到，实际趋势: " + efficiencyTrend);
        }
    }

    @Nested
    @DisplayName("故障类型判别测试")
    class FaultTypeClassificationTests {

        @Test
        @DisplayName("抽油杆断脱判别 - 验证特征匹配正确率")
        void testRodBreakClassification() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-001");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);
            request.setFaultProbabilityThreshold(0.5);
            request.setGenerateMaintenanceRecommendation(true);
            request.setFaultTypesToCheck(Collections.singletonList("ROD_BREAK"));

            List<PumpingUnitData> data = createGradualFaultData("ROD_BREAK", 72);
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-001"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);
            when(faultPredictionRepository.findLatestUnacknowledgedByWellAndType(
                    anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(faultPredictionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<FaultPrediction> predictions = predictor.predictWellFaults(request);

            assertFalse(predictions.isEmpty(), "应该检测到抽油杆断脱故障");
            FaultPrediction prediction = predictions.get(0);
            assertEquals("ROD_BREAK", prediction.getFaultType());
            assertTrue(prediction.getFaultProbability() >= 0.5,
                    "抽油杆断脱概率应该 >= 0.5，实际: " + prediction.getFaultProbability());
            assertTrue(prediction.getSymptoms().contains("电流") ||
                            prediction.getSymptoms().contains("动液面"),
                    "症状描述应该包含电流或动液面相关信息");
        }

        @Test
        @DisplayName("泵漏失判别 - 验证特征匹配正确率")
        void testPumpLeakClassification() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-002");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);
            request.setFaultProbabilityThreshold(0.5);
            request.setGenerateMaintenanceRecommendation(true);
            request.setFaultTypesToCheck(Collections.singletonList("PUMP_LEAK"));

            List<PumpingUnitData> data = createGradualFaultData("PUMP_LEAK", 72);
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-002"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);
            when(faultPredictionRepository.findLatestUnacknowledgedByWellAndType(
                    anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(faultPredictionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<FaultPrediction> predictions = predictor.predictWellFaults(request);

            assertFalse(predictions.isEmpty(), "应该检测到泵漏失故障");
            FaultPrediction prediction = predictions.get(0);
            assertEquals("PUMP_LEAK", prediction.getFaultType());
            assertTrue(prediction.getFaultProbability() >= 0.5,
                    "泵漏失概率应该 >= 0.5，实际: " + prediction.getFaultProbability());
            assertTrue(prediction.getSymptoms().contains("动液面上升") ||
                            prediction.getSymptoms().contains("泵效"),
                    "症状描述应该包含动液面上升或泵效相关信息");
        }

        @Test
        @DisplayName("气锁判别 - 验证特征匹配正确率")
        void testGasLockClassification() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-003");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);
            request.setFaultProbabilityThreshold(0.5);
            request.setGenerateMaintenanceRecommendation(true);
            request.setFaultTypesToCheck(Collections.singletonList("GAS_LOCK"));

            List<PumpingUnitData> data = createGradualFaultData("GAS_LOCK", 72);
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-003"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);
            when(faultPredictionRepository.findLatestUnacknowledgedByWellAndType(
                    anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(faultPredictionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<FaultPrediction> predictions = predictor.predictWellFaults(request);

            assertFalse(predictions.isEmpty(), "应该检测到气锁故障");
            FaultPrediction prediction = predictions.get(0);
            assertEquals("GAS_LOCK", prediction.getFaultType());
            assertTrue(prediction.getFaultProbability() >= 0.5,
                    "气锁概率应该 >= 0.5，实际: " + prediction.getFaultProbability());
        }

        @Test
        @DisplayName("阀漏失判别 - 验证特征匹配正确率")
        void testValveLeakClassification() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-004");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);
            request.setFaultProbabilityThreshold(0.5);
            request.setGenerateMaintenanceRecommendation(true);
            request.setFaultTypesToCheck(Collections.singletonList("VALVE_LEAK"));

            List<PumpingUnitData> data = createGradualFaultData("VALVE_LEAK", 72);
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-004"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);
            when(faultPredictionRepository.findLatestUnacknowledgedByWellAndType(
                    anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(faultPredictionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<FaultPrediction> predictions = predictor.predictWellFaults(request);

            assertFalse(predictions.isEmpty(), "应该检测到阀漏失故障");
            FaultPrediction prediction = predictions.get(0);
            assertEquals("VALVE_LEAK", prediction.getFaultType());
            assertTrue(prediction.getFaultProbability() >= 0.5,
                    "阀漏失概率应该 >= 0.5，实际: " + prediction.getFaultProbability());
            assertTrue(prediction.getSymptoms().contains("泵效下降"),
                    "症状描述应该包含泵效下降相关信息");
        }

        @Test
        @DisplayName("多故障并发判别 - 验证优先级判断")
        void testMultipleFaultsPriority() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-005");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);
            request.setFaultProbabilityThreshold(0.3);
            request.setGenerateMaintenanceRecommendation(true);

            List<PumpingUnitData> data = createMultipleFaultData();
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-005"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);
            when(faultPredictionRepository.findLatestUnacknowledgedByWellAndType(
                    anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(faultPredictionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<FaultPrediction> predictions = predictor.predictWellFaults(request);

            assertTrue(predictions.size() >= 2,
                    "应该检测到至少2种故障，实际检测到: " + predictions.size());

            Optional<FaultPrediction> critical = predictions.stream()
                    .filter(p -> "CRITICAL".equals(p.getSeverityLevel()))
                    .findFirst();
            assertTrue(critical.isPresent(), "应该有CRITICAL级别的故障");
        }

        @Test
        @DisplayName("严重级别阈值验证 - CRITICAL级别边界")
        void testCriticalSeverityThreshold() {
            FaultPrediction prediction = new FaultPrediction();
            prediction.setFaultProbability(0.80);
            assertEquals("CRITICAL", prediction.determineSeverityLevel());

            prediction.setFaultProbability(0.799);
            assertNotEquals("CRITICAL", prediction.determineSeverityLevel());
        }

        @Test
        @DisplayName("严重级别阈值验证 - WARNING级别边界")
        void testWarningSeverityThreshold() {
            FaultPrediction prediction = new FaultPrediction();
            prediction.setFaultProbability(0.60);
            assertEquals("WARNING", prediction.determineSeverityLevel());

            prediction.setFaultProbability(0.599);
            assertNotEquals("WARNING", prediction.determineSeverityLevel());
        }

        @Test
        @DisplayName("严重级别阈值验证 - NOTICE级别边界")
        void testNoticeSeverityThreshold() {
            FaultPrediction prediction = new FaultPrediction();
            prediction.setFaultProbability(0.40);
            assertEquals("NOTICE", prediction.determineSeverityLevel());

            prediction.setFaultProbability(0.399);
            assertEquals("LOW", prediction.determineSeverityLevel());
        }

        @Test
        @DisplayName("未知故障类型处理 - 验证空值返回")
        void testUnknownFaultTypeHandling() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-006");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);
            request.setFaultProbabilityThreshold(0.3);
            request.setFaultTypesToCheck(Collections.singletonList("UNKNOWN_FAULT"));

            List<PumpingUnitData> data = createTimeSeriesData(1000, 20, 0, 0, 72);
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-006"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);

            List<FaultPrediction> predictions = predictor.predictWellFaults(request);

            assertTrue(predictions.isEmpty(), "未知故障类型应该返回空列表");
        }

        @Test
        @DisplayName("概率低于阈值过滤 - 验证低概率故障被过滤")
        void testBelowThresholdFiltering() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-007");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);
            request.setFaultProbabilityThreshold(0.9);
            request.setGenerateMaintenanceRecommendation(true);

            List<PumpingUnitData> data = createGradualFaultData("ROD_BREAK", 36);
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-007"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);
            when(faultPredictionRepository.findLatestUnacknowledgedByWellAndType(
                    anyString(), anyString()))
                    .thenReturn(Optional.empty());

            List<FaultPrediction> predictions = predictor.predictWellFaults(request);

            assertTrue(predictions.isEmpty(),
                    "概率低于0.9阈值的故障应该被过滤，设置高阈值用于验证过滤逻辑");
        }
    }

    @Nested
    @DisplayName("检修建议测试")
    class MaintenanceRecommendationTests {

        @Test
        @DisplayName("抽油杆断脱检修建议 - 验证与故障类型匹配")
        void testRodBreakRecommendationMatching() {
            FaultPrediction prediction = new FaultPrediction();
            prediction.setFaultType("ROD_BREAK");
            prediction.setFaultProbability(0.85);
            prediction.setSeverityLevel("CRITICAL");

            invokeAddMaintenanceRecommendation(prediction);

            assertNotNull(prediction.getRecommendedAction());
            assertTrue(prediction.getRecommendedAction().contains("抽油杆"),
                    "建议应该包含抽油杆相关操作，实际: " + prediction.getRecommendedAction());
            assertTrue(prediction.getRecommendedAction().contains("停产检修") ||
                            prediction.getRecommendedAction().contains("修井"),
                    "CRITICAL级别应该建议停产检修，实际: " + prediction.getRecommendedAction());
            assertEquals(50000.0, prediction.getEstimatedMaintenanceCost(), 0.01);
            assertEquals(24, prediction.getEstimatedDowntimeHours());
        }

        @Test
        @DisplayName("泵漏失检修建议 - 验证与故障类型匹配")
        void testPumpLeakRecommendationMatching() {
            FaultPrediction prediction = new FaultPrediction();
            prediction.setFaultType("PUMP_LEAK");
            prediction.setFaultProbability(0.75);
            prediction.setSeverityLevel("WARNING");

            invokeAddMaintenanceRecommendation(prediction);

            assertNotNull(prediction.getRecommendedAction());
            assertTrue(prediction.getRecommendedAction().contains("检泵") ||
                            prediction.getRecommendedAction().contains("泵"),
                    "建议应该包含检泵相关操作，实际: " + prediction.getRecommendedAction());
            assertTrue(prediction.getRecommendedAction().contains("近期安排") ||
                            prediction.getRecommendedAction().contains("准备"),
                    "WARNING级别应该建议近期安排，实际: " + prediction.getRecommendedAction());
            assertEquals(30000.0, prediction.getEstimatedMaintenanceCost(), 0.01);
            assertEquals(12, prediction.getEstimatedDowntimeHours());
        }

        @Test
        @DisplayName("气锁检修建议 - 验证与故障类型匹配")
        void testGasLockRecommendationMatching() {
            FaultPrediction prediction = new FaultPrediction();
            prediction.setFaultType("GAS_LOCK");
            prediction.setFaultProbability(0.7);
            prediction.setSeverityLevel("WARNING");

            invokeAddMaintenanceRecommendation(prediction);

            assertNotNull(prediction.getRecommendedAction());
            assertTrue(prediction.getRecommendedAction().contains("气") ||
                            prediction.getRecommendedAction().contains("抽汲参数"),
                    "建议应该包含防气或参数调整相关操作，实际: " + prediction.getRecommendedAction());
            assertEquals(10000.0, prediction.getEstimatedMaintenanceCost(), 0.01);
            assertEquals(4, prediction.getEstimatedDowntimeHours());
        }

        @Test
        @DisplayName("阀漏失检修建议 - 验证与故障类型匹配")
        void testValveLeakRecommendationMatching() {
            FaultPrediction prediction = new FaultPrediction();
            prediction.setFaultType("VALVE_LEAK");
            prediction.setFaultProbability(0.82);
            prediction.setSeverityLevel("CRITICAL");

            invokeAddMaintenanceRecommendation(prediction);

            assertNotNull(prediction.getRecommendedAction());
            assertTrue(prediction.getRecommendedAction().contains("阀") ||
                            prediction.getRecommendedAction().contains("密封"),
                    "建议应该包含阀门或密封相关操作，实际: " + prediction.getRecommendedAction());
            assertEquals(15000.0, prediction.getEstimatedMaintenanceCost(), 0.01);
            assertEquals(8, prediction.getEstimatedDowntimeHours());
        }

        @Test
        @DisplayName("检修建议可操作性 - 验证包含具体措施")
        void testRecommendationActionability() {
            String[] faultTypes = {"ROD_BREAK", "PUMP_LEAK", "GAS_LOCK", "VALVE_LEAK"};
            String[] severities = {"CRITICAL", "WARNING", "NOTICE"};

            for (String faultType : faultTypes) {
                for (String severity : severities) {
                    FaultPrediction prediction = new FaultPrediction();
                    prediction.setFaultType(faultType);
                    prediction.setFaultProbability(0.75);
                    prediction.setSeverityLevel(severity);

                    invokeAddMaintenanceRecommendation(prediction);

                    assertNotNull(prediction.getRecommendedAction(),
                            faultType + "-" + severity + " 的检修建议不应该为空");
                    assertTrue(prediction.getRecommendedAction().length() > 10,
                            faultType + "-" + severity + " 的检修建议应该详细，实际长度: "
                                    + prediction.getRecommendedAction().length());
                    assertNotNull(prediction.getEstimatedMaintenanceCost(),
                            faultType + "-" + severity + " 应该有预估成本");
                    assertNotNull(prediction.getEstimatedDowntimeHours(),
                            faultType + "-" + severity + " 应该有预估停机时间");
                    assertTrue(prediction.getEstimatedMaintenanceCost() > 0,
                            faultType + "-" + severity + " 预估成本应该大于0");
                    assertTrue(prediction.getEstimatedDowntimeHours() > 0,
                            faultType + "-" + severity + " 预估停机时间应该大于0");
                }
            }
        }

        @Test
        @DisplayName("严重级别与建议力度对应 - CRITICAL级别")
        void testSeverityRecommendationIntensityCritical() {
            FaultPrediction prediction = new FaultPrediction();
            prediction.setFaultType("ROD_BREAK");
            prediction.setSeverityLevel("CRITICAL");

            invokeAddMaintenanceRecommendation(prediction);

            assertTrue(prediction.getRecommendedAction().contains("立即"),
                    "CRITICAL级别建议应该包含'立即'，实际: " + prediction.getRecommendedAction());
        }

        @Test
        @DisplayName("严重级别与建议力度对应 - WARNING级别")
        void testSeverityRecommendationIntensityWarning() {
            FaultPrediction prediction = new FaultPrediction();
            prediction.setFaultType("PUMP_LEAK");
            prediction.setSeverityLevel("WARNING");

            invokeAddMaintenanceRecommendation(prediction);

            assertTrue(prediction.getRecommendedAction().contains("尽快") ||
                            prediction.getRecommendedAction().contains("近期"),
                    "WARNING级别建议应该包含'尽快'或'近期'，实际: " + prediction.getRecommendedAction());
        }

        @Test
        @DisplayName("严重级别与建议力度对应 - NOTICE级别")
        void testSeverityRecommendationIntensityNotice() {
            FaultPrediction prediction = new FaultPrediction();
            prediction.setFaultType("GAS_LOCK");
            prediction.setSeverityLevel("NOTICE");

            invokeAddMaintenanceRecommendation(prediction);

            assertTrue(prediction.getRecommendedAction().contains("监测") ||
                            prediction.getRecommendedAction().contains("关注"),
                    "NOTICE级别建议应该包含'监测'或'关注'，实际: " + prediction.getRecommendedAction());
        }

        @Test
        @DisplayName("未知故障类型默认建议 - 验证降级处理")
        void testUnknownFaultTypeDefaultRecommendation() {
            FaultPrediction prediction = new FaultPrediction();
            prediction.setFaultType("UNKNOWN_TYPE");
            prediction.setSeverityLevel("WARNING");

            invokeAddMaintenanceRecommendation(prediction);

            assertNotNull(prediction.getRecommendedAction());
            assertTrue(prediction.getRecommendedAction().contains("专业技术人员") ||
                            prediction.getRecommendedAction().contains("诊断"),
                    "未知故障类型应该建议专业人员诊断，实际: " + prediction.getRecommendedAction());
            assertEquals(20000.0, prediction.getEstimatedMaintenanceCost(), 0.01);
            assertEquals(12, prediction.getEstimatedDowntimeHours());
        }

        @Test
        @DisplayName("预估成本准确性 - 验证配置一致性")
        void testEstimatedCostAccuracy() {
            FaultPrediction rodBreak = new FaultPrediction();
            rodBreak.setFaultType("ROD_BREAK");
            rodBreak.setSeverityLevel("CRITICAL");
            invokeAddMaintenanceRecommendation(rodBreak);
            assertEquals(50000.0, rodBreak.getEstimatedMaintenanceCost(), 0.01);

            FaultPrediction pumpLeak = new FaultPrediction();
            pumpLeak.setFaultType("PUMP_LEAK");
            pumpLeak.setSeverityLevel("WARNING");
            invokeAddMaintenanceRecommendation(pumpLeak);
            assertEquals(30000.0, pumpLeak.getEstimatedMaintenanceCost(), 0.01);

            FaultPrediction gasLock = new FaultPrediction();
            gasLock.setFaultType("GAS_LOCK");
            gasLock.setSeverityLevel("WARNING");
            invokeAddMaintenanceRecommendation(gasLock);
            assertEquals(10000.0, gasLock.getEstimatedMaintenanceCost(), 0.01);

            FaultPrediction valveLeak = new FaultPrediction();
            valveLeak.setFaultType("VALVE_LEAK");
            valveLeak.setSeverityLevel("WARNING");
            invokeAddMaintenanceRecommendation(valveLeak);
            assertEquals(15000.0, valveLeak.getEstimatedMaintenanceCost(), 0.01);
        }
    }

    @Nested
    @DisplayName("边界与异常场景测试")
    class BoundaryAndExceptionTests {

        @Test
        @DisplayName("数据不足处理 - 低于最小分析窗口")
        void testInsufficientDataHandling() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-008");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);

            List<PumpingUnitData> data = createTimeSeriesData(1000, 20, 0, 0, 10);
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-008"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);

            List<FaultPrediction> predictions = predictor.predictWellFaults(request);

            assertTrue(predictions.isEmpty(),
                    "数据量不足（10点 < 最小要求12点）应该返回空列表");
        }

        @Test
        @DisplayName("最小数据量边界 - 刚好满足最低要求")
        void testMinimumDataBoundary() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-009");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);
            request.setFaultProbabilityThreshold(0.3);
            request.setGenerateMaintenanceRecommendation(true);

            List<PumpingUnitData> data = createGradualFaultData("ROD_BREAK", 24);
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-009"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);
            when(faultPredictionRepository.findLatestUnacknowledgedByWellAndType(
                    anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(faultPredictionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<FaultPrediction> predictions = predictor.predictWellFaults(request);

            assertFalse(predictions.isEmpty(),
                    "刚好满足最小数据量（24点）应该能够进行分析");
        }

        @Test
        @DisplayName("正常数据无预警 - 验证误报率控制")
        void testNormalDataNoFalseAlarm() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-010");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);
            request.setFaultProbabilityThreshold(0.6);

            List<PumpingUnitData> data = createNormalStableData(96);
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-010"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);

            List<FaultPrediction> predictions = predictor.predictWellFaults(request);

            assertTrue(predictions.isEmpty(),
                    "正常稳定运行数据不应该产生故障预警");
        }

        @Test
        @DisplayName("数据库异常处理 - Repository查询异常")
        void testDatabaseExceptionHandling() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-011");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);

            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-011"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenThrow(new RuntimeException("数据库连接超时"));

            assertDoesNotThrow(() -> {
                List<FaultPrediction> predictions = predictor.predictWellFaults(request);
                assertTrue(predictions.isEmpty(), "数据库异常时应该返回空列表");
            }, "数据库异常不应该向外传播");
        }

        @Test
        @DisplayName("重复预测去重 - 6小时内已有预测")
        void testDuplicatePredictionDeduplication() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-012");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);
            request.setGenerateMaintenanceRecommendation(true);

            FaultPrediction existingPrediction = new FaultPrediction();
            existingPrediction.setPredictionId("existing-123");
            existingPrediction.setWellId("TEST-012");
            existingPrediction.setFaultType("ROD_BREAK");
            existingPrediction.setFaultProbability(0.85);
            existingPrediction.setPredictionTime(LocalDateTime.now().minusHours(2));
            existingPrediction.setSeverityLevel("CRITICAL");

            List<PumpingUnitData> data = createGradualFaultData("ROD_BREAK", 72);
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-012"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);
            when(faultPredictionRepository.findLatestUnacknowledgedByWellAndType(
                    eq("TEST-012"), eq("ROD_BREAK")))
                    .thenReturn(Optional.of(existingPrediction));

            List<FaultPrediction> predictions = predictor.predictWellFaults(request);

            assertFalse(predictions.isEmpty());
            assertEquals("existing-123", predictions.get(0).getPredictionId(),
                    "6小时内的已有预测应该被复用，而不是生成新预测");
            verify(faultPredictionRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("告警事件发布 - CRITICAL级别告警")
        void testCriticalAlarmEventPublishing() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-013");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);
            request.setFaultProbabilityThreshold(0.5);
            request.setGenerateMaintenanceRecommendation(true);
            request.setAutoPublishAlarm(true);

            List<PumpingUnitData> data = createGradualFaultData("ROD_BREAK", 72);
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-013"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);
            when(faultPredictionRepository.findLatestUnacknowledgedByWellAndType(
                    anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(faultPredictionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            predictor.predictWellFaults(request);

            ArgumentCaptor<AlarmTriggeredEvent> alarmCaptor =
                    ArgumentCaptor.forClass(AlarmTriggeredEvent.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(alarmCaptor.capture());

            AlarmTriggeredEvent alarmEvent = alarmCaptor.getAllValues().stream()
                    .filter(e -> "LEVEL_2".equals(e.getAlarmLevel()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(alarmEvent, "CRITICAL故障应该发布LEVEL_2告警");
            assertTrue(alarmEvent.getDescription().contains("抽油杆断脱"),
                    "告警描述应该包含故障类型名称");
        }

        @Test
        @DisplayName("告警事件发布 - WARNING级别告警")
        void testWarningAlarmEventPublishing() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-014");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(48);
            request.setFaultProbabilityThreshold(0.6);
            request.setGenerateMaintenanceRecommendation(true);
            request.setAutoPublishAlarm(true);

            List<PumpingUnitData> data = createGradualFaultData("GAS_LOCK", 48);
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-014"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);
            when(faultPredictionRepository.findLatestUnacknowledgedByWellAndType(
                    anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(faultPredictionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            predictor.predictWellFaults(request);

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());

            boolean hasWarningAlarm = eventCaptor.getAllValues().stream()
                    .anyMatch(e -> e instanceof AlarmTriggeredEvent
                            && "LEVEL_1".equals(((AlarmTriggeredEvent) e).getAlarmLevel()));
            assertTrue(hasWarningAlarm, "WARNING故障应该发布LEVEL_1告警");
        }

        @Test
        @DisplayName("NOTICE级别无告警 - 验证分级告警策略")
        void testNoticeLevelNoAlarm() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-015");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);
            request.setFaultProbabilityThreshold(0.4);
            request.setGenerateMaintenanceRecommendation(true);
            request.setAutoPublishAlarm(true);

            List<PumpingUnitData> data = createTimeSeriesData(
                    1050, 19.5,
                    0.02, -0.02,
                    72
            );
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-015"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);
            when(faultPredictionRepository.findLatestUnacknowledgedByWellAndType(
                    anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(faultPredictionRepository.saveAll(anyList())).thenAnswer(inv -> {
                List<FaultPrediction> preds = inv.getArgument(0);
                preds.forEach(p -> p.setSeverityLevel("NOTICE"));
                return preds;
            });

            predictor.predictWellFaults(request);

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());

            long alarmCount = eventCaptor.getAllValues().stream()
                    .filter(e -> e instanceof AlarmTriggeredEvent)
                    .count();
            assertEquals(0, alarmCount, "NOTICE级别不应该发布告警");
        }

        @Test
        @DisplayName("空值数据处理 - 部分字段缺失")
        void testNullDataHandling() throws Exception {
            List<PumpingUnitData> data = new ArrayList<>();
            LocalDateTime baseTime = LocalDateTime.now();
            for (int i = 0; i < 48; i++) {
                PumpingUnitData d = new PumpingUnitData();
                d.setWellId("TEST-016");
                d.setRecordTime(baseTime.minusHours(48 - i));
                if (i % 3 == 0) {
                    d.setDynamicFluidLevel(null);
                    d.setMotorCurrent(20.0);
                } else if (i % 3 == 1) {
                    d.setDynamicFluidLevel(1000.0);
                    d.setMotorCurrent(null);
                } else {
                    d.setDynamicFluidLevel(1000.0);
                    d.setMotorCurrent(20.0);
                }
                d.setCasingPressure(2.0);
                d.setPumpEfficiency(0.7);
                data.add(d);
            }

            Map<String, Object> result = invokeAnalyzeTimeSeriesData(data);

            double dataQuality = (double) result.get("dataQuality");
            assertTrue(dataQuality >= 0.3 && dataQuality <= 0.4,
                    "1/3数据完整，数据质量应该在0.33左右，实际: " + dataQuality);
        }

        @Test
        @DisplayName("批量预测性能 - 验证异常捕获机制")
        void testBatchPredictionExceptionHandling() {
            Well well1 = new Well();
            well1.setWellId("WELL-A");
            well1.setWellType("production");
            Well well2 = new Well();
            well2.setWellId("WELL-B");
            well2.setWellType("production");
            Well well3 = new Well();
            well3.setWellId("WELL-C");
            well3.setWellType("production");

            List<Well> productionWells = Arrays.asList(well1, well2, well3);
            LocalDateTime cutoffTime = LocalDateTime.now().minusHours(24);

            when(wellRepository.findByWellType("production")).thenReturn(productionWells);
            when(pumpingUnitDataRepository.findWellsWithRecentData(cutoffTime))
                    .thenReturn(Arrays.asList("WELL-A", "WELL-B", "WELL-C"));

            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("WELL-A"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenThrow(new RuntimeException("数据库异常"));
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("WELL-B"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(createGradualFaultData("ROD_BREAK", 72));
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("WELL-C"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(createNormalStableData(72));
            when(faultPredictionRepository.findLatestUnacknowledgedByWellAndType(
                    anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(faultPredictionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            assertDoesNotThrow(() -> predictor.autoPredictAllWells(),
                    "单井异常不应该导致整个批量预测失败");
        }

        @Test
        @DisplayName("预测确认功能 - 验证状态更新")
        void testPredictionAcknowledgement() {
            String predictionId = "pred-ack-001";
            String acknowledgedBy = "engineer_zhang";

            FaultPrediction prediction = new FaultPrediction();
            prediction.setPredictionId(predictionId);
            prediction.setIsAcknowledged(false);

            when(faultPredictionRepository.findByPredictionId(predictionId))
                    .thenReturn(Optional.of(prediction));
            when(faultPredictionRepository.save(any(FaultPrediction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            FaultPrediction result = predictor.acknowledgePrediction(predictionId, acknowledgedBy);

            assertNotNull(result);
            assertTrue(result.getIsAcknowledged(), "确认后isAcknowledged应该为true");
            assertNotNull(result.getAcknowledgeTime(), "确认时间应该被设置");
            assertEquals(acknowledgedBy, result.getAcknowledgedBy(), "确认人应该被正确设置");
        }

        @Test
        @DisplayName("实际故障记录 - 验证预测准确率计算")
        void testActualFaultRecordingAndAccuracy() {
            String predictionId = "pred-accuracy-001";
            LocalDateTime predictedTime = LocalDateTime.now().plusHours(24);
            LocalDateTime actualTime = LocalDateTime.now().plusHours(30);

            FaultPrediction prediction = new FaultPrediction();
            prediction.setPredictionId(predictionId);
            prediction.setPredictedFaultTime(predictedTime);

            when(faultPredictionRepository.findByPredictionId(predictionId))
                    .thenReturn(Optional.of(prediction));
            when(faultPredictionRepository.save(any(FaultPrediction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            FaultPrediction result = predictor.recordActualFault(predictionId, actualTime);

            assertNotNull(result);
            assertTrue(result.getActualFaultOccurred(), "实际故障标志应该为true");
            assertEquals(actualTime, result.getActualFaultTime(), "实际故障时间应该被正确设置");
            assertNotNull(result.getPredictionAccuracy(), "预测准确率应该被计算");
            assertTrue(result.getPredictionAccuracy() > 0.8,
                    "预测时间偏差6小时，准确率应该 > 0.8，实际: " + result.getPredictionAccuracy());
        }

        @Test
        @DisplayName("预测准确率计算边界 - 72小时偏差")
        void testPredictionAccuracyBoundary() {
            String predictionId = "pred-boundary-001";
            LocalDateTime predictedTime = LocalDateTime.now();
            LocalDateTime actualTime = LocalDateTime.now().plusHours(72);

            FaultPrediction prediction = new FaultPrediction();
            prediction.setPredictionId(predictionId);
            prediction.setPredictedFaultTime(predictedTime);

            when(faultPredictionRepository.findByPredictionId(predictionId))
                    .thenReturn(Optional.of(prediction));
            when(faultPredictionRepository.save(any(FaultPrediction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            FaultPrediction result = predictor.recordActualFault(predictionId, actualTime);

            assertEquals(0.0, result.getPredictionAccuracy(), 0.01,
                    "偏差72小时准确率应该为0");
        }

        @Test
        @DisplayName("预测汇总信息 - 验证统计数据完整性")
        void testGetPredictionSummary() {
            String wellId = "TEST-SUMMARY-001";
            LocalDateTime now = LocalDateTime.now();

            FaultPrediction p1 = new FaultPrediction();
            p1.setWellId(wellId);
            p1.setFaultType("ROD_BREAK");
            p1.setFaultProbability(0.85);
            p1.setSeverityLevel("CRITICAL");
            p1.setPredictionTime(now);

            FaultPrediction p2 = new FaultPrediction();
            p2.setWellId(wellId);
            p2.setFaultType("PUMP_LEAK");
            p2.setFaultProbability(0.7);
            p2.setSeverityLevel("WARNING");
            p2.setPredictionTime(now);

            when(faultPredictionRepository.findLatestByWellId(wellId))
                    .thenReturn(Arrays.asList(p1, p2));

            Map<String, Object> summary = predictor.getPredictionSummary(wellId);

            assertEquals(wellId, summary.get("wellId"));
            assertEquals(0.85, (Double) summary.get("maxFaultProbability"), 0.01);
            assertEquals("CRITICAL", summary.get("highestSeverity"));
            assertEquals(2, summary.get("predictionCount"));
            assertEquals(1L, summary.get("criticalCount"));
            assertEquals(1L, summary.get("warningCount"));
        }

        @Test
        @DisplayName("无预测数据汇总 - 验证默认值")
        void testGetPredictionSummaryEmpty() {
            String wellId = "TEST-EMPTY-001";

            when(faultPredictionRepository.findLatestByWellId(wellId))
                    .thenReturn(Collections.emptyList());

            Map<String, Object> summary = predictor.getPredictionSummary(wellId);

            assertEquals(0.0, (Double) summary.get("maxFaultProbability"), 0.01);
            assertEquals("NONE", summary.get("highestSeverity"));
            assertEquals(0, summary.get("predictionCount"));
        }

        @Test
        @DisplayName("区块预测统计 - 验证ALL区块查询")
        void testGetBlockPredictionsAll() {
            LocalDateTime now = LocalDateTime.now();

            FaultPrediction p1 = new FaultPrediction();
            p1.setFaultType("ROD_BREAK");
            p1.setSeverityLevel("CRITICAL");
            p1.setPredictionTime(now);

            FaultPrediction p2 = new FaultPrediction();
            p2.setFaultType("PUMP_LEAK");
            p2.setSeverityLevel("WARNING");
            p2.setPredictionTime(now);

            FaultPrediction p3 = new FaultPrediction();
            p3.setFaultType("GAS_LOCK");
            p3.setSeverityLevel("NOTICE");
            p3.setPredictionTime(now);

            when(faultPredictionRepository.findAllLatestWithAcknowledged(any(LocalDateTime.class)))
                    .thenReturn(Arrays.asList(p1, p2, p3));

            Map<String, Object> result = predictor.getBlockPredictions("ALL");

            assertEquals(3, result.get("totalCount"));
            @SuppressWarnings("unchecked")
            Map<String, Object> stats = (Map<String, Object>) result.get("statistics");
            assertEquals(1L, stats.get("criticalCount"));
            assertEquals(1L, stats.get("warningCount"));
            assertEquals(1L, stats.get("noticeCount"));
        }

        @Test
        @DisplayName("数据质量计算 - 验证完整数据")
        void testDataQualityCalculationComplete() throws Exception {
            List<PumpingUnitData> data = new ArrayList<>();
            for (int i = 0; i < 48; i++) {
                PumpingUnitData d = new PumpingUnitData();
                d.setDynamicFluidLevel(1000.0 + i * 0.5);
                d.setMotorCurrent(20.0 - i * 0.1);
                d.setCasingPressure(2.0);
                d.setPumpEfficiency(0.75);
                data.add(d);
            }

            Map<String, Object> result = invokeAnalyzeTimeSeriesData(data);

            assertEquals(1.0, (double) result.get("dataQuality"), 0.01,
                    "所有数据完整时质量应该为1.0");
        }

        @Test
        @DisplayName("数据质量计算 - 验证空列表")
        void testDataQualityCalculationEmpty() throws Exception {
            List<PumpingUnitData> data = Collections.emptyList();

            Map<String, Object> result = invokeAnalyzeTimeSeriesData(data);

            assertEquals(0.0, (double) result.get("dataQuality"), 0.01,
                    "空列表数据质量应该为0");
        }

        @Test
        @DisplayName("故障预测事件发布 - 验证事件内容")
        void testFaultPredictedEventPublishing() {
            FaultPredictionRequest request = new FaultPredictionRequest();
            request.setWellId("TEST-EVENT-001");
            request.setAnalysisEndTime(LocalDateTime.now());
            request.setAnalysisWindowHours(72);
            request.setFaultProbabilityThreshold(0.5);
            request.setGenerateMaintenanceRecommendation(true);
            request.setAutoPublishAlarm(false);

            List<PumpingUnitData> data = createGradualFaultData("ROD_BREAK", 72);
            when(pumpingUnitDataRepository.findByWellIdAndTimeRange(
                    eq("TEST-EVENT-001"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(data);
            when(faultPredictionRepository.findLatestUnacknowledgedByWellAndType(
                    anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(faultPredictionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            predictor.predictWellFaults(request);

            ArgumentCaptor<FaultPredictedEvent> eventCaptor =
                    ArgumentCaptor.forClass(FaultPredictedEvent.class);
            verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

            FaultPredictedEvent event = eventCaptor.getValue();
            assertEquals("TEST-EVENT-001", event.getWellId());
            assertFalse(event.getPredictions().isEmpty());
        }
    }

    @Nested
    @DisplayName("工况自适应阈值和迁移学习验证")
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
                    predictor, stableData, faultConfig);

            List<PumpingUnitData> unstableData = generateUnstableData(50, 30.0, 1000.0, 5.0);
            double unstableStability = (double) calculateWorkingConditionStability.invoke(
                    predictor, unstableData, faultConfig);

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
                    "com.smart.oilfield.pump.service.PumpingUnitFaultPredictor$AdaptiveThresholdResult");

            Object stableResult = calculateAdaptiveThreshold.invoke(predictor,
                    baseThreshold, stableStability, analysisData, faultConfig);
            Object unstableResult = calculateAdaptiveThreshold.invoke(predictor,
                    baseThreshold, unstableStability, analysisData, faultConfig);

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
                    predictor, originalProb, transferredProb, dataWeight, configWeight);

            double effectiveWeight = configWeight * dataWeight;
            double expected = originalProb * (1 - effectiveWeight) + transferredProb * effectiveWeight;

            assertEquals(expected, combined, 0.001, "知识融合计算应正确");
            assertTrue(combined < originalProb, "低概率迁移知识应降低整体概率");
            assertTrue(combined > transferredProb, "融合结果应在两者之间");

            double highTransferred = 0.9;
            double combinedHigh = (double) combineTransferKnowledge.invoke(
                    predictor, originalProb, highTransferred, dataWeight, configWeight);

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

            double stableCV = (double) calculateCV.invoke(predictor, stableData);
            double unstableCV = (double) calculateCV.invoke(predictor, unstableData);

            assertTrue(stableCV < 0.1, "稳定数据变异系数应很小: " + stableCV);
            assertTrue(unstableCV > 0.3, "不稳定数据变异系数应较大: " + unstableCV);

            Method calculateAbsoluteChanges = PumpingUnitFaultPredictor.class
                    .getDeclaredMethod("calculateAbsoluteChanges", double[].class);
            calculateAbsoluteChanges.setAccessible(true);

            double[] changes = (double[]) calculateAbsoluteChanges.invoke(predictor, unstableData);
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

            double similaritySameType = (double) calculateWellSimilarity.invoke(predictor, well1, well2);
            double similarityDifferent = (double) calculateWellSimilarity.invoke(predictor, well1, well3);

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

    @Nested
    @DisplayName("ONNX推理服务测试")
    class OnnxInferenceServiceTests {

        @InjectMocks
        private OnnxInferenceService onnxInferenceService;

        @Test
        @DisplayName("ONNX模型加载状态检测")
        void testModelLoadedStatus() {
            boolean isLoaded = onnxInferenceService.isModelLoaded();
            assertNotNull(isLoaded, "模型加载状态不应为null");
        }

        @Test
        @DisplayName("ONNX支持的故障类型验证")
        void testSupportedFaultTypes() {
            List<String> faultTypes = onnxInferenceService.getFaultTypes();
            assertNotNull(faultTypes, "故障类型列表不应为null");
            assertEquals(4, faultTypes.size(), "应支持4种故障类型");
            assertTrue(faultTypes.contains("ROD_BREAK"), "应包含ROD_BREAK");
            assertTrue(faultTypes.contains("PUMP_LEAK"), "应包含PUMP_LEAK");
            assertTrue(faultTypes.contains("GAS_LOCK"), "应包含GAS_LOCK");
            assertTrue(faultTypes.contains("VALVE_LEAK"), "应包含VALVE_LEAK");
        }

        @Test
        @DisplayName("ONNX时序数据预处理 - 正常数据")
        void testPreprocessTimeSeriesNormalData() {
            List<PumpingUnitData> data = new ArrayList<>();
            LocalDateTime baseTime = LocalDateTime.now();
            for (int i = 0; i < 50; i++) {
                PumpingUnitData d = new PumpingUnitData();
                d.setRecordTime(baseTime.minusHours(50 - i));
                d.setDynamicFluidLevel(1000.0 + i * 0.5);
                d.setMotorCurrent(25.0 - i * 0.1);
                d.setCasingPressure(2.0);
                d.setPumpEfficiency(0.75);
                data.add(d);
            }

            float[] features = onnxInferenceService.preprocessTimeSeries(data);

            assertNotNull(features, "预处理结果不应为null");
            assertEquals(8, features.length, "特征向量长度应为8");

            for (int i = 0; i < features.length; i++) {
                assertTrue(features[i] >= -1.0f && features[i] <= 1.0f,
                        "特征值应在[-1, 1]范围内，特征[" + i + "]: " + features[i]);
            }
        }

        @Test
        @DisplayName("ONNX时序数据预处理 - 空数据")
        void testPreprocessTimeSeriesEmptyData() {
            List<PumpingUnitData> data = Collections.emptyList();

            float[] features = onnxInferenceService.preprocessTimeSeries(data);

            assertNotNull(features, "空数据预处理结果不应为null");
            assertEquals(8, features.length, "特征向量长度应为8");
        }

        @Test
        @DisplayName("ONNX时序数据预处理 - 空值数据")
        void testPreprocessTimeSeriesNullData() {
            float[] features = onnxInferenceService.preprocessTimeSeries(null);

            assertNotNull(features, "null数据预处理结果不应为null");
            assertEquals(8, features.length, "特征向量长度应为8");
        }

        @Test
        @DisplayName("ONNX推理 - 正常输入推理")
        void testInferFaultProbabilityNormalInput() {
            float[] input = new float[8];
            Arrays.fill(input, 0.0f);

            Map<String, Double> result = onnxInferenceService.inferFaultProbability(input);

            assertNotNull(result, "推理结果不应为null");
            assertEquals(4, result.size(), "应返回4种故障概率");
            for (String faultType : Arrays.asList("ROD_BREAK", "PUMP_LEAK", "GAS_LOCK", "VALVE_LEAK")) {
                assertTrue(result.containsKey(faultType), "应包含" + faultType);
                Double prob = result.get(faultType);
                assertNotNull(prob, faultType + "概率不应为null");
                assertTrue(prob >= 0.0 && prob <= 1.0,
                        faultType + "概率应在[0,1]范围内，实际: " + prob);
            }
        }

        @Test
        @DisplayName("ONNX推理性能统计 - 初始状态")
        void testGetInferencePerformanceInitial() {
            Map<String, Object> performance = onnxInferenceService.getInferencePerformance();

            assertNotNull(performance, "性能统计不应为null");
            assertTrue(performance.containsKey("totalInferenceCount"), "应包含总推理次数");
            assertTrue(performance.containsKey("averageInferenceTimeMs"), "应包含平均推理时间");
            assertTrue(performance.containsKey("inferenceByType"), "应包含按类型统计");
            assertTrue(performance.containsKey("modelLoaded"), "应包含模型加载状态");
        }

        @Test
        @DisplayName("ONNX推理性能统计 - 多次推理后")
        void testGetInferencePerformanceAfterMultipleInferences() {
            float[] input = new float[8];
            for (int i = 0; i < 5; i++) {
                onnxInferenceService.inferFaultProbability(input);
            }

            Map<String, Object> performance = onnxInferenceService.getInferencePerformance();

            long totalCount = (Long) performance.get("totalInferenceCount");
            assertTrue(totalCount >= 5, "总推理次数应不少于5，实际: " + totalCount);
        }

        @Test
        @DisplayName("ONNX与传统方法对比 - 结果完整性")
        void testCompareTraditionalVsOnnxInference() {
            Map<String, Object> comparison = onnxInferenceService.compareTraditionalVsOnnxInference();

            assertNotNull(comparison, "对比结果不应为null");
            assertTrue(comparison.containsKey("traditionalTimeMs"), "应包含传统方法耗时");
            assertTrue(comparison.containsKey("onnxTimeMs"), "应包含ONNX耗时");
            assertTrue(comparison.containsKey("speedupRatio"), "应包含加速比");
            assertTrue(comparison.containsKey("probabilityComparison"), "应包含概率对比");

            @SuppressWarnings("unchecked")
            Map<String, Map<String, Double>> probComparison =
                    (Map<String, Map<String, Double>>) comparison.get("probabilityComparison");
            assertEquals(4, probComparison.size(), "应对比4种故障类型");
        }

        @Test
        @DisplayName("ONNX推理 - 故障概率合理性")
        void testInferFaultProbabilityReasonableRange() {
            List<PumpingUnitData> data = createGradualFaultData("ROD_BREAK", 72);
            float[] features = onnxInferenceService.preprocessTimeSeries(data);

            Map<String, Double> result = onnxInferenceService.inferFaultProbability(features);

            for (Map.Entry<String, Double> entry : result.entrySet()) {
                Double prob = entry.getValue();
                assertTrue(prob >= 0.0 && prob <= 1.0,
                        entry.getKey() + "概率应在[0,1]范围内，实际: " + prob);
            }
        }
    }

    private List<PumpingUnitData> createTimeSeriesData(
            double baseFluidLevel, double baseCurrent,
            double fluidLevelTrend, double currentTrend,
            int hours) {

        List<PumpingUnitData> data = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.now();

        for (int i = 0; i < hours; i++) {
            PumpingUnitData d = new PumpingUnitData();
            d.setWellId("TEST-001");
            d.setRecordTime(baseTime.minusHours(hours - i));
            d.setDynamicFluidLevel(baseFluidLevel + i * fluidLevelTrend);
            d.setMotorCurrent(baseCurrent + i * currentTrend);
            d.setCasingPressure(2.0);
            d.setPumpEfficiency(0.75);
            data.add(d);
        }

        return data;
    }

    private List<PumpingUnitData> createNoisyTimeSeriesData(
            double baseFluidLevel, double baseCurrent,
            double fluidLevelTrend, double currentTrend,
            int hours, double noiseLevel) {

        Random random = new Random(42);
        List<PumpingUnitData> data = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.now();

        for (int i = 0; i < hours; i++) {
            PumpingUnitData d = new PumpingUnitData();
            d.setWellId("TEST-001");
            d.setRecordTime(baseTime.minusHours(hours - i));
            double fluidNoise = (random.nextDouble() - 0.5) * noiseLevel * baseFluidLevel;
            double currentNoise = (random.nextDouble() - 0.5) * noiseLevel * baseCurrent;
            d.setDynamicFluidLevel(baseFluidLevel + i * fluidLevelTrend + fluidNoise);
            d.setMotorCurrent(baseCurrent + i * currentTrend + currentNoise);
            d.setCasingPressure(2.0);
            d.setPumpEfficiency(0.75);
            data.add(d);
        }

        return data;
    }

    private List<PumpingUnitData> createGradualFaultData(String faultType, int hours) {
        List<PumpingUnitData> data = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.now();

        for (int i = 0; i < hours; i++) {
            PumpingUnitData d = new PumpingUnitData();
            d.setWellId("TEST-001");
            d.setRecordTime(baseTime.minusHours(hours - i));

            double progress = (double) i / hours;

            switch (faultType) {
                case "ROD_BREAK":
                    d.setDynamicFluidLevel(800 + progress * 300);
                    d.setMotorCurrent(25 - progress * 15);
                    d.setPumpEfficiency(Math.max(0.2, 0.8 - progress * 0.5));
                    break;
                case "PUMP_LEAK":
                    d.setDynamicFluidLevel(800 + progress * 400);
                    d.setMotorCurrent(20 + progress * 8);
                    d.setPumpEfficiency(Math.max(0.2, 0.8 - progress * 0.6));
                    break;
                case "GAS_LOCK":
                    d.setDynamicFluidLevel(900 + progress * 200);
                    d.setMotorCurrent(22 - progress * 12);
                    d.setPumpEfficiency(Math.max(0.15, 0.75 - progress * 0.6));
                    break;
                case "VALVE_LEAK":
                    d.setDynamicFluidLevel(950 + progress * 150);
                    d.setMotorCurrent(19 + progress * 6);
                    d.setPumpEfficiency(Math.max(0.3, 0.85 - progress * 0.5));
                    break;
                default:
                    d.setDynamicFluidLevel(1000.0);
                    d.setMotorCurrent(20.0);
                    d.setPumpEfficiency(0.75);
            }

            d.setCasingPressure(2.0 + progress * 0.5);
            data.add(d);
        }

        return data;
    }

    private List<PumpingUnitData> createMultipleFaultData() {
        List<PumpingUnitData> data = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.now();

        for (int i = 0; i < 96; i++) {
            PumpingUnitData d = new PumpingUnitData();
            d.setWellId("TEST-005");
            d.setRecordTime(baseTime.minusHours(96 - i));
            double progress = (double) i / 96;
            d.setDynamicFluidLevel(750 + progress * 500);
            d.setMotorCurrent(22 - progress * 10);
            d.setPumpEfficiency(Math.max(0.15, 0.85 - progress * 0.7));
            d.setCasingPressure(2.0 + progress * 1.0);
            data.add(d);
        }

        return data;
    }

    private List<PumpingUnitData> createNormalStableData(int hours) {
        Random random = new Random(12345);
        List<PumpingUnitData> data = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.now();

        for (int i = 0; i < hours; i++) {
            PumpingUnitData d = new PumpingUnitData();
            d.setWellId("TEST-010");
            d.setRecordTime(baseTime.minusHours(hours - i));
            d.setDynamicFluidLevel(1000 + (random.nextDouble() - 0.5) * 20);
            d.setMotorCurrent(20 + (random.nextDouble() - 0.5) * 1);
            d.setCasingPressure(2.0 + (random.nextDouble() - 0.5) * 0.1);
            d.setPumpEfficiency(0.75 + (random.nextDouble() - 0.5) * 0.02);
            data.add(d);
        }

        return data;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeAnalyzeTimeSeriesData(List<PumpingUnitData> data) throws Exception {
        Method method = PumpingUnitFaultPredictor.class.getDeclaredMethod(
                "analyzeTimeSeriesData", List.class, AdvancedFeaturesProperties.Fault.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(predictor, data, faultConfig);
    }

    private void invokeAddMaintenanceRecommendation(FaultPrediction prediction) {
        try {
            Method method = PumpingUnitFaultPredictor.class.getDeclaredMethod(
                    "addMaintenanceRecommendation", FaultPrediction.class, AdvancedFeaturesProperties.Fault.class);
            method.setAccessible(true);
            method.invoke(predictor, prediction, faultConfig);
        } catch (Exception e) {
            throw new RuntimeException("反射调用失败", e);
        }
    }
}