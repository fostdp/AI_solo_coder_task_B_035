package com.smart.oilfield.connectivity.service;

import com.smart.oilfield.common.config.AdvancedFeaturesProperties;
import com.smart.oilfield.common.dto.ConnectivityAnalysisRequest;
import com.smart.oilfield.common.entity.WaterInjectionData;
import com.smart.oilfield.common.entity.Well;
import com.smart.oilfield.common.entity.WellConnectivity;
import com.smart.oilfield.common.repository.WaterInjectionDataRepository;
import com.smart.oilfield.common.repository.WellConnectivityRepository;
import com.smart.oilfield.common.repository.WellRepository;
import com.smart.oilfield.connectivity.ConnectivityAnalyzerApplication;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = ConnectivityAnalyzerApplication.class)
@ExtendWith(MockitoExtension.class)
@DisplayName("连通性分析测试 - WellConnectivityAnalyzer")
class WellConnectivityAnalyzerTest {

    @Mock
    private WellConnectivityRepository connectivityRepository;

    @Mock
    private WellRepository wellRepository;

    @Mock
    private WaterInjectionDataRepository injectionDataRepository;

    @Mock
    private AdvancedFeaturesProperties properties;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Spy
    private PearsonsCorrelation pearsonsCorrelation = new PearsonsCorrelation();

    @InjectMocks
    private WellConnectivityAnalyzer analyzer;

    private AdvancedFeaturesProperties.Connectivity connectivityConfig;

    @BeforeEach
    void setUp() {
        connectivityConfig = new AdvancedFeaturesProperties.Connectivity();
        connectivityConfig.setEnabled(true);
        connectivityConfig.setMinDataPoints(24);
        connectivityConfig.setMaxLagHours(72);
        connectivityConfig.setStrongConnectivityThreshold(0.7);
        connectivityConfig.setModerateConnectivityThreshold(0.4);
        connectivityConfig.setWeakConnectivityThreshold(0.2);
        connectivityConfig.setMinAnalysisWindowDays(7);
        connectivityConfig.setDefaultAnalysisWindowDays(30);
        connectivityConfig.setSignificanceThreshold(0.3);
        connectivityConfig.setMinDataQualityThreshold(0.5);

        when(properties.getConnectivity()).thenReturn(connectivityConfig);
    }

    @Nested
    @DisplayName("皮尔逊相关系数计算测试")
    class PearsonCorrelationTests {

        @Test
        @DisplayName("完全正相关 - 验证皮尔逊相关系数接近1.0")
        void testPerfectPositiveCorrelation() {
            double[] x = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
            double[] y = {2, 4, 6, 8, 10, 12, 14, 16, 18, 20};

            double result = analyzer.calculatePearsonCorrelation(x, y);

            assertEquals(1.0, result, 0.0001,
                    "完全正相关的数据对皮尔逊系数应接近1.0");
            assertTrue(result > 0.99, "正相关系数应大于0.99");
        }

        @Test
        @DisplayName("完全负相关 - 验证皮尔逊相关系数接近-1.0")
        void testPerfectNegativeCorrelation() {
            double[] x = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
            double[] y = {20, 18, 16, 14, 12, 10, 8, 6, 4, 2};

            double result = analyzer.calculatePearsonCorrelation(x, y);

            assertEquals(-1.0, result, 0.0001,
                    "完全负相关的数据对皮尔逊系数应接近-1.0");
            assertTrue(result < -0.99, "负相关系数应小于-0.99");
        }

        @Test
        @DisplayName("无相关 - 验证皮尔逊相关系数接近0")
        void testNoCorrelation() {
            double[] x = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
            double[] y = {5, 2, 8, 1, 9, 3, 7, 4, 6, 10};

            double result = analyzer.calculatePearsonCorrelation(x, y);

            assertEquals(0.0, result, 0.3,
                    "随机数据的皮尔逊系数应接近0");
        }

        @Test
        @DisplayName("中等正相关 - 验证皮尔逊相关系数在预期范围")
        void testModeratePositiveCorrelation() {
            double[] x = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
            double[] y = {1.5, 3.2, 4.1, 5.8, 7.3, 8.9, 10.2, 11.8, 13.5, 15.1};

            double result = analyzer.calculatePearsonCorrelation(x, y);

            assertTrue(result > 0.9 && result < 1.0,
                    "中等正相关系数应在0.9到1.0之间，实际为: " + result);
        }

        @Test
        @DisplayName("边界 - 数组长度不足2 - 返回0.0")
        void testInsufficientDataLength() {
            double[] x = {1};
            double[] y = {2};

            double result = analyzer.calculatePearsonCorrelation(x, y);

            assertEquals(0.0, result, "长度不足2的数组应返回0.0");
        }

        @Test
        @DisplayName("边界 - 数组长度不一致 - 返回0.0")
        void testMismatchedArrayLength() {
            double[] x = {1, 2, 3, 4, 5};
            double[] y = {2, 4, 6};

            double result = analyzer.calculatePearsonCorrelation(x, y);

            assertEquals(0.0, result, "长度不一致的数组应返回0.0");
        }

        @Test
        @DisplayName("边界 - 常数数组 - 异常处理返回0.0")
        void testConstantArray() {
            double[] x = {5, 5, 5, 5, 5, 5, 5, 5, 5, 5};
            double[] y = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

            double result = analyzer.calculatePearsonCorrelation(x, y);

            assertEquals(0.0, result, "常数数组的皮尔逊系数应返回0.0（异常处理）");
        }

        @Test
        @DisplayName("异常 - NaN值处理")
        void testNaNValues() {
            double[] x = {1, 2, Double.NaN, 4, 5, 6, 7, 8, 9, 10};
            double[] y = {2, 4, 6, 8, 10, 12, 14, 16, 18, 20};

            double result = analyzer.calculatePearsonCorrelation(x, y);

            assertEquals(0.0, result, "包含NaN的数组应返回0.0（异常处理）");
        }

        @Test
        @DisplayName("精度验证 - 与Apache Commons Math结果对比")
        void testPrecisionAgainstApacheMath() {
            double[] x = generateSinusoidalData(100, 1.0, 0.0);
            double[] y = generateSinusoidalData(100, 1.0, 0.5);

            double expected = pearsonsCorrelation.correlation(x, y);
            double actual = analyzer.calculatePearsonCorrelation(x, y);

            assertEquals(expected, actual, 0.0001,
                    "自定义实现应与Apache Commons Math结果一致");
        }

        @Test
        @DisplayName("大数据量性能 - 1000个数据点")
        void testLargeDatasetPerformance() {
            double[] x = generateSinusoidalData(1000, 1.0, 0.0);
            double[] y = generateSinusoidalData(1000, 1.0, 0.3);

            long startTime = System.nanoTime();
            double result = analyzer.calculatePearsonCorrelation(x, y);
            long endTime = System.nanoTime();
            long duration = (endTime - startTime) / 1000000;

            assertTrue(result > 0.9, "1000个数据点的相关系数应正确计算");
            assertTrue(duration < 100, "1000个数据点计算应在100ms内完成，实际: " + duration + "ms");
        }
    }

    @Nested
    @DisplayName("时滞互相关分析测试")
    class TimeLagCrossCorrelationTests {

        @Test
        @DisplayName("无时延 - 验证最佳时延为0")
        void testZeroTimeLag() {
            double[] x = generateSinusoidalData(50, 1.0, 0.0);
            double[] y = generateSinusoidalData(50, 1.0, 0.0);

            WellConnectivityAnalyzer.CrossCorrelationResult result =
                    analyzer.calculateTimeLagCrossCorrelation(x, y, 24);

            assertEquals(0, result.optimalLag, "无时延数据的最佳时延应为0");
            assertTrue(result.maxCorrelation > 0.95,
                    "无时延数据的最大互相关系数应大于0.95，实际为: " + result.maxCorrelation);
        }

        @Test
        @DisplayName("已知时延估计 - 验证时延估计精度")
        void testKnownTimeLagEstimation() {
            int trueLag = 8;
            int dataLength = 100;
            double[] x = generateSinusoidalData(dataLength, 1.0, 0.0);
            double[] y = generateSinusoidalData(dataLength, 1.0, trueLag * 0.1);

            WellConnectivityAnalyzer.CrossCorrelationResult result =
                    analyzer.calculateTimeLagCrossCorrelation(x, y, 24);

            assertEquals(trueLag, result.optimalLag,
                    "时延估计应准确，预期: " + trueLag + "，实际: " + result.optimalLag);
            assertTrue(result.maxCorrelation > 0.8,
                    "已知时延数据的最大互相关系数应大于0.8，实际为: " + result.maxCorrelation);
        }

        @Test
        @DisplayName("最大时延边界 - 验证不超过maxLagHours")
        void testMaxLagBoundary() {
            int maxLag = 12;
            double[] x = generateSinusoidalData(100, 1.0, 0.0);
            double[] y = generateSinusoidalData(100, 1.0, 30 * 0.1);

            WellConnectivityAnalyzer.CrossCorrelationResult result =
                    analyzer.calculateTimeLagCrossCorrelation(x, y, maxLag);

            assertTrue(result.optimalLag <= maxLag,
                    "最佳时滞不应超过maxLagHours，实际为: " + result.optimalLag);
            assertTrue(result.optimalLag >= 0, "最佳时滞应为非负数");
        }

        @Test
        @DisplayName("短数据序列 - 验证最小数据点要求")
        void testShortDataSequence() {
            double[] x = {1, 2, 3, 4, 5, 6, 7, 8};
            double[] y = {1, 2, 3, 4, 5, 6, 7, 8};

            WellConnectivityAnalyzer.CrossCorrelationResult result =
                    analyzer.calculateTimeLagCrossCorrelation(x, y, 10);

            assertNotNull(result, "短序列也应返回结果");
            assertTrue(result.optimalLag >= 0, "最佳时滞应为非负数");
        }

        @Test
        @DisplayName("噪声数据 - 验证时延估计鲁棒性")
        void testNoisyDataTimeLag() {
            int trueLag = 5;
            double[] x = generateNoisySinusoidalData(80, 1.0, 0.0, 0.1);
            double[] y = generateNoisySinusoidalData(80, 1.0, trueLag * 0.1, 0.1);

            WellConnectivityAnalyzer.CrossCorrelationResult result =
                    analyzer.calculateTimeLagCrossCorrelation(x, y, 24);

            assertTrue(Math.abs(result.optimalLag - trueLag) <= 2,
                    "噪声数据时延估计误差应在±2以内，预期: " + trueLag + "，实际: " + result.optimalLag);
            assertTrue(result.maxCorrelation > 0.7,
                    "噪声数据的最大互相关系数应大于0.7，实际为: " + result.maxCorrelation);
        }

        @Test
        @DisplayName("边界 - 数据长度不足5 - 返回0时延")
        void testVeryShortData() {
            double[] x = {1, 2, 3, 4};
            double[] y = {1, 2, 3, 4};

            WellConnectivityAnalyzer.CrossCorrelationResult result =
                    analyzer.calculateTimeLagCrossCorrelation(x, y, 10);

            assertEquals(0, result.optimalLag, "数据不足5个点时应返回0时延");
        }

        @Test
        @DisplayName("时延估计精度 - 不同时滞下的准确率")
        void testTimeLagAccuracyVariousLags() {
            int[] trueLags = {0, 3, 6, 12, 24};
            int maxLag = 30;

            for (int trueLag : trueLags) {
                double[] x = generateSinusoidalData(200, 1.0, 0.0);
                double[] y = generateSinusoidalData(200, 1.0, trueLag * 0.05);

                WellConnectivityAnalyzer.CrossCorrelationResult result =
                        analyzer.calculateTimeLagCrossCorrelation(x, y, maxLag);

                int error = Math.abs(result.optimalLag - trueLag);
                assertTrue(error <= 1,
                        "时滞 " + trueLag + " 的估计误差应≤1，实际误差: " + error +
                                "，估计值: " + result.optimalLag);
            }
        }
    }

    @Nested
    @DisplayName("连通强度标注测试")
    class ConnectivityStrengthTests {

        @Test
        @DisplayName("强连通 - 验证标注为STRONG")
        void testStrongConnectivity() {
            double pearson = 0.95;
            double crossCorr = 0.92;
            double dataQuality = 0.9;

            WellConnectivity connectivity = calculateConnectivity(pearson, crossCorr, dataQuality);

            assertEquals("STRONG", connectivity.getConnectivityType(),
                    "高强度数据应标注为STRONG");
            assertTrue(connectivity.getConnectivityStrength() >= 0.7,
                    "强连通强度应≥0.7，实际为: " + connectivity.getConnectivityStrength());
            assertTrue(connectivity.getIsSignificant(), "强连通应标记为显著");
        }

        @Test
        @DisplayName("中连通 - 验证标注为MODERATE")
        void testModerateConnectivity() {
            double pearson = 0.6;
            double crossCorr = 0.55;
            double dataQuality = 0.8;

            WellConnectivity connectivity = calculateConnectivity(pearson, crossCorr, dataQuality);

            assertEquals("MODERATE", connectivity.getConnectivityType(),
                    "中等强度数据应标注为MODERATE");
            double strength = connectivity.getConnectivityStrength();
            assertTrue(strength >= 0.4 && strength < 0.7,
                    "中连通强度应在[0.4, 0.7)之间，实际为: " + strength);
        }

        @Test
        @DisplayName("弱连通 - 验证标注为WEAK")
        void testWeakConnectivity() {
            double pearson = 0.35;
            double crossCorr = 0.3;
            double dataQuality = 0.7;

            WellConnectivity connectivity = calculateConnectivity(pearson, crossCorr, dataQuality);

            assertEquals("WEAK", connectivity.getConnectivityType(),
                    "低强度数据应标注为WEAK");
            double strength = connectivity.getConnectivityStrength();
            assertTrue(strength >= 0.2 && strength < 0.4,
                    "弱连通强度应在[0.2, 0.4)之间，实际为: " + strength);
        }

        @Test
        @DisplayName("无连通 - 验证标注为NONE")
        void testNoConnectivity() {
            double pearson = 0.1;
            double crossCorr = 0.05;
            double dataQuality = 0.6;

            WellConnectivity connectivity = calculateConnectivity(pearson, crossCorr, dataQuality);

            assertEquals("NONE", connectivity.getConnectivityType(),
                    "极弱数据应标注为NONE");
            assertTrue(connectivity.getConnectivityStrength() < 0.2,
                    "无连通强度应<0.2，实际为: " + connectivity.getConnectivityStrength());
            assertFalse(connectivity.getIsSignificant(), "无连通信不应标记为显著");
        }

        @Test
        @DisplayName("边界阈值 - 强连通临界值")
        void testStrongThresholdBoundary() {
            connectivityConfig.setStrongConnectivityThreshold(0.7);

            WellConnectivity connectivity1 = calculateConnectivity(0.87, 0.87, 0.7);
            assertEquals(0.7, connectivity1.getConnectivityStrength(), 0.001,
                    "边界值0.7应正确计算");

            WellConnectivity connectivity2 = calculateConnectivity(0.88, 0.88, 0.7);
            assertTrue(connectivity2.getConnectivityStrength() > 0.7,
                    "略高于临界值应标记为STRONG");
        }

        @Test
        @DisplayName("权重验证 - 验证权重公式正确性")
        void testWeightCalculation() {
            double pearson = 0.8;
            double crossCorr = 0.7;
            double dataQuality = 0.9;

            WellConnectivity connectivity = calculateConnectivity(pearson, crossCorr, dataQuality);

            double expectedStrength = 0.4 * Math.abs(pearson) +
                    0.4 * Math.abs(crossCorr) +
                    0.2 * dataQuality;

            assertEquals(expectedStrength, connectivity.getConnectivityStrength(), 0.0001,
                    "连通强度应正确应用权重公式");
        }

        @Test
        @DisplayName("数据质量影响 - 低质量数据降低强度")
        void testDataQualityImpact() {
            double pearson = 0.9;
            double crossCorr = 0.85;

            WellConnectivity highQuality = calculateConnectivity(pearson, crossCorr, 0.95);
            WellConnectivity lowQuality = calculateConnectivity(pearson, crossCorr, 0.3);

            assertTrue(highQuality.getConnectivityStrength() > lowQuality.getConnectivityStrength(),
                    "高质量数据的连通强度应高于低质量数据");
            assertTrue(lowQuality.getConnectivityStrength() < 0.7,
                    "低质量数据即使相关性高也不应达到强连通");
        }

        @Test
        @DisplayName("负值处理 - 负相关取绝对值")
        void testNegativeCorrelationHandling() {
            WellConnectivity positive = calculateConnectivity(0.8, 0.7, 0.9);
            WellConnectivity negative = calculateConnectivity(-0.8, -0.7, 0.9);

            assertEquals(positive.getConnectivityStrength(), negative.getConnectivityStrength(), 0.0001,
                    "负相关的绝对值应与正相关产生相同的强度");
            assertEquals(positive.getConnectivityType(), negative.getConnectivityType(),
                    "负相关与正相关应有相同的连通类型");
        }

        @Test
        @DisplayName("极端值 - 完全相关高数据质量")
        void testPerfectCorrelationHighQuality() {
            WellConnectivity connectivity = calculateConnectivity(1.0, 1.0, 1.0);

            assertEquals(1.0, connectivity.getConnectivityStrength(), 0.0001,
                    "完全相关+高质量应得到最大强度1.0");
            assertEquals("STRONG", connectivity.getConnectivityType());
        }

        @Test
        @DisplayName("极端值 - 零相关")
        void testZeroCorrelation() {
            WellConnectivity connectivity = calculateConnectivity(0.0, 0.0, 1.0);

            assertEquals(0.2, connectivity.getConnectivityStrength(), 0.0001,
                    "零相关+高质量应得到数据质量贡献0.2");
            assertEquals("WEAK", connectivity.getConnectivityType());
        }
    }

    @Nested
    @DisplayName("区块连通性分析集成测试")
    class BlockAnalysisIntegrationTests {

        @Test
        @DisplayName("正常场景 - 完整区块分析流程")
        void testNormalBlockAnalysis() {
            String blockName = "东区";
            LocalDate analysisDate = LocalDate.now();

            Well injWell = createMockWell("INJ-001", "injection", blockName);
            Well prodWell = createMockWell("PRO-001", "production", blockName);

            List<Well> injectionWells = Collections.singletonList(injWell);
            List<Well> productionWells = Collections.singletonList(prodWell);

            when(wellRepository.findByBlockNameAndWellType(blockName, "injection"))
                    .thenReturn(injectionWells);
            when(wellRepository.findByBlockNameAndWellType(blockName, "production"))
                    .thenReturn(productionWells);

            List<WaterInjectionData> injData = createMockInjectionData(30, 20.0, 5.0);
            List<WaterInjectionData> prodData = createMockInjectionData(30, 18.0, 4.5);

            when(injectionDataRepository.findByWellIdAndReportDateBetweenOrderByReportDate(
                    eq("INJ-001"), any(), any())).thenReturn(injData);
            when(injectionDataRepository.findByWellIdAndReportDateBetweenOrderByReportDate(
                    eq("PRO-001"), any(), any())).thenReturn(prodData);

            when(connectivityRepository.findByInjectionWellIdAndProductionWellIdAndAnalysisDate(
                    any(), any(), any())).thenReturn(Optional.empty());
            when(connectivityRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            ConnectivityAnalysisRequest request = new ConnectivityAnalysisRequest();
            request.setBlockName(blockName);
            request.setAnalysisDate(analysisDate);

            List<WellConnectivity> results = analyzer.analyzeBlockConnectivity(request);

            assertNotNull(results, "分析结果不应为空");
            assertFalse(results.isEmpty(), "正常区块应产生分析结果");
            assertEquals(1, results.size(), "1对注采井应产生1条结果");

            WellConnectivity result = results.get(0);
            assertNotNull(result.getPearsonCorrelation(), "皮尔逊系数不应为空");
            assertNotNull(result.getCrossCorrelation(), "互相关系数不应为空");
            assertNotNull(result.getConnectivityStrength(), "连通强度不应为空");
            assertNotNull(result.getConnectivityType(), "连通类型不应为空");
        }

        @Test
        @DisplayName("边界场景 - 区块无注水井")
        void testBlockWithNoInjectionWells() {
            String blockName = "空区块";
            when(wellRepository.findByBlockNameAndWellType(blockName, "injection"))
                    .thenReturn(Collections.emptyList());
            when(wellRepository.findByBlockNameAndWellType(blockName, "production"))
                    .thenReturn(Collections.singletonList(createMockWell("PRO-001", "production", blockName)));

            ConnectivityAnalysisRequest request = new ConnectivityAnalysisRequest();
            request.setBlockName(blockName);

            List<WellConnectivity> results = analyzer.analyzeBlockConnectivity(request);

            assertTrue(results.isEmpty(), "无注水井的区块应返回空结果");
        }

        @Test
        @DisplayName("边界场景 - 区块无采油井")
        void testBlockWithNoProductionWells() {
            String blockName = "空区块";
            when(wellRepository.findByBlockNameAndWellType(blockName, "injection"))
                    .thenReturn(Collections.singletonList(createMockWell("INJ-001", "injection", blockName)));
            when(wellRepository.findByBlockNameAndWellType(blockName, "production"))
                    .thenReturn(Collections.emptyList());

            ConnectivityAnalysisRequest request = new ConnectivityAnalysisRequest();
            request.setBlockName(blockName);

            List<WellConnectivity> results = analyzer.analyzeBlockConnectivity(request);

            assertTrue(results.isEmpty(), "无采油井的区块应返回空结果");
        }

        @Test
        @DisplayName("边界场景 - 数据不足最低要求")
        void testInsufficientData() {
            String blockName = "新区块";
            Well injWell = createMockWell("INJ-001", "injection", blockName);
            Well prodWell = createMockWell("PRO-001", "production", blockName);

            when(wellRepository.findByBlockNameAndWellType(blockName, "injection"))
                    .thenReturn(Collections.singletonList(injWell));
            when(wellRepository.findByBlockNameAndWellType(blockName, "production"))
                    .thenReturn(Collections.singletonList(prodWell));

            List<WaterInjectionData> shortData = createMockInjectionData(5, 20.0, 5.0);
            when(injectionDataRepository.findByWellIdAndReportDateBetweenOrderByReportDate(
                    any(), any(), any())).thenReturn(shortData);

            ConnectivityAnalysisRequest request = new ConnectivityAnalysisRequest();
            request.setBlockName(blockName);

            List<WellConnectivity> results = analyzer.analyzeBlockConnectivity(request);

            assertTrue(results.isEmpty(), "数据不足的井对应返回空结果");
        }

        @Test
        @DisplayName("异常场景 - 数据库异常处理")
        void testDatabaseExceptionHandling() {
            String blockName = "异常区块";
            Well injWell = createMockWell("INJ-001", "injection", blockName);
            Well prodWell = createMockWell("PRO-001", "production", blockName);

            when(wellRepository.findByBlockNameAndWellType(blockName, "injection"))
                    .thenReturn(Collections.singletonList(injWell));
            when(wellRepository.findByBlockNameAndWellType(blockName, "production"))
                    .thenReturn(Collections.singletonList(prodWell));

            when(injectionDataRepository.findByWellIdAndReportDateBetweenOrderByReportDate(
                    any(), any(), any())).thenThrow(new RuntimeException("数据库连接异常"));

            ConnectivityAnalysisRequest request = new ConnectivityAnalysisRequest();
            request.setBlockName(blockName);

            assertDoesNotThrow(() -> analyzer.analyzeBlockConnectivity(request),
                    "数据库异常应被捕获处理，不应抛出");

            verify(connectivityRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("异常场景 - 数据质量过低")
        void testLowDataQuality() {
            String blockName = "低质量区块";
            Well injWell = createMockWell("INJ-001", "injection", blockName);
            Well prodWell = createMockWell("PRO-001", "production", blockName);

            when(wellRepository.findByBlockNameAndWellType(blockName, "injection"))
                    .thenReturn(Collections.singletonList(injWell));
            when(wellRepository.findByBlockNameAndWellType(blockName, "production"))
                    .thenReturn(Collections.singletonList(prodWell));

            List<WaterInjectionData> lowQualityData = createLowQualityInjectionData(30);
            when(injectionDataRepository.findByWellIdAndReportDateBetweenOrderByReportDate(
                    any(), any(), any())).thenReturn(lowQualityData);

            ConnectivityAnalysisRequest request = new ConnectivityAnalysisRequest();
            request.setBlockName(blockName);

            List<WellConnectivity> results = analyzer.analyzeBlockConnectivity(request);

            assertTrue(results.isEmpty(), "数据质量过低的井对应返回空结果");
        }
    }

    private WellConnectivity calculateConnectivity(double pearson, double crossCorr, double dataQuality) {
        try {
            java.lang.reflect.Method method = WellConnectivityAnalyzer.class.getDeclaredMethod(
                    "calculateConnectivityStrength",
                    double.class, double.class, double.class,
                    AdvancedFeaturesProperties.Connectivity.class);
            method.setAccessible(true);

            double strength = (double) method.invoke(analyzer, pearson, crossCorr, dataQuality, connectivityConfig);

            java.lang.reflect.Method typeMethod = WellConnectivityAnalyzer.class.getDeclaredMethod(
                    "determineConnectivityType",
                    double.class, AdvancedFeaturesProperties.Connectivity.class);
            typeMethod.setAccessible(true);

            String type = (String) typeMethod.invoke(analyzer, strength, connectivityConfig);

            WellConnectivity connectivity = new WellConnectivity();
            connectivity.setPearsonCorrelation(pearson);
            connectivity.setCrossCorrelation(crossCorr);
            connectivity.setConnectivityStrength(strength);
            connectivity.setConnectivityType(type);
            connectivity.setIsSignificant(strength >= connectivityConfig.getSignificanceThreshold());

            return connectivity;
        } catch (Exception e) {
            throw new RuntimeException("反射调用失败", e);
        }
    }

    private double[] generateSinusoidalData(int length, double amplitude, double phase) {
        double[] data = new double[length];
        for (int i = 0; i < length; i++) {
            data[i] = amplitude * Math.sin(0.1 * i + phase) + 10;
        }
        return data;
    }

    private double[] generateNoisySinusoidalData(int length, double amplitude, double phase, double noiseLevel) {
        Random random = new Random(42);
        double[] data = new double[length];
        for (int i = 0; i < length; i++) {
            data[i] = amplitude * Math.sin(0.1 * i + phase) + 10 + noiseLevel * random.nextGaussian();
        }
        return data;
    }

    private Well createMockWell(String wellId, String wellType, String blockName) {
        Well well = new Well();
        well.setWellId(wellId);
        well.setWellName(wellType.equals("injection") ? "注" + wellId.substring(4) + "井" : "采" + wellId.substring(4) + "井");
        well.setWellType(wellType);
        well.setBlockName(blockName);
        return well;
    }

    private List<WaterInjectionData> createMockInjectionData(int days, double basePressure, double pressureVariation) {
        List<WaterInjectionData> data = new ArrayList<>();
        Random random = new Random(42);
        LocalDate startDate = LocalDate.now().minusDays(days);

        for (int i = 0; i < days; i++) {
            WaterInjectionData d = new WaterInjectionData();
            d.setReportDate(startDate.plusDays(i));
            d.setInjectionPressure(basePressure + pressureVariation * Math.sin(0.3 * i) + random.nextGaussian() * 0.5);
            d.setWaterVolume(50 + random.nextGaussian() * 10);
            data.add(d);
        }
        return data;
    }

    private List<WaterInjectionData> createLowQualityInjectionData(int days) {
        List<WaterInjectionData> data = new ArrayList<>();
        LocalDate startDate = LocalDate.now().minusDays(days);

        for (int i = 0; i < days; i++) {
            WaterInjectionData d = new WaterInjectionData();
            d.setReportDate(startDate.plusDays(i));
            d.setInjectionPressure(i % 3 == 0 ? 0.0 : 20.0);
            d.setWaterVolume(i % 3 == 0 ? 0.0 : 50.0);
            data.add(d);
        }
        return data;
    }
}
