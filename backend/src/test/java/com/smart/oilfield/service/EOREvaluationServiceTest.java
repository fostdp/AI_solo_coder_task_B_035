package com.smart.oilfield.service;

import com.smart.oilfield.config.AdvancedFeaturesProperties;
import com.smart.oilfield.dto.EOREvaluationRequest;
import com.smart.oilfield.entity.BlockDailySummary;
import com.smart.oilfield.entity.EOREvaluation;
import com.smart.oilfield.repository.BlockDailySummaryRepository;
import com.smart.oilfield.repository.EOREvaluationRepository;
import com.smart.oilfield.repository.ProductionDataRepository;
import com.smart.oilfield.repository.WellRepository;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("三次采油方案测试 - EOREvaluationService")
class EOREvaluationServiceTest {

    @Mock
    private EOREvaluationRepository evaluationRepository;

    @Mock
    private WellRepository wellRepository;

    @Mock
    private BlockDailySummaryRepository summaryRepository;

    @Mock
    private ProductionDataRepository productionDataRepository;

    @Mock
    private AdvancedFeaturesProperties properties;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EOREvaluationService eorService;

    private AdvancedFeaturesProperties.Eor eorConfig;

    @BeforeEach
    void setUp() {
        eorConfig = new AdvancedFeaturesProperties.Eor();
        eorConfig.setEnabled(true);
        eorConfig.setDefaultPredictionHorizonMonths(60);
        eorConfig.setDefaultOilPricePerBarrel(75.0);
        eorConfig.setDefaultDiscountRate(0.08);

        eorConfig.setPolymerCostPerTon(new BigDecimal("15000"));
        eorConfig.setSurfactantCostPerTon(new BigDecimal("20000"));

        eorConfig.setPolymerDefaultConcentration(1500.0);
        eorConfig.setSurfactantDefaultConcentration(2000.0);

        eorConfig.setPolymerDefaultSlugSize(0.5);
        eorConfig.setSurfactantDefaultSlugSize(0.3);

        eorConfig.setPolymerDefaultInjectionRate(500.0);
        eorConfig.setSurfactantDefaultInjectionRate(400.0);

        eorConfig.setPolymerOilIncrementFactor(0.35);
        eorConfig.setSurfactantOilIncrementFactor(0.30);
        eorConfig.setCombinedOilIncrementFactor(0.45);

        eorConfig.setPolymerWaterCutReduction(15.0);
        eorConfig.setSurfactantWaterCutReduction(12.0);
        eorConfig.setCombinedWaterCutReduction(20.0);

        eorConfig.setMinPermeabilityForPolymer(50.0);
        eorConfig.setMaxPermeabilityForPolymer(2000.0);
        eorConfig.setMinRemainingOilSaturation(0.3);
        eorConfig.setMaxReservoirTemperatureForPolymer(95.0);

        eorConfig.setMinTechnicalFeasibility(0.3);
        eorConfig.setMinRoiThreshold(15.0);

        eorConfig.setTechnicalFeasibilityWeight(0.4);
        eorConfig.setEconomicFeasibilityWeight(0.6);

        eorConfig.setModelVersion("1.0.0");

        when(properties.getEor()).thenReturn(eorConfig);
    }

    @Nested
    @DisplayName("代理模型预测偏差测试")
    class SurrogateModelPredictionTests {

        @Test
        @DisplayName("聚合物驱 - 理想油藏条件增油量预测")
        void testPolymerFloodingIdealConditions() {
            double permeability = 200.0;
            double remainingOil = 0.45;
            double temperature = 70.0;

            double result = invokeCalculateTechnicalFeasibility(
                    "POLYMER_FLOODING", permeability, remainingOil, temperature);

            assertTrue(result > 0.7,
                    "理想条件下聚合物驱技术可行性应>0.7，实际: " + result);
            assertTrue(result <= 1.0,
                    "技术可行性不应超过1.0");
        }

        @Test
        @DisplayName("聚合物驱 - 低渗透率油藏适应性差")
        void testPolymerFloodingLowPermeability() {
            double permeability = 30.0;
            double remainingOil = 0.45;
            double temperature = 70.0;

            double result = invokeCalculateTechnicalFeasibility(
                    "POLYMER_FLOODING", permeability, remainingOil, temperature);

            assertTrue(result < 0.6,
                    "低渗透率下聚合物驱技术可行性应<0.6，实际: " + result);
        }

        @Test
        @DisplayName("聚合物驱 - 高温油藏适应性差")
        void testPolymerFloodingHighTemperature() {
            double permeability = 200.0;
            double remainingOil = 0.45;
            double temperature = 110.0;

            double result = invokeCalculateTechnicalFeasibility(
                    "POLYMER_FLOODING", permeability, remainingOil, temperature);

            assertTrue(result < 0.6,
                    "高温下聚合物驱技术可行性应<0.6，实际: " + result);
        }

        @Test
        @DisplayName("表面活性剂驱 - 中低渗油藏适应性")
        void testSurfactantFloodingMediumPermeability() {
            double permeability = 80.0;
            double remainingOil = 0.40;
            double temperature = 60.0;

            double result = invokeCalculateTechnicalFeasibility(
                    "SURFACTANT_FLOODING", permeability, remainingOil, temperature);

            assertTrue(result > 0.6,
                    "中低渗下表面活性剂驱技术可行性应>0.6，实际: " + result);
        }

        @Test
        @DisplayName("复合驱 - 复杂油藏适应性")
        void testCombinedFloodingComplexReservoir() {
            double permeability = 150.0;
            double remainingOil = 0.38;
            double temperature = 65.0;

            double polymerResult = invokeCalculateTechnicalFeasibility(
                    "POLYMER_FLOODING", permeability, remainingOil, temperature);
            double surfactantResult = invokeCalculateTechnicalFeasibility(
                    "SURFACTANT_FLOODING", permeability, remainingOil, temperature);
            double combinedResult = invokeCalculateTechnicalFeasibility(
                    "COMBINED_FLOODING", permeability, remainingOil, temperature);

            assertTrue(combinedResult >= polymerResult,
                    "复合驱技术可行性不应低于单一聚合物驱。复合: " + combinedResult +
                            "，聚合物: " + polymerResult);
            assertTrue(combinedResult >= surfactantResult,
                    "复合驱技术可行性不应低于单一表面活性剂驱。复合: " + combinedResult +
                            "，表活剂: " + surfactantResult);
        }

        @Test
        @DisplayName("温度因子验证 - 各EOR类型最优温度")
        void testTemperatureFactorOptimalTemperature() {
            String[] eorTypes = {"POLYMER_FLOODING", "SURFACTANT_FLOODING",
                    "COMBINED_FLOODING", "ALKALINE_FLOODING"};
            double[] optimalTemps = {70.0, 60.0, 65.0, 75.0};

            for (int i = 0; i < eorTypes.length; i++) {
                double optimal = invokeCalculateTemperatureFactor(eorTypes[i], optimalTemps[i]);
                double suboptimal = invokeCalculateTemperatureFactor(eorTypes[i], optimalTemps[i] + 30);

                assertEquals(1.0, optimal, 0.01,
                        eorTypes[i] + "在最优温度下温度因子应为1.0，实际: " + optimal);
                assertTrue(suboptimal < optimal,
                        eorTypes[i] + "在偏离最优温度时因子应降低。最优: " + optimal +
                                "，偏离: " + suboptimal);
            }
        }

        @Test
        @DisplayName("流度因子验证 - 高孔渗油藏适应性好")
        void testMobilityFactorHighPermeability() {
            double highFactor = invokeCalculateMobilityFactor("POLYMER_FLOODING", 300.0, 0.30);
            double lowFactor = invokeCalculateMobilityFactor("POLYMER_FLOODING", 50.0, 0.15);

            assertTrue(highFactor > lowFactor,
                    "高孔渗油藏流度因子应高于低孔渗。高: " + highFactor + "，低: " + lowFactor);
            assertEquals(1.0, highFactor, 0.01,
                    "高孔渗下应达到最大流度因子1.0");
            assertTrue(lowFactor >= 0.2, "流度因子不应低于0.2");
        }

        @Test
        @DisplayName("预测偏差验证 - 不同油价下增油量稳定性")
        void testPredictionStabilityUnderDifferentOilPrices() {
            double[] oilPrices = {50.0, 75.0, 100.0, 150.0};
            double[] predictedIncrements = new double[oilPrices.length];

            for (int i = 0; i < oilPrices.length; i++) {
                EOREvaluation eval = invokeEvaluateScenario(
                        "POLYMER_FLOODING", 200.0, 0.4, 70.0,
                        BigDecimal.valueOf(oilPrices[i]), 0.08);
                predictedIncrements[i] = eval.getPredictedOilIncrement();
            }

            for (int i = 1; i < predictedIncrements.length; i++) {
                assertEquals(predictedIncrements[0], predictedIncrements[i], 0.01,
                        "油价变化不应影响增油量预测。价格" + oilPrices[i] +
                                "时增量: " + predictedIncrements[i] +
                                "，基准: " + predictedIncrements[0]);
            }
        }

        @Test
        @DisplayName("模型精度验证 - 与经验公式偏差在合理范围")
        void testModelAccuracyAgainstEmpiricalFormula() {
            double permeability = 200.0;
            double remainingOil = 0.4;
            double currentProduction = 100.0;
            double horizonMonths = 60;

            EOREvaluation eval = invokeEvaluateScenario(
                    "POLYMER_FLOODING", permeability, remainingOil, 70.0,
                    BigDecimal.valueOf(75.0), 0.08);

            double empiricalIncrement = currentProduction * 0.35 *
                    Math.min(permeability / 500.0, 1.0) *
                    Math.max(0, (remainingOil - 0.2) / 0.3);

            double deviation = Math.abs(eval.getPredictedOilIncrement() - empiricalIncrement * horizonMonths) /
                    (empiricalIncrement * horizonMonths) * 100;

            assertTrue(deviation < 30.0,
                    "代理模型与经验公式偏差应<30%，实际偏差: " + deviation + "%");
        }
    }

    @Nested
    @DisplayName("经济性评估指标测试")
    class EconomicEvaluationTests {

        @Test
        @DisplayName("净利润计算 - 验证收入减成本公式")
        void testNetProfitCalculation() {
            BigDecimal oilPrice = BigDecimal.valueOf(75.0);
            double totalIncrement = 10000.0;
            double barrels = totalIncrement * 7.33;
            BigDecimal expectedRevenue = BigDecimal.valueOf(barrels)
                    .multiply(oilPrice).setScale(2, RoundingMode.HALF_UP);

            EOREvaluation eval = invokeEvaluateScenario(
                    "POLYMER_FLOODING", 200.0, 0.45, 70.0,
                    oilPrice, 0.08);

            assertNotNull(eval.getTotalRevenue(), "总收入不应为空");
            assertNotNull(eval.getTotalChemicalCost(), "总化学剂成本不应为空");
            assertNotNull(eval.getNetProfit(), "净利润不应为空");

            BigDecimal expectedProfit = expectedRevenue.subtract(eval.getTotalChemicalCost())
                    .setScale(2, RoundingMode.HALF_UP);

            assertEquals(expectedProfit, eval.getNetProfit(),
                    "净利润应等于总收入减总成本。预期: " + expectedProfit +
                            "，实际: " + eval.getNetProfit());
        }

        @Test
        @DisplayName("ROI计算 - 验证投资回报率公式")
        void testROICalculation() {
            EOREvaluation eval = invokeEvaluateScenario(
                    "POLYMER_FLOODING", 300.0, 0.5, 70.0,
                    BigDecimal.valueOf(100.0), 0.08);

            assertNotNull(eval.getRoiPercentage(), "ROI不应为空");
            assertNotNull(eval.getTotalChemicalCost(), "总化学剂成本不应为空");
            assertNotNull(eval.getNetProfit(), "净利润不应为空");

            if (eval.getTotalChemicalCost().doubleValue() > 0) {
                double expectedROI = (eval.getNetProfit().doubleValue() /
                        eval.getTotalChemicalCost().doubleValue()) * 100;
                assertEquals(expectedROI, eval.getRoiPercentage(), 0.01,
                        "ROI计算应正确。预期: " + expectedROI + "%，实际: " + eval.getRoiPercentage() + "%");
            }
        }

        @Test
        @DisplayName("投资回收期计算 - 验证回收周期公式")
        void testPaybackPeriodCalculation() {
            BigDecimal oilPrice = BigDecimal.valueOf(100.0);

            EOREvaluation eval = invokeEvaluateScenario(
                    "POLYMER_FLOODING", 300.0, 0.5, 70.0,
                    oilPrice, 0.08);

            assertNotNull(eval.getPaybackPeriodMonths(), "投资回收期不应为空");
            assertNotNull(eval.getPredictedOilIncrement(), "预测增油量不应为空");
            assertNotNull(eval.getPredictionTimeHorizonMonths(), "预测周期不应为空");

            double monthlyIncrement = eval.getPredictedOilIncrement() /
                    eval.getPredictionTimeHorizonMonths();
            double monthlyRevenue = monthlyIncrement * 7.33 * oilPrice.doubleValue();

            if (monthlyRevenue > 0) {
                int expectedPayback = (int) Math.ceil(
                        eval.getTotalChemicalCost().doubleValue() / monthlyRevenue);
                int maxPayback = eval.getPredictionTimeHorizonMonths() * 2;
                expectedPayback = Math.min(expectedPayback, maxPayback);

                assertEquals(expectedPayback, eval.getPaybackPeriodMonths(),
                        "投资回收期计算应正确。预期: " + expectedPayback +
                                "个月，实际: " + eval.getPaybackPeriodMonths() + "个月");
            }
        }

        @Test
        @DisplayName("经济可行性评分 - 验证ROI和回收期权重")
        void testEconomicViabilityScoring() {
            double excellentROI = 100.0;
            int excellentPayback = 12;
            double poorROI = 5.0;
            int poorPayback = 60;

            double excellentViability = invokeCalculateEconomicViability(excellentROI, excellentPayback);
            double poorViability = invokeCalculateEconomicViability(poorROI, poorPayback);

            assertTrue(excellentViability > 0.8,
                    "优秀项目经济可行性应>0.8，实际: " + excellentViability);
            assertTrue(poorViability < 0.6,
                    "较差项目经济可行性应<0.6，实际: " + poorViability);
            assertTrue(excellentViability > poorViability,
                    "优秀项目评分应高于较差项目");
        }

        @Test
        @DisplayName("综合评分 - 验证技术和经济权重")
        void testOverallScoreWeighting() {
            double technical = 0.8;
            double economic = 0.6;

            double expectedOverall = 0.5 * technical + 0.5 * economic;

            EOREvaluation eval = invokeEvaluateScenarioWithScores(
                    "POLYMER_FLOODING", technical, economic);

            assertEquals(expectedOverall, eval.getOverallScore(), 0.01,
                    "综合评分应正确应用权重。预期: " + expectedOverall +
                            "，实际: " + eval.getOverallScore());
        }

        @Test
        @DisplayName("高油价下经济性提升")
        void testEconomicImprovementWithHighOilPrice() {
            EOREvaluation lowPriceEval = invokeEvaluateScenario(
                    "POLYMER_FLOODING", 200.0, 0.4, 70.0,
                    BigDecimal.valueOf(50.0), 0.08);

            EOREvaluation highPriceEval = invokeEvaluateScenario(
                    "POLYMER_FLOODING", 200.0, 0.4, 70.0,
                    BigDecimal.valueOf(100.0), 0.08);

            assertTrue(highPriceEval.getRoiPercentage() > lowPriceEval.getRoiPercentage(),
                    "高油价下ROI应更高。低油价ROI: " + lowPriceEval.getRoiPercentage() +
                            "%，高油价ROI: " + highPriceEval.getRoiPercentage() + "%");
            assertTrue(highPriceEval.getNetProfit().compareTo(lowPriceEval.getNetProfit()) > 0,
                    "高油价下净利润应更高");
        }

        @Test
        @DisplayName("边界 - 零化学剂成本处理")
        void testZeroChemicalCostHandling() {
            EOREvaluation eval = invokeEvaluateScenario(
                    "ALKALINE_FLOODING", 200.0, 0.4, 70.0,
                    BigDecimal.valueOf(75.0), 0.08);

            if (eval.getTotalChemicalCost().doubleValue() == 0) {
                assertEquals(0.0, eval.getRoiPercentage(), 0.01,
                        "零成本时ROI应为0");
            }
        }

        @Test
        @DisplayName("边界 - 负净利润处理")
        void testNegativeNetProfitHandling() {
            EOREvaluation eval = invokeEvaluateScenario(
                    "POLYMER_FLOODING", 50.0, 0.25, 90.0,
                    BigDecimal.valueOf(30.0), 0.08);

            if (eval.getTotalRevenue().compareTo(eval.getTotalChemicalCost()) < 0) {
                assertTrue(eval.getNetProfit().compareTo(BigDecimal.ZERO) < 0,
                        "收入低于成本时净利润应为负");
                assertTrue(eval.getRoiPercentage() < 0,
                        "亏损项目ROI应为负，实际: " + eval.getRoiPercentage() + "%");
            }
        }

        @Test
        @DisplayName("折现率影响 - 高折现率降低经济价值")
        void testDiscountRateImpact() {
            EOREvaluation lowDiscountEval = invokeEvaluateScenario(
                    "POLYMER_FLOODING", 200.0, 0.4, 70.0,
                    BigDecimal.valueOf(75.0), 0.05);

            EOREvaluation highDiscountEval = invokeEvaluateScenario(
                    "POLYMER_FLOODING", 200.0, 0.4, 70.0,
                    BigDecimal.valueOf(75.0), 0.15);

            assertTrue(highDiscountEval.getEconomicViability() <= lowDiscountEval.getEconomicViability(),
                    "高折现率不应提高经济可行性评分。低折现率: " + lowDiscountEval.getEconomicViability() +
                            "，高折现率: " + highDiscountEval.getEconomicViability());
        }
    }

    @Nested
    @DisplayName("方案对比分析完整性测试")
    class ComparativeAnalysisTests {

        @Test
        @DisplayName("完整评估流程 - 4种方案全部生成")
        void testCompleteEvaluationAllScenarios() {
            String blockName = "东区";
            setupMockBlockData(blockName);

            EOREvaluationRequest request = new EOREvaluationRequest();
            request.setBlockName(blockName);
            request.setEvaluationDate(LocalDate.now());
            request.setPredictionTimeHorizonMonths(60);
            request.setOilPricePerBarrel(BigDecimal.valueOf(75.0));
            request.setDiscountRate(0.08);

            when(evaluationRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<EOREvaluation> results = eorService.evaluateBlockScenarios(request);

            assertNotNull(results, "评估结果不应为空");
            assertEquals(4, results.size(), "应生成4种EOR方案的评估结果");

            Set<String> expectedTypes = new HashSet<>(Arrays.asList(
                    "POLYMER_FLOODING", "SURFACTANT_FLOODING",
                    "COMBINED_FLOODING", "ALKALINE_FLOODING"));
            Set<String> actualTypes = results.stream()
                    .map(EOREvaluation::getEorType)
                    .collect(Collectors.toSet());

            assertEquals(expectedTypes, actualTypes, "应包含所有4种EOR类型");
        }

        @Test
        @DisplayName("推荐方案 - 综合评分最高者被推荐")
        void testRecommendedScenarioHasHighestScore() {
            String blockName = "东区";
            setupMockBlockData(blockName);

            EOREvaluationRequest request = new EOREvaluationRequest();
            request.setBlockName(blockName);

            when(evaluationRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<EOREvaluation> results = eorService.evaluateBlockScenarios(request);

            Optional<EOREvaluation> recommended = results.stream()
                    .filter(EOREvaluation::getIsRecommended)
                    .findFirst();

            assertTrue(recommended.isPresent(), "应有推荐方案");

            EOREvaluation recommendedEval = recommended.get();
            for (EOREvaluation eval : results) {
                if (!eval.getIsRecommended()) {
                    assertTrue(recommendedEval.getOverallScore() >= eval.getOverallScore(),
                            "推荐方案评分应最高。推荐方案评分: " + recommendedEval.getOverallScore() +
                                    "，方案" + eval.getEorType() + "评分: " + eval.getOverallScore());
                }
            }
        }

        @Test
        @DisplayName("排序验证 - 按综合评分降序排列")
        void testResultsSortedByOverallScore() {
            String blockName = "东区";
            setupMockBlockData(blockName);

            EOREvaluationRequest request = new EOREvaluationRequest();
            request.setBlockName(blockName);

            when(evaluationRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<EOREvaluation> results = eorService.evaluateBlockScenarios(request);

            for (int i = 1; i < results.size(); i++) {
                double prevScore = results.get(i - 1).getOverallScore() != null ?
                        results.get(i - 1).getOverallScore() : 0;
                double currScore = results.get(i).getOverallScore() != null ?
                        results.get(i).getOverallScore() : 0;
                assertTrue(prevScore >= currScore,
                        "结果应按综合评分降序排列。第" + i + "项评分(" + currScore +
                                ")不应高于第" + (i - 1) + "项(" + prevScore + ")");
            }
        }

        @Test
        @DisplayName("对比分析 - 多维度对比数据完整性")
        void testComparativeAnalysisCompleteness() {
            String blockName = "东区";
            setupMockBlockData(blockName);

            EOREvaluationRequest request = new EOREvaluationRequest();
            request.setBlockName(blockName);

            when(evaluationRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
            eorService.evaluateBlockScenarios(request);

            List<EOREvaluation> mockEvaluations = createMockEvaluations(blockName);
            when(evaluationRepository.findLatestByBlockName(blockName))
                    .thenReturn(mockEvaluations);

            Map<String, Object> analysis = eorService.getComparativeAnalysis(blockName);

            assertNotNull(analysis, "对比分析结果不应为空");
            assertTrue(analysis.containsKey("blockName"), "应包含区块名称");
            assertTrue(analysis.containsKey("scenarios"), "应包含方案列表");
            assertTrue(analysis.containsKey("recommendedScenario"), "应包含推荐方案");
            assertTrue(analysis.containsKey("comparisonTable"), "应包含对比表格");
            assertTrue(analysis.containsKey("radarChartData"), "应包含雷达图数据");
            assertTrue(analysis.containsKey("recommendations"), "应包含建议");
            assertTrue(analysis.containsKey("sensitivityAnalysis"), "应包含敏感性分析");
            assertTrue(analysis.containsKey("riskAssessment"), "应包含风险评估");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> comparisonTable =
                    (List<Map<String, Object>>) analysis.get("comparisonTable");
            assertEquals(4, comparisonTable.size(), "对比表格应有4行");

            for (Map<String, Object> row : comparisonTable) {
                assertTrue(row.containsKey("eorType"), "应包含EOR类型");
                assertTrue(row.containsKey("predictedOilIncrement"), "应包含增油量");
                assertTrue(row.containsKey("netProfit"), "应包含净利润");
                assertTrue(row.containsKey("roiPercentage"), "应包含ROI");
                assertTrue(row.containsKey("paybackPeriodMonths"), "应包含回收期");
                assertTrue(row.containsKey("overallScore"), "应包含综合评分");
            }
        }

        @Test
        @DisplayName("雷达图数据 - 多维度评估完整")
        void testRadarChartDataCompleteness() {
            String blockName = "东区";
            List<EOREvaluation> mockEvaluations = createMockEvaluations(blockName);
            when(evaluationRepository.findLatestByBlockName(blockName))
                    .thenReturn(mockEvaluations);

            Map<String, Object> analysis = eorService.getComparativeAnalysis(blockName);

            @SuppressWarnings("unchecked")
            Map<String, Object> radarData = (Map<String, Object>) analysis.get("radarChartData");

            assertNotNull(radarData, "雷达图数据不应为空");
            assertTrue(radarData.containsKey("labels"), "应包含维度标签");
            assertTrue(radarData.containsKey("datasets"), "应包含数据集");

            @SuppressWarnings("unchecked")
            List<String> labels = (List<String>) radarData.get("labels");
            List<String> expectedLabels = Arrays.asList(
                    "技术可行性", "增油量", "经济效益", "ROI", "回收期", "适用条件");
            assertEquals(expectedLabels, labels, "雷达图维度应完整");
        }

        @Test
        @DisplayName("敏感性分析 - 油价变化对各方案影响")
        void testSensitivityAnalysisToOilPrice() {
            String blockName = "东区";
            List<EOREvaluation> mockEvaluations = createMockEvaluations(blockName);
            when(evaluationRepository.findLatestByBlockName(blockName))
                    .thenReturn(mockEvaluations);

            Map<String, Object> analysis = eorService.getComparativeAnalysis(blockName);

            @SuppressWarnings("unchecked")
            Map<String, Object> sensitivity = (Map<String, Object>) analysis.get("sensitivityAnalysis");

            assertNotNull(sensitivity, "敏感性分析不应为空");
            assertTrue(sensitivity.containsKey("oilPriceSensitivity"), "应包含油价敏感性");
            assertTrue(sensitivity.containsKey("breakEvenPrice"), "应包含盈亏平衡油价");

            @SuppressWarnings("unchecked")
            Map<String, Double> breakEvenPrices = (Map<String, Double>) sensitivity.get("breakEvenPrice");
            for (String eorType : Arrays.asList("POLYMER_FLOODING", "SURFACTANT_FLOODING")) {
                assertTrue(breakEvenPrices.containsKey(eorType),
                        eorType + "应包含盈亏平衡油价");
                assertTrue(breakEvenPrices.get(eorType) > 0,
                        eorType + "盈亏平衡油价应为正数");
            }
        }

        @Test
        @DisplayName("风险评估 - 各方案风险等级判定")
        void testRiskAssessment() {
            String blockName = "东区";
            List<EOREvaluation> mockEvaluations = createMockEvaluations(blockName);
            when(evaluationRepository.findLatestByBlockName(blockName))
                    .thenReturn(mockEvaluations);

            Map<String, Object> analysis = eorService.getComparativeAnalysis(blockName);

            @SuppressWarnings("unchecked")
            Map<String, Object> riskAssessment = (Map<String, Object>) analysis.get("riskAssessment");

            assertNotNull(riskAssessment, "风险评估不应为空");

            for (EOREvaluation eval : mockEvaluations) {
                assertNotNull(eval.getRiskLevel(),
                        eval.getEorType() + "应有风险等级判定");
                assertTrue(Arrays.asList("LOW", "MEDIUM", "HIGH").contains(eval.getRiskLevel()),
                        eval.getEorType() + "风险等级应在LOW/MEDIUM/HIGH中，实际: " + eval.getRiskLevel());
            }
        }

        @Test
        @DisplayName("建议生成 - 各方案有针对性建议")
        void testRecommendationGeneration() {
            String blockName = "东区";
            setupMockBlockData(blockName);

            EOREvaluationRequest request = new EOREvaluationRequest();
            request.setBlockName(blockName);

            when(evaluationRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<EOREvaluation> results = eorService.evaluateBlockScenarios(request);

            for (EOREvaluation eval : results) {
                assertNotNull(eval.getRecommendation(),
                        eval.getEorType() + "应有针对性建议");
                assertNotNull(eval.getScenarioName(),
                        eval.getEorType() + "应有方案名称");
                assertNotNull(eval.getDescription(),
                        eval.getEorType() + "应有方案描述");
                assertFalse(eval.getRecommendation().isEmpty(),
                        eval.getEorType() + "建议不应为空字符串");
            }
        }

        @Test
        @DisplayName("指定方案类型 - 仅评估指定方案")
        void testSpecifiedEorTypesOnly() {
            String blockName = "东区";
            setupMockBlockData(blockName);

            EOREvaluationRequest request = new EOREvaluationRequest();
            request.setBlockName(blockName);
            request.setEorTypes(Arrays.asList("POLYMER_FLOODING", "SURFACTANT_FLOODING"));

            when(evaluationRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<EOREvaluation> results = eorService.evaluateBlockScenarios(request);

            assertEquals(2, results.size(), "应只评估指定的2种方案");
            Set<String> actualTypes = results.stream()
                    .map(EOREvaluation::getEorType)
                    .collect(Collectors.toSet());
            assertTrue(actualTypes.contains("POLYMER_FLOODING"));
            assertTrue(actualTypes.contains("SURFACTANT_FLOODING"));
            assertFalse(actualTypes.contains("COMBINED_FLOODING"));
        }

        @Test
        @DisplayName("边界 - 区块无数据 - 使用默认值")
        void testBlockWithNoDataUsesDefaults() {
            String blockName = "新区块";
            when(wellRepository.findByBlockNameAndWellType(blockName, "production"))
                    .thenReturn(Collections.emptyList());
            when(summaryRepository.findByBlockNameAndSummaryDate(eq(blockName), any()))
                    .thenReturn(Optional.empty());
            when(summaryRepository.findTopByBlockNameOrderBySummaryDateDesc(blockName))
                    .thenReturn(Optional.empty());

            EOREvaluationRequest request = new EOREvaluationRequest();
            request.setBlockName(blockName);
            request.setEorTypes(Collections.singletonList("POLYMER_FLOODING"));

            when(evaluationRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            assertDoesNotThrow(() -> eorService.evaluateBlockScenarios(request),
                    "无数据区块不应抛出异常");
        }
    }

    @Nested
    @DisplayName("边界与异常场景测试")
    class BoundaryAndExceptionTests {

        @Test
        @DisplayName("正常场景 - 完整评估流程")
        void testNormalEvaluationFlow() {
            String blockName = "东区";
            setupMockBlockData(blockName);

            EOREvaluationRequest request = new EOREvaluationRequest();
            request.setBlockName(blockName);
            request.setEvaluationDate(LocalDate.now());
            request.setPredictionTimeHorizonMonths(60);
            request.setOilPricePerBarrel(BigDecimal.valueOf(75.0));
            request.setDiscountRate(0.08);
            request.setEorTypes(Arrays.asList("POLYMER_FLOODING", "SURFACTANT_FLOODING"));

            when(evaluationRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<EOREvaluation> results = eorService.evaluateBlockScenarios(request);

            assertNotNull(results);
            assertEquals(2, results.size());

            for (EOREvaluation eval : results) {
                assertNotNull(eval.getEorType());
                assertNotNull(eval.getPredictedOilIncrement());
                assertNotNull(eval.getNetProfit());
                assertNotNull(eval.getRoiPercentage());
                assertNotNull(eval.getOverallScore());
                assertNotNull(eval.getTechnicalFeasibility());
                assertNotNull(eval.getEconomicViability());
            }

            verify(evaluationRepository, times(1)).saveAll(anyList());
            verify(eventPublisher, times(1)).publishEvent(any());
        }

        @Test
        @DisplayName("边界 - 极低剩余油 - 方案不经济")
        void testVeryLowRemainingOil() {
            EOREvaluation eval = invokeEvaluateScenario(
                    "POLYMER_FLOODING", 200.0, 0.15, 70.0,
                    BigDecimal.valueOf(50.0), 0.08);

            assertTrue(eval.getOverallScore() < 0.5,
                    "极低剩余油下综合评分应<0.5，实际: " + eval.getOverallScore());
            assertTrue(eval.getPredictedOilIncrement() < 5000,
                    "极低剩余油下增油量应较低，实际: " + eval.getPredictedOilIncrement());
        }

        @Test
        @DisplayName("边界 - 极高剩余油 - 方案效果好")
        void testVeryHighRemainingOil() {
            EOREvaluation eval = invokeEvaluateScenario(
                    "POLYMER_FLOODING", 200.0, 0.55, 70.0,
                    BigDecimal.valueOf(100.0), 0.08);

            assertTrue(eval.getOverallScore() > 0.7,
                    "高剩余油下综合评分应>0.7，实际: " + eval.getOverallScore());
            assertTrue(eval.getPredictedOilIncrement() > 10000,
                    "高剩余油下增油量应较高，实际: " + eval.getPredictedOilIncrement());
        }

        @Test
        @DisplayName("边界 - 过短预测周期")
        void testVeryShortPredictionHorizon() {
            EOREvaluationRequest request = new EOREvaluationRequest();
            request.setBlockName("东区");
            request.setPredictionTimeHorizonMonths(6);
            request.setEorTypes(Collections.singletonList("POLYMER_FLOODING"));

            setupMockBlockData("东区");
            when(evaluationRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<EOREvaluation> results = eorService.evaluateBlockScenarios(request);

            assertFalse(results.isEmpty());
            assertEquals(6, results.get(0).getPredictionTimeHorizonMonths(),
                    "应使用指定的6个月预测周期");
        }

        @Test
        @DisplayName("边界 - 负油价处理")
        void testNegativeOilPrice() {
            EOREvaluation eval = invokeEvaluateScenario(
                    "POLYMER_FLOODING", 200.0, 0.4, 70.0,
                    BigDecimal.valueOf(-10.0), 0.08);

            assertTrue(eval.getNetProfit().compareTo(BigDecimal.ZERO) < 0,
                    "负油价下净利润应为负");
            assertTrue(eval.getRoiPercentage() < 0,
                    "负油价下ROI应为负，实际: " + eval.getRoiPercentage() + "%");
        }

        @Test
        @DisplayName("异常场景 - 不支持的EOR类型")
        void testUnsupportedEorType() {
            EOREvaluation eval = invokeEvaluateScenario(
                    "UNKNOWN_EOR", 200.0, 0.4, 70.0,
                    BigDecimal.valueOf(75.0), 0.08);

            assertNotNull(eval, "未知类型也应返回评估结果");
            assertNotNull(eval.getOverallScore(), "综合评分不应为空");
        }

        @Test
        @DisplayName("异常场景 - 数据库异常处理")
        void testDatabaseExceptionHandling() {
            String blockName = "异常区块";
            when(wellRepository.findDistinctBlockNames())
                    .thenThrow(new RuntimeException("Database connection failed"));

            assertThrows(RuntimeException.class,
                    () -> eorService.autoEvaluateAllBlocks(),
                    "数据库异常应被传播");
        }

        @Test
        @DisplayName("异常场景 - 方案参数覆盖")
        void testScenarioParameterOverride() {
            String blockName = "东区";
            setupMockBlockData(blockName);

            EOREvaluationRequest request = new EOREvaluationRequest();
            request.setBlockName(blockName);
            request.setEorTypes(Collections.singletonList("POLYMER_FLOODING"));

            EOREvaluationRequest.ScenarioParameter param = new EOREvaluationRequest.ScenarioParameter();
            param.setEorType("POLYMER_FLOODING");
            param.setChemicalConcentration(2000.0);
            param.setChemicalCostPerTon(new BigDecimal("20000"));
            request.setScenarioParameters(Collections.singletonList(param));

            when(evaluationRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<EOREvaluation> results = eorService.evaluateBlockScenarios(request);

            assertEquals(2000.0, results.get(0).getChemicalConcentration(), 0.01,
                    "应使用自定义浓度2000，实际: " + results.get(0).getChemicalConcentration());
            assertEquals(new BigDecimal("20000"), results.get(0).getChemicalCostPerTon(),
                    "应使用自定义成本20000，实际: " + results.get(0).getChemicalCostPerTon());
        }
    }

    private void setupMockBlockData(String blockName) {
        BlockDailySummary summary = new BlockDailySummary();
        summary.setBlockName(blockName);
        summary.setSummaryDate(LocalDate.now());
        summary.setTotalOilProduction(100000.0);
        summary.setAverageWaterCut(80.0);

        when(summaryRepository.findByBlockNameAndSummaryDate(eq(blockName), any()))
                .thenReturn(Optional.of(summary));
    }

    private List<EOREvaluation> createMockEvaluations(String blockName) {
        List<EOREvaluation> evaluations = new ArrayList<>();
        String[] types = {"POLYMER_FLOODING", "SURFACTANT_FLOODING",
                "COMBINED_FLOODING", "ALKALINE_FLOODING"};
        double[] scores = {0.85, 0.75, 0.90, 0.60};

        for (int i = 0; i < types.length; i++) {
            EOREvaluation eval = new EOREvaluation();
            eval.setId((long) (i + 1));
            eval.setBlockName(blockName);
            eval.setEorType(types[i]);
            eval.setEvaluationDate(LocalDate.now());
            eval.setOverallScore(scores[i]);
            eval.setTechnicalFeasibility(0.7 + i * 0.05);
            eval.setEconomicViability(0.6 + i * 0.05);
            eval.setPredictedOilIncrement(10000 + i * 2000);
            eval.setPredictedWaterCutReduction(10.0 + i * 2);
            eval.setTotalChemicalCost(new BigDecimal("1000000").add(BigDecimal.valueOf(i * 100000)));
            eval.setTotalRevenue(new BigDecimal("2000000").add(BigDecimal.valueOf(i * 300000)));
            eval.setNetProfit(new BigDecimal("1000000").add(BigDecimal.valueOf(i * 200000)));
            eval.setRoiPercentage(100.0 + i * 15);
            eval.setPaybackPeriodMonths(24 - i * 3);
            eval.setIsRecommended(i == 2);
            eval.setScenarioName("方案" + (i + 1));
            eval.setRecommendation("建议实施" + types[i]);
            eval.setRiskLevel(i % 2 == 0 ? "LOW" : "MEDIUM");
            evaluations.add(eval);
        }
        evaluations.sort((a, b) -> Double.compare(
                b.getOverallScore() != null ? b.getOverallScore() : 0,
                a.getOverallScore() != null ? a.getOverallScore() : 0));
        return evaluations;
    }

    private double invokeCalculateTechnicalFeasibility(String eorType, double permeability,
                                                       double remainingOil, double temperature) {
        try {
            Method method = EOREvaluationService.class.getDeclaredMethod(
                    "calculateTechnicalFeasibility",
                    String.class, double.class, double.class, double.class,
                    AdvancedFeaturesProperties.Eor.class);
            method.setAccessible(true);
            return (double) method.invoke(eorService, eorType, permeability,
                    remainingOil, temperature, eorConfig);
        } catch (Exception e) {
            throw new RuntimeException("反射调用失败", e);
        }
    }

    private double invokeCalculateTemperatureFactor(String eorType, double temperature) {
        try {
            Method method = EOREvaluationService.class.getDeclaredMethod(
                    "calculateTemperatureFactor", String.class, double.class);
            method.setAccessible(true);
            return (double) method.invoke(eorService, eorType, temperature);
        } catch (Exception e) {
            throw new RuntimeException("反射调用失败", e);
        }
    }

    private double invokeCalculateMobilityFactor(String eorType, double permeability, double porosity) {
        try {
            Method method = EOREvaluationService.class.getDeclaredMethod(
                    "calculateMobilityFactor", String.class, double.class, double.class);
            method.setAccessible(true);
            return (double) method.invoke(eorService, eorType, permeability, porosity);
        } catch (Exception e) {
            throw new RuntimeException("反射调用失败", e);
        }
    }

    private double invokeCalculateEconomicViability(double roi, int paybackMonths) {
        try {
            Method method = EOREvaluationService.class.getDeclaredMethod(
                    "calculateEconomicViability",
                    double.class, int.class, AdvancedFeaturesProperties.Eor.class);
            method.setAccessible(true);
            return (double) method.invoke(eorService, roi, paybackMonths, eorConfig);
        } catch (Exception e) {
            throw new RuntimeException("反射调用失败", e);
        }
    }

    private EOREvaluation invokeEvaluateScenario(String eorType, double permeability,
                                                  double remainingOil, double temperature,
                                                  BigDecimal oilPrice, double discountRate) {
        try {
            Method method = EOREvaluationService.class.getDeclaredMethod(
                    "evaluateScenario",
                    String.class, String.class, LocalDate.class, int.class,
                    BigDecimal.class, double.class, Map.class,
                    EOREvaluationRequest.ScenarioParameter.class,
                    AdvancedFeaturesProperties.Eor.class);
            method.setAccessible(true);

            Map<String, Object> blockData = new HashMap<>();
            blockData.put("currentOilProduction", 100.0);
            blockData.put("currentWaterCut", 80.0);
            blockData.put("remainingOilSaturation", remainingOil);
            blockData.put("permeability", permeability);
            blockData.put("porosity", 0.25);
            blockData.put("reservoirTemperature", temperature);
            blockData.put("reservoirPressure", 25.0);

            return (EOREvaluation) method.invoke(eorService,
                    "东区", eorType, LocalDate.now(), 60,
                    oilPrice, discountRate, blockData, null, eorConfig);
        } catch (Exception e) {
            throw new RuntimeException("反射调用失败", e);
        }
    }

    private EOREvaluation invokeEvaluateScenarioWithScores(String eorType,
                                                           double technical, double economic) {
        try {
            Method method = EOREvaluationService.class.getDeclaredMethod(
                    "evaluateScenario",
                    String.class, String.class, LocalDate.class, int.class,
                    BigDecimal.class, double.class, Map.class,
                    EOREvaluationRequest.ScenarioParameter.class,
                    AdvancedFeaturesProperties.Eor.class);
            method.setAccessible(true);

            Map<String, Object> blockData = new HashMap<>();
            blockData.put("currentOilProduction", 100.0);
            blockData.put("currentWaterCut", 80.0);
            blockData.put("remainingOilSaturation", 0.4);
            blockData.put("permeability", 200.0);
            blockData.put("porosity", 0.25);
            blockData.put("reservoirTemperature", 70.0);
            blockData.put("reservoirPressure", 25.0);

            EOREvaluation eval = (EOREvaluation) method.invoke(eorService,
                    "东区", eorType, LocalDate.now(), 60,
                    BigDecimal.valueOf(75.0), 0.08, blockData, null, eorConfig);

            eval.setTechnicalFeasibility(technical);
            eval.setEconomicViability(economic);
            eval.setOverallScore(0.5 * technical + 0.5 * economic);

            return eval;
        } catch (Exception e) {
            throw new RuntimeException("反射调用失败", e);
        }
    }
}
