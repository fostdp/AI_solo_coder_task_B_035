package com.smart.oilfield.profile.service;

import com.smart.oilfield.common.config.AdvancedFeaturesProperties;
import com.smart.oilfield.common.dto.ProfileAdjustmentRequest;
import com.smart.oilfield.common.entity.InjectionProfile;
import com.smart.oilfield.common.entity.Well;
import com.smart.oilfield.common.repository.InjectionProfileRepository;
import com.smart.oilfield.common.repository.WellRepository;
import com.smart.oilfield.profile.ProfileAdjusterApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = ProfileAdjusterApplication.class)
@ExtendWith(MockitoExtension.class)
@DisplayName("注水剖面调整测试 - InjectionProfileOptimizer")
class InjectionProfileOptimizerTest {

    @Mock
    private InjectionProfileRepository profileRepository;

    @Mock
    private WellRepository wellRepository;

    @Mock
    private AdvancedFeaturesProperties properties;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private InjectionProfileOptimizer optimizer;

    private AdvancedFeaturesProperties.Profile profileConfig;
    private AdvancedFeaturesProperties.SmartWaterController swcConfig;

    @BeforeEach
    void setUp() {
        profileConfig = new AdvancedFeaturesProperties.Profile();
        profileConfig.setEnabled(true);
        profileConfig.setEnableAutoAdjustment(true);
        profileConfig.setMaxAdjustmentPercentage(30.0);
        profileConfig.setMinLayerInjectionVolume(5.0);
        profileConfig.setPermeabilityWeight(0.4);
        profileConfig.setThicknessWeight(0.3);
        profileConfig.setAbsorptionWeight(0.3);
        profileConfig.setStartingPressurePenaltyFactor(0.1);
        profileConfig.setSkinFactorPenaltyFactor(0.1);
        profileConfig.setMaintainTotalVolume(true);
        profileConfig.setDefaultLayerCount(5);

        swcConfig = new AdvancedFeaturesProperties.SmartWaterController();
        swcConfig.setEnabled(true);
        swcConfig.setEnableSimulation(true);
        swcConfig.setBaseUrl("http://localhost:8081");
        swcConfig.setApiKey("test-api-key");
        swcConfig.setMaxRetryAttempts(3);
        swcConfig.setRetryDelayMs(100);
        swcConfig.setSimulationDelayMs(10);
        swcConfig.setSimulationSuccessRate(0.95);

        when(properties.getProfile()).thenReturn(profileConfig);
        when(properties.getSmartWaterController()).thenReturn(swcConfig);
    }

    @Nested
    @DisplayName("分层注水量计算最优性测试")
    class OptimalLayerAllocationTests {

        @Test
        @DisplayName("高渗层优先分配 - 验证高渗透率层获得更多注水量")
        void testHighPermeabilityLayerGetsMoreWater() {
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 300.0, 10.0, 0.6, 10.0, 5.0, 30.0),
                    createMockProfile(2, 100.0, 10.0, 0.6, 10.0, 5.0, 30.0),
                    createMockProfile(3, 50.0, 10.0, 0.6, 10.0, 5.0, 30.0)
            );

            List<InjectionProfile> result = invokeCalculateOptimalAllocation(
                    profiles, 90.0, 90.0, 30.0, new HashMap<>());

            assertNotNull(result, "调配结果不应为空");
            assertEquals(3, result.size(), "应返回3个层的调配结果");

            double layer1Volume = result.get(0).getSuggestedInjectionVolume();
            double layer2Volume = result.get(1).getSuggestedInjectionVolume();
            double layer3Volume = result.get(2).getSuggestedInjectionVolume();

            assertTrue(layer1Volume > layer2Volume,
                    "高渗层(300mD)注水量应大于中渗层(100mD)。层1: " + layer1Volume + "，层2: " + layer2Volume);
            assertTrue(layer2Volume > layer3Volume,
                    "中渗层(100mD)注水量应大于低渗层(50mD)。层2: " + layer2Volume + "，层3: " + layer3Volume);

            double totalSuggested = result.stream()
                    .mapToDouble(InjectionProfile::getSuggestedInjectionVolume)
                    .sum();
            assertEquals(90.0, totalSuggested, 0.01,
                    "总注水量应保持90m³，实际: " + totalSuggested);
        }

        @Test
        @DisplayName("厚层优先分配 - 验证厚度影响注水量分配")
        void testThickLayerGetsMoreWater() {
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 100.0, 20.0, 0.5, 10.0, 5.0, 30.0),
                    createMockProfile(2, 100.0, 10.0, 0.5, 10.0, 5.0, 30.0),
                    createMockProfile(3, 100.0, 5.0, 0.5, 10.0, 5.0, 30.0)
            );

            List<InjectionProfile> result = invokeCalculateOptimalAllocation(
                    profiles, 90.0, 90.0, 30.0, new HashMap<>());

            double layer1Volume = result.get(0).getSuggestedInjectionVolume();
            double layer2Volume = result.get(1).getSuggestedInjectionVolume();
            double layer3Volume = result.get(2).getSuggestedInjectionVolume();

            assertTrue(layer1Volume > layer2Volume,
                    "厚层(20m)注水量应大于中层(10m)。层1: " + layer1Volume + "，层2: " + layer2Volume);
            assertTrue(layer2Volume > layer3Volume,
                    "中层(10m)注水量应大于薄层(5m)。层2: " + layer2Volume + "，层3: " + layer3Volume);
        }

        @Test
        @DisplayName("高吸水比层优先 - 验证吸水比影响注水量分配")
        void testHighAbsorptionLayerGetsMoreWater() {
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 100.0, 10.0, 0.8, 10.0, 5.0, 30.0),
                    createMockProfile(2, 100.0, 10.0, 0.5, 10.0, 5.0, 30.0),
                    createMockProfile(3, 100.0, 10.0, 0.2, 10.0, 5.0, 30.0)
            );

            List<InjectionProfile> result = invokeCalculateOptimalAllocation(
                    profiles, 90.0, 90.0, 30.0, new HashMap<>());

            double layer1Volume = result.get(0).getSuggestedInjectionVolume();
            double layer2Volume = result.get(1).getSuggestedInjectionVolume();
            double layer3Volume = result.get(2).getSuggestedInjectionVolume();

            assertTrue(layer1Volume > layer2Volume,
                    "高吸水比层(0.8)注水量应大于中吸水比层(0.5)。层1: " + layer1Volume + "，层2: " + layer2Volume);
            assertTrue(layer2Volume > layer3Volume,
                    "中吸水比层(0.5)注水量应大于低吸水比层(0.2)。层2: " + layer2Volume + "，层3: " + layer3Volume);
        }

        @Test
        @DisplayName("惩罚机制验证 - 高启动压力层减少配水量")
        void testHighStartingPressurePenalty() {
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 100.0, 10.0, 0.5, 5.0, 5.0, 30.0),
                    createMockProfile(2, 100.0, 10.0, 0.5, 25.0, 5.0, 30.0)
            );

            List<InjectionProfile> result = invokeCalculateOptimalAllocation(
                    profiles, 60.0, 60.0, 30.0, new HashMap<>());

            double normalLayerVolume = result.get(0).getSuggestedInjectionVolume();
            double highPressureLayerVolume = result.get(1).getSuggestedInjectionVolume();

            assertTrue(normalLayerVolume > highPressureLayerVolume,
                    "正常启动压力层(5MPa)注水量应高于高启动压力层(25MPa)。" +
                            "正常层: " + normalLayerVolume + "，高压层: " + highPressureLayerVolume);
        }

        @Test
        @DisplayName("惩罚机制验证 - 高表皮系数层减少配水量")
        void testHighSkinFactorPenalty() {
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 100.0, 10.0, 0.5, 10.0, 1.0, 30.0),
                    createMockProfile(2, 100.0, 10.0, 0.5, 10.0, 8.0, 30.0)
            );

            List<InjectionProfile> result = invokeCalculateOptimalAllocation(
                    profiles, 60.0, 60.0, 30.0, new HashMap<>());

            double normalLayerVolume = result.get(0).getSuggestedInjectionVolume();
            double highSkinLayerVolume = result.get(1).getSuggestedInjectionVolume();

            assertTrue(normalLayerVolume > highSkinLayerVolume,
                    "低表皮系数层(1.0)注水量应高于高表皮系数层(8.0)。" +
                            "正常层: " + normalLayerVolume + "，高表皮层: " + highSkinLayerVolume);
        }

        @Test
        @DisplayName("边界 - 最大调整幅度约束 - 验证不超过±30%")
        void testMaxAdjustmentConstraint() {
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 500.0, 20.0, 0.8, 5.0, 1.0, 30.0),
                    createMockProfile(2, 10.0, 2.0, 0.1, 25.0, 8.0, 30.0)
            );

            List<InjectionProfile> result = invokeCalculateOptimalAllocation(
                    profiles, 60.0, 60.0, 30.0, new HashMap<>());

            double layer1Current = profiles.get(0).getCurrentInjectionVolume();
            double layer1Suggested = result.get(0).getSuggestedInjectionVolume();
            double layer1AdjustmentPct = (layer1Suggested - layer1Current) / layer1Current;

            double layer2Current = profiles.get(1).getCurrentInjectionVolume();
            double layer2Suggested = result.get(1).getSuggestedInjectionVolume();
            double layer2AdjustmentPct = (layer2Suggested - layer2Current) / layer2Current;

            assertTrue(Math.abs(layer1AdjustmentPct) <= 0.301,
                    "层1调整幅度不应超过±30%，实际: " + (layer1AdjustmentPct * 100) + "%");
            assertTrue(Math.abs(layer2AdjustmentPct) <= 0.301,
                    "层2调整幅度不应超过±30%，实际: " + (layer2AdjustmentPct * 100) + "%");
        }

        @Test
        @DisplayName("边界 - 最小注水量约束 - 验证不低于5m³")
        void testMinInjectionVolumeConstraint() {
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 500.0, 20.0, 0.8, 5.0, 1.0, 50.0),
                    createMockProfile(2, 10.0, 2.0, 0.1, 25.0, 8.0, 5.0)
            );

            List<InjectionProfile> result = invokeCalculateOptimalAllocation(
                    profiles, 55.0, 55.0, 30.0, new HashMap<>());

            double layer2Suggested = result.get(1).getSuggestedInjectionVolume();

            assertTrue(layer2Suggested >= 5.0,
                    "差层注水量不应低于最小约束5m³，实际: " + layer2Suggested);
        }

        @Test
        @DisplayName("边界 - 总水量保持 - 验证总水量平衡")
        void testTotalVolumeMaintenance() {
            int[] layerCounts = {2, 5, 10, 20};
            double[] totalVolumes = {50.0, 100.0, 200.0, 500.0};

            for (int layerCount : layerCounts) {
                for (double totalVolume : totalVolumes) {
                    List<InjectionProfile> profiles = createMockProfiles(layerCount, totalVolume);

                    List<InjectionProfile> result = invokeCalculateOptimalAllocation(
                            profiles, totalVolume, totalVolume, 30.0, new HashMap<>());

                    double totalSuggested = result.stream()
                            .mapToDouble(InjectionProfile::getSuggestedInjectionVolume)
                            .sum();

                    assertEquals(totalVolume, totalSuggested, 0.01,
                            layerCount + "层, 目标" + totalVolume + "m³时总水量应平衡，实际: " + totalSuggested);
                }
            }
        }

        @Test
        @DisplayName("层间矛盾改善 - 验证注采对应关系改善")
        void testLayerContradictionImprovement() {
            List<InjectionProfile> initialProfiles = Arrays.asList(
                    createMockProfile(1, 300.0, 10.0, 0.7, 8.0, 2.0, 30.0),
                    createMockProfile(2, 200.0, 10.0, 0.6, 8.0, 2.0, 30.0),
                    createMockProfile(3, 100.0, 10.0, 0.4, 8.0, 2.0, 30.0),
                    createMockProfile(4, 50.0, 10.0, 0.2, 15.0, 5.0, 30.0)
            );

            double initialContradiction = calculateLayerContradiction(initialProfiles);

            List<InjectionProfile> adjustedProfiles = invokeCalculateOptimalAllocation(
                    initialProfiles, 120.0, 120.0, 30.0, new HashMap<>());

            double adjustedContradiction = calculateLayerContradiction(adjustedProfiles);

            assertTrue(adjustedContradiction < initialContradiction,
                    "调配后层间矛盾应减小。初始矛盾指数: " + initialContradiction +
                            "，调配后矛盾指数: " + adjustedContradiction);
        }

        @Test
        @DisplayName("人工干预 - 验证层级强制配水量")
        void testLayerOverride() {
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 300.0, 10.0, 0.6, 10.0, 5.0, 30.0),
                    createMockProfile(2, 100.0, 10.0, 0.6, 10.0, 5.0, 30.0),
                    createMockProfile(3, 50.0, 10.0, 0.6, 10.0, 5.0, 30.0)
            );

            Map<Integer, Double> overrides = new HashMap<>();
            overrides.put(2, 40.0);

            List<InjectionProfile> result = invokeCalculateOptimalAllocation(
                    profiles, 90.0, 90.0, 30.0, overrides);

            assertEquals(40.0, result.get(1).getSuggestedInjectionVolume(), 0.01,
                    "层2应严格按照人工干预值40m³配水，实际: " + result.get(1).getSuggestedInjectionVolume());

            double totalSuggested = result.stream()
                    .mapToDouble(InjectionProfile::getSuggestedInjectionVolume)
                    .sum();
            assertEquals(90.0, totalSuggested, 0.01,
                    "人工干预后总水量仍应保持90m³，实际: " + totalSuggested);
        }

        @Test
        @DisplayName("综合评分验证 - 验证权重公式正确性")
        void testCompositeScoreCalculation() {
            InjectionProfile profile = createMockProfile(1, 250.0, 15.0, 0.7, 10.0, 3.0, 30.0);

            double score = invokeCalculateLayerAllocationScore(profile);

            double expectedNormalizedPerm = Math.min(250.0 / 500.0, 1.0);
            double expectedNormalizedThick = Math.min(15.0 / 50.0, 1.0);
            double expectedNormalizedAbs = Math.max(Math.min(0.7, 1.0), 0.0);
            double expectedPressurePenalty = Math.max(0, 10.0 / 30.0) * 0.1;
            double expectedSkinPenalty = Math.max(0, 3.0 / 10.0) * 0.1;

            double expectedScore = 0.4 * expectedNormalizedPerm +
                    0.3 * expectedNormalizedThick +
                    0.3 * expectedNormalizedAbs -
                    expectedPressurePenalty -
                    expectedSkinPenalty;
            expectedScore = Math.max(expectedScore, 0.01);

            assertEquals(expectedScore, score, 0.0001,
                    "综合评分应正确应用权重公式。预期: " + expectedScore + "，实际: " + score);
        }

        @Test
        @DisplayName("异常场景 - 单井单层调配")
        void testSingleLayerAllocation() {
            List<InjectionProfile> profiles = Collections.singletonList(
                    createMockProfile(1, 100.0, 10.0, 0.5, 10.0, 5.0, 50.0)
            );

            List<InjectionProfile> result = invokeCalculateOptimalAllocation(
                    profiles, 50.0, 60.0, 30.0, new HashMap<>());

            assertEquals(1, result.size(), "单层调配应返回1个结果");
            assertEquals(60.0, result.get(0).getSuggestedInjectionVolume(), 0.01,
                    "单层调配应达到目标值60m³");
            assertEquals("INCREASE", result.get(0).getAdjustmentDirection(),
                    "增注应标记为INCREASE");
        }

        @Test
        @DisplayName("异常场景 - 所有层参数相同 - 验证均匀分配")
        void testIdenticalLayersEqualAllocation() {
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 100.0, 10.0, 0.5, 10.0, 5.0, 30.0),
                    createMockProfile(2, 100.0, 10.0, 0.5, 10.0, 5.0, 30.0),
                    createMockProfile(3, 100.0, 10.0, 0.5, 10.0, 5.0, 30.0)
            );

            List<InjectionProfile> result = invokeCalculateOptimalAllocation(
                    profiles, 90.0, 90.0, 30.0, new HashMap<>());

            double layer1Vol = result.get(0).getSuggestedInjectionVolume();
            double layer2Vol = result.get(1).getSuggestedInjectionVolume();
            double layer3Vol = result.get(2).getSuggestedInjectionVolume();

            assertEquals(layer1Vol, layer2Vol, 0.01,
                    "参数相同的层应得到相同的分配量。层1: " + layer1Vol + "，层2: " + layer2Vol);
            assertEquals(layer2Vol, layer3Vol, 0.01,
                    "参数相同的层应得到相同的分配量。层2: " + layer2Vol + "，层3: " + layer3Vol);
            assertEquals(30.0, layer1Vol, 0.01,
                    "每层应分配30m³，实际: " + layer1Vol);
        }
    }

    @Nested
    @DisplayName("智能配水器指令一致性测试")
    class SmartWaterControllerTests {

        @Test
        @DisplayName("模拟模式 - 指令下发与反馈一致性")
        void testSimulationModeCommandConsistency() {
            String wellId = "INJ-001";
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 300.0, 10.0, 0.6, 10.0, 5.0, 30.0),
                    createMockProfile(2, 100.0, 10.0, 0.6, 10.0, 5.0, 30.0)
            );

            swcConfig.setEnableSimulation(true);
            swcConfig.setSimulationSuccessRate(1.0);

            boolean result = invokeExecuteSmartWaterController(wellId, profiles);

            assertTrue(result, "成功率100%时配水器执行应成功");
        }

        @Test
        @DisplayName("模拟模式 - 失败场景处理")
        void testSimulationModeFailureHandling() {
            String wellId = "INJ-001";
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 300.0, 10.0, 0.6, 10.0, 5.0, 30.0)
            );

            swcConfig.setEnableSimulation(true);
            swcConfig.setSimulationSuccessRate(0.0);

            boolean result = invokeExecuteSmartWaterController(wellId, profiles);

            assertFalse(result, "成功率0%时配水器执行应失败");
        }

        @Test
        @DisplayName("真实模式 - 指令格式验证")
        void testRealModeCommandFormat() {
            String wellId = "INJ-001";
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 300.0, 10.0, 0.6, 10.0, 5.0, 30.0),
                    createMockProfile(2, 100.0, 10.0, 0.6, 10.0, 5.0, 30.0)
            );
            profiles.get(0).setSuggestedInjectionVolume(45.0);
            profiles.get(1).setSuggestedInjectionVolume(45.0);

            swcConfig.setEnableSimulation(false);

            Map<String, Object> successResponse = new HashMap<>();
            successResponse.put("success", true);
            successResponse.put("message", "OK");

            when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                    .thenReturn(successResponse);

            boolean result = invokeExecuteSmartWaterController(wellId, profiles);

            assertTrue(result, "成功响应时应返回true");

            verify(restTemplate, times(1)).postForObject(
                    eq(swcConfig.getBaseUrl() + "/wells/" + wellId + "/adjust"),
                    any(),
                    eq(Map.class)
            );
        }

        @Test
        @DisplayName("重试机制 - 验证失败时自动重试")
        void testRetryMechanism() {
            String wellId = "INJ-001";
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 300.0, 10.0, 0.6, 10.0, 5.0, 30.0)
            );
            profiles.get(0).setSuggestedInjectionVolume(30.0);

            swcConfig.setEnableSimulation(false);
            swcConfig.setMaxRetryAttempts(3);
            swcConfig.setRetryDelayMs(10);

            Map<String, Object> successResponse = new HashMap<>();
            successResponse.put("success", true);

            when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                    .thenThrow(new RuntimeException("Connection refused"))
                    .thenThrow(new RuntimeException("Timeout"))
                    .thenReturn(successResponse);

            boolean result = invokeExecuteSmartWaterController(wellId, profiles);

            assertTrue(result, "第三次重试成功时应返回true");
            verify(restTemplate, times(3)).postForObject(anyString(), any(), eq(Map.class));
        }

        @Test
        @DisplayName("重试机制 - 验证重试耗尽后返回失败")
        void testRetryExhaustion() {
            String wellId = "INJ-001";
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 300.0, 10.0, 0.6, 10.0, 5.0, 30.0)
            );
            profiles.get(0).setSuggestedInjectionVolume(30.0);

            swcConfig.setEnableSimulation(false);
            swcConfig.setMaxRetryAttempts(3);
            swcConfig.setRetryDelayMs(10);

            when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                    .thenThrow(new RuntimeException("Service unavailable"));

            boolean result = invokeExecuteSmartWaterController(wellId, profiles);

            assertFalse(result, "重试耗尽后应返回false");
            verify(restTemplate, times(3)).postForObject(anyString(), any(), eq(Map.class));
        }

        @Test
        @DisplayName("异常场景 - 服务端返回错误")
        void testServerErrorResponse() {
            String wellId = "INJ-001";
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 300.0, 10.0, 0.6, 10.0, 5.0, 30.0)
            );
            profiles.get(0).setSuggestedInjectionVolume(30.0);

            swcConfig.setEnableSimulation(false);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Invalid parameters");

            when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                    .thenReturn(errorResponse);

            boolean result = invokeExecuteSmartWaterController(wellId, profiles);

            assertFalse(result, "服务端返回错误时应返回false");
        }

        @Test
        @DisplayName("异常场景 - 空响应处理")
        void testNullResponseHandling() {
            String wellId = "INJ-001";
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 300.0, 10.0, 0.6, 10.0, 5.0, 30.0)
            );
            profiles.get(0).setSuggestedInjectionVolume(30.0);

            swcConfig.setEnableSimulation(false);

            when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                    .thenReturn(null);

            boolean result = invokeExecuteSmartWaterController(wellId, profiles);

            assertFalse(result, "空响应应返回false");
        }

        @Test
        @DisplayName("状态更新 - 验证执行成功后状态标记")
        void testSuccessStatusUpdate() {
            String wellId = "INJ-001";
            ProfileAdjustmentRequest request = new ProfileAdjustmentRequest();
            request.setWellId(wellId);
            request.setProfileDate(LocalDate.now());
            request.setMaintainTotalVolume(true);
            request.setExecuteAutoAdjustment(true);

            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 300.0, 10.0, 0.6, 10.0, 5.0, 30.0),
                    createMockProfile(2, 100.0, 10.0, 0.6, 10.0, 5.0, 30.0)
            );

            when(profileRepository.findByWellIdAndProfileDateOrderByLayerNumber(eq(wellId), any()))
                    .thenReturn(profiles);
            when(profileRepository.sumCurrentInjectionByWellAndDate(eq(wellId), any()))
                    .thenReturn(60.0);
            when(profileRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            swcConfig.setEnableSimulation(true);
            swcConfig.setSimulationSuccessRate(1.0);

            List<InjectionProfile> result = optimizer.adjustWellProfile(request);

            for (InjectionProfile profile : result) {
                assertEquals("EXECUTED", profile.getAllocationStatus(),
                        "执行成功后状态应为EXECUTED");
                assertTrue(profile.getAdjustmentSuccess(),
                        "执行成功标志应为true");
                assertNotNull(profile.getLastAdjustmentTime(),
                        "执行时间不应为空");
            }
        }

        @Test
        @DisplayName("状态更新 - 验证执行失败后状态标记")
        void testFailureStatusUpdate() {
            String wellId = "INJ-001";
            ProfileAdjustmentRequest request = new ProfileAdjustmentRequest();
            request.setWellId(wellId);
            request.setProfileDate(LocalDate.now());
            request.setMaintainTotalVolume(true);
            request.setExecuteAutoAdjustment(true);

            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 300.0, 10.0, 0.6, 10.0, 5.0, 30.0)
            );

            when(profileRepository.findByWellIdAndProfileDateOrderByLayerNumber(eq(wellId), any()))
                    .thenReturn(profiles);
            when(profileRepository.sumCurrentInjectionByWellAndDate(eq(wellId), any()))
                    .thenReturn(30.0);
            when(profileRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            swcConfig.setEnableSimulation(true);
            swcConfig.setSimulationSuccessRate(0.0);

            List<InjectionProfile> result = optimizer.adjustWellProfile(request);

            assertEquals("PENDING", result.get(0).getAllocationStatus(),
                    "执行失败后状态应为PENDING");
            assertFalse(result.get(0).getAdjustmentSuccess(),
                    "执行成功标志应为false");
        }
    }

    @Nested
    @DisplayName("层间矛盾改善效果测试")
    class LayerContradictionImprovementTests {

        @Test
        @DisplayName("极端层间差异 - 验证矛盾显著改善")
        void testExtremeLayerContradiction() {
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 500.0, 20.0, 0.9, 5.0, 1.0, 20.0),
                    createMockProfile(2, 10.0, 2.0, 0.1, 25.0, 9.0, 50.0)
            );

            double initialContradiction = calculateLayerContradiction(profiles);

            List<InjectionProfile> adjusted = invokeCalculateOptimalAllocation(
                    profiles, 70.0, 70.0, 30.0, new HashMap<>());

            double adjustedContradiction = calculateLayerContradiction(adjusted);

            double improvementRate = (initialContradiction - adjustedContradiction) / initialContradiction * 100;

            assertTrue(improvementRate > 20.0,
                    "极端层间差异应获得>20%的改善。改善率: " + improvementRate + "%");

            assertTrue(adjusted.get(0).getSuggestedInjectionVolume() > adjusted.get(1).getSuggestedInjectionVolume(),
                    "高渗层应获得更多注水。层高渗: " + adjusted.get(0).getSuggestedInjectionVolume() +
                            "，低渗: " + adjusted.get(1).getSuggestedInjectionVolume());
        }

        @Test
        @DisplayName("吸水剖面不均 - 验证均衡改善")
        void testUnevenAbsorptionProfile() {
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 100.0, 10.0, 0.8, 8.0, 3.0, 35.0),
                    createMockProfile(2, 100.0, 10.0, 0.5, 8.0, 3.0, 30.0),
                    createMockProfile(3, 100.0, 10.0, 0.2, 8.0, 3.0, 25.0)
            );

            double initialStdDev = calculateInjectionStdDev(profiles);
            double initialWaterStdDev = calculateAbsorptionStdDev(profiles);

            List<InjectionProfile> adjusted = invokeCalculateOptimalAllocation(
                    profiles, 90.0, 90.0, 30.0, new HashMap<>());

            double adjustedStdDev = calculateInjectionStdDev(adjusted);
            double adjustedWaterStdDev = calculateAbsorptionStdDev(adjusted);

            double absorptionImprovement = (initialWaterStdDev - adjustedWaterStdDev) / initialWaterStdDev * 100;

            assertTrue(adjustedWaterStdDev < initialWaterStdDev,
                    "调配后吸水剖面标准差应减小。初始: " + initialWaterStdDev + "，调配后: " + adjustedWaterStdDev);

            assertTrue(absorptionImprovement > 10.0,
                    "吸水剖面不均改善率应>10%，实际: " + absorptionImprovement + "%");
        }

        @Test
        @DisplayName("单层突进 - 验证剖面调整抑制突进")
        void testSingleLayerBreakthrough() {
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 400.0, 15.0, 0.95, 6.0, 2.0, 60.0),
                    createMockProfile(2, 150.0, 10.0, 0.6, 10.0, 4.0, 25.0),
                    createMockProfile(3, 80.0, 8.0, 0.3, 12.0, 6.0, 15.0)
            );

            double initialMaxRatio = profiles.stream()
                    .mapToDouble(p -> p.getCurrentInjectionVolume() != null ? p.getCurrentInjectionVolume() : 0)
                    .max().orElse(0) /
                    profiles.stream()
                            .mapToDouble(p -> p.getCurrentInjectionVolume() != null ? p.getCurrentInjectionVolume() : 0)
                            .min().orElse(1);

            List<InjectionProfile> adjusted = invokeCalculateOptimalAllocation(
                    profiles, 100.0, 100.0, 30.0, new HashMap<>());

            double adjustedMaxRatio = adjusted.stream()
                    .mapToDouble(p -> p.getSuggestedInjectionVolume() != null ? p.getSuggestedInjectionVolume() : 0)
                    .max().orElse(0) /
                    adjusted.stream()
                            .mapToDouble(p -> p.getSuggestedInjectionVolume() != null ? p.getSuggestedInjectionVolume() : 0)
                            .min().orElse(1);

            assertTrue(adjustedMaxRatio < initialMaxRatio,
                    "调配后层间最大/最小注水量比应减小。初始比: " + initialMaxRatio +
                            "，调配后比: " + adjustedMaxRatio);

            double breakthroughLayerAdjustment = adjusted.get(0).getAdjustmentAmount();
            assertTrue(breakthroughLayerAdjustment < 0,
                    "突进层应减注。调整量: " + breakthroughLayerAdjustment);
        }

        @Test
        @DisplayName("调配方向正确性 - 验证高渗减注、低渗增注")
        void testAdjustmentDirectionCorrectness() {
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 500.0, 20.0, 0.9, 5.0, 1.0, 60.0),
                    createMockProfile(2, 30.0, 3.0, 0.15, 20.0, 7.0, 20.0)
            );

            List<InjectionProfile> adjusted = invokeCalculateOptimalAllocation(
                    profiles, 80.0, 80.0, 30.0, new HashMap<>());

            double layer1Adjustment = adjusted.get(0).getAdjustmentAmount();
            double layer2Adjustment = adjusted.get(1).getAdjustmentAmount();

            assertTrue(layer1Adjustment < 0,
                    "高渗厚层应减注。调整量: " + layer1Adjustment);
            assertTrue(layer2Adjustment > 0,
                    "低渗薄层应增注。调整量: " + layer2Adjustment);

            assertEquals("DECREASE", adjusted.get(0).getAdjustmentDirection(),
                    "高渗层调配方向应为DECREASE");
            assertEquals("INCREASE", adjusted.get(1).getAdjustmentDirection(),
                    "低渗层调配方向应为INCREASE");
        }

        @Test
        @DisplayName("均衡度验证 - 验证吸水指数均衡度提升")
        void testAbsorptionIndexBalance() {
            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 200.0, 10.0, 0.7, 8.0, 2.0, 25.0),
                    createMockProfile(2, 150.0, 10.0, 0.6, 10.0, 3.0, 25.0),
                    createMockProfile(3, 100.0, 10.0, 0.5, 12.0, 4.0, 25.0),
                    createMockProfile(4, 50.0, 10.0, 0.3, 15.0, 6.0, 25.0)
            );

            double initialBalance = calculateAbsorptionBalance(profiles);

            List<InjectionProfile> adjusted = invokeCalculateOptimalAllocation(
                    profiles, 100.0, 100.0, 30.0, new HashMap<>());

            double adjustedBalance = calculateAbsorptionBalance(adjusted);

            assertTrue(adjustedBalance > initialBalance,
                    "调配后吸水指数均衡度应提升。初始: " + initialBalance + "，调配后: " + adjustedBalance);
        }
    }

    @Nested
    @DisplayName("边界与异常场景集成测试")
    class BoundaryAndExceptionTests {

        @Test
        @DisplayName("正常场景 - 完整剖面调整流程")
        void testNormalProfileAdjustmentFlow() {
            String wellId = "INJ-001";
            ProfileAdjustmentRequest request = new ProfileAdjustmentRequest();
            request.setWellId(wellId);
            request.setProfileDate(LocalDate.now());
            request.setMaintainTotalVolume(true);
            request.setExecuteAutoAdjustment(true);
            request.setMaxAdjustmentPercentage(30.0);

            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 300.0, 10.0, 0.6, 10.0, 5.0, 30.0),
                    createMockProfile(2, 100.0, 10.0, 0.6, 10.0, 5.0, 30.0)
            );

            when(profileRepository.findByWellIdAndProfileDateOrderByLayerNumber(eq(wellId), any()))
                    .thenReturn(profiles);
            when(profileRepository.sumCurrentInjectionByWellAndDate(eq(wellId), any()))
                    .thenReturn(60.0);
            when(profileRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            swcConfig.setEnableSimulation(true);
            swcConfig.setSimulationSuccessRate(1.0);

            List<InjectionProfile> result = optimizer.adjustWellProfile(request);

            assertNotNull(result, "调整结果不应为空");
            assertEquals(2, result.size(), "应返回2个层的调整结果");

            for (InjectionProfile profile : result) {
                assertNotNull(profile.getSuggestedInjectionVolume(), "建议注水量不应为空");
                assertNotNull(profile.getAdjustmentDirection(), "调整方向不应为空");
                assertTrue(profile.getSuggestedInjectionVolume() >= 5.0,
                        "建议注水量不应低于最小值");
            }

            double totalSuggested = result.stream()
                    .mapToDouble(InjectionProfile::getSuggestedInjectionVolume)
                    .sum();
            assertEquals(60.0, totalSuggested, 0.01, "总注水量应保持60m³");

            verify(eventPublisher, times(1)).publishEvent(any());
        }

        @Test
        @DisplayName("边界场景 - 无现有剖面自动生成")
        void testNoExistingProfilesAutoGeneration() {
            String wellId = "NEW-INJ-001";
            ProfileAdjustmentRequest request = new ProfileAdjustmentRequest();
            request.setWellId(wellId);
            request.setProfileDate(LocalDate.now());
            request.setMaintainTotalVolume(true);
            request.setExecuteAutoAdjustment(false);

            when(profileRepository.findByWellIdAndProfileDateOrderByLayerNumber(eq(wellId), any()))
                    .thenReturn(Collections.emptyList());
            when(profileRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<InjectionProfile> result = optimizer.adjustWellProfile(request);

            assertNotNull(result, "应返回自动生成的剖面");
            assertEquals(5, result.size(), "默认应生成5个层的剖面");

            for (InjectionProfile profile : result) {
                assertNotNull(profile.getLayerNumber(), "层号不应为空");
                assertNotNull(profile.getPermeability(), "渗透率不应为空");
                assertNotNull(profile.getCurrentInjectionVolume(), "当前注水量不应为空");
                assertNotNull(profile.getSuggestedInjectionVolume(), "建议注水量不应为空");
            }
        }

        @Test
        @DisplayName("边界场景 - 零注水量井处理")
        void testZeroInjectionWell() {
            String wellId = "INJ-ZERO";
            ProfileAdjustmentRequest request = new ProfileAdjustmentRequest();
            request.setWellId(wellId);
            request.setProfileDate(LocalDate.now());
            request.setMaintainTotalVolume(true);
            request.setExecuteAutoAdjustment(false);

            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 100.0, 10.0, 0.5, 10.0, 5.0, 0.0),
                    createMockProfile(2, 100.0, 10.0, 0.5, 10.0, 5.0, 0.0)
            );

            when(profileRepository.findByWellIdAndProfileDateOrderByLayerNumber(eq(wellId), any()))
                    .thenReturn(profiles);
            when(profileRepository.sumCurrentInjectionByWellAndDate(eq(wellId), any()))
                    .thenReturn(0.0);
            when(profileRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            assertDoesNotThrow(() -> optimizer.adjustWellProfile(request),
                    "零注水量井不应抛出异常");
        }

        @Test
        @DisplayName("异常场景 - 数据库异常处理")
        void testDatabaseExceptionHandling() {
            String wellId = "INJ-ERR";
            ProfileAdjustmentRequest request = new ProfileAdjustmentRequest();
            request.setWellId(wellId);
            request.setProfileDate(LocalDate.now());

            when(profileRepository.findByWellIdAndProfileDateOrderByLayerNumber(eq(wellId), any()))
                    .thenThrow(new RuntimeException("Database connection failed"));

            assertThrows(RuntimeException.class,
                    () -> optimizer.adjustWellProfile(request),
                    "数据库异常应被捕获并重新抛出");
        }

        @Test
        @DisplayName("异常场景 - 目标总水量为负")
        void testNegativeTargetVolume() {
            String wellId = "INJ-NEG";
            ProfileAdjustmentRequest request = new ProfileAdjustmentRequest();
            request.setWellId(wellId);
            request.setProfileDate(LocalDate.now());
            request.setTotalTargetVolume(-100.0);
            request.setExecuteAutoAdjustment(false);

            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 100.0, 10.0, 0.5, 10.0, 5.0, 30.0),
                    createMockProfile(2, 100.0, 10.0, 0.5, 10.0, 5.0, 30.0)
            );

            when(profileRepository.findByWellIdAndProfileDateOrderByLayerNumber(eq(wellId), any()))
                    .thenReturn(profiles);
            when(profileRepository.sumCurrentInjectionByWellAndDate(eq(wellId), any()))
                    .thenReturn(60.0);
            when(profileRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<InjectionProfile> result = optimizer.adjustWellProfile(request);

            for (InjectionProfile profile : result) {
                assertTrue(profile.getSuggestedInjectionVolume() >= 5.0,
                        "即使目标为负，单层注水量也不应低于最小值。实际: " + profile.getSuggestedInjectionVolume());
            }
        }

        @Test
        @DisplayName("异常场景 - 调整幅度为0%")
        void testZeroAdjustmentPercentage() {
            String wellId = "INJ-ZERO-ADJ";
            ProfileAdjustmentRequest request = new ProfileAdjustmentRequest();
            request.setWellId(wellId);
            request.setProfileDate(LocalDate.now());
            request.setMaxAdjustmentPercentage(0.0);
            request.setExecuteAutoAdjustment(false);

            List<InjectionProfile> profiles = Arrays.asList(
                    createMockProfile(1, 300.0, 10.0, 0.8, 10.0, 5.0, 30.0),
                    createMockProfile(2, 50.0, 10.0, 0.2, 10.0, 5.0, 30.0)
            );

            when(profileRepository.findByWellIdAndProfileDateOrderByLayerNumber(eq(wellId), any()))
                    .thenReturn(profiles);
            when(profileRepository.sumCurrentInjectionByWellAndDate(eq(wellId), any()))
                    .thenReturn(60.0);
            when(profileRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<InjectionProfile> result = optimizer.adjustWellProfile(request);

            for (InjectionProfile profile : result) {
                assertEquals(profile.getCurrentInjectionVolume(), profile.getSuggestedInjectionVolume(), 0.01,
                        "调整幅度为0%时，建议值应等于当前值");
                assertEquals("KEEP", profile.getAdjustmentDirection(),
                        "调整幅度为0%时，方向应为KEEP");
            }
        }
    }

    private InjectionProfile createMockProfile(int layerNumber, double permeability, double thickness,
                                                double absorptionRatio, double startingPressure,
                                                double skinFactor, double currentVolume) {
        InjectionProfile profile = new InjectionProfile();
        profile.setId((long) layerNumber);
        profile.setWellId("INJ-001");
        profile.setLayerNumber(layerNumber);
        profile.setLayerName("Layer " + layerNumber);
        profile.setProfileDate(LocalDate.now());
        profile.setTopDepth(1500.0 + (layerNumber - 1) * 20.0);
        profile.setBottomDepth(1500.0 + layerNumber * 20.0);
        profile.setThickness(thickness);
        profile.setPermeability(permeability);
        profile.setPorosity(0.25);
        profile.setCurrentInjectionVolume(currentVolume);
        profile.setWaterAbsorptionRatio(absorptionRatio);
        profile.setStartingPressure(startingPressure);
        profile.setCurrentPressure(startingPressure + 2.0);
        profile.setSkinFactor(skinFactor);
        profile.setAllocationStatus("INITIAL");
        return profile;
    }

    private List<InjectionProfile> createMockProfiles(int layerCount, double totalVolume) {
        List<InjectionProfile> profiles = new ArrayList<>();
        double perLayerVolume = totalVolume / layerCount;
        Random random = new Random(42);

        for (int i = 1; i <= layerCount; i++) {
            double perm = 50 + random.nextDouble() * 450;
            double thick = 5 + random.nextDouble() * 15;
            double absorp = 0.2 + random.nextDouble() * 0.6;
            double startPress = 5 + random.nextDouble() * 20;
            double skin = -1 + random.nextDouble() * 8;

            InjectionProfile profile = createMockProfile(i, perm, thick, absorp, startPress, skin, perLayerVolume);
            profiles.add(profile);
        }
        return profiles;
    }

    @SuppressWarnings("unchecked")
    private List<InjectionProfile> invokeCalculateOptimalAllocation(
            List<InjectionProfile> profiles, double currentTotal, double targetTotal,
            double maxAdjustmentPct, Map<Integer, Double> layerOverrides) {
        try {
            Method method = InjectionProfileOptimizer.class.getDeclaredMethod(
                    "calculateOptimalLayerAllocation",
                    List.class, double.class, double.class,
                    double.class, Map.class, AdvancedFeaturesProperties.Profile.class);
            method.setAccessible(true);
            return (List<InjectionProfile>) method.invoke(
                    optimizer, profiles, currentTotal, targetTotal,
                    maxAdjustmentPct, layerOverrides, profileConfig);
        } catch (Exception e) {
            throw new RuntimeException("反射调用失败", e);
        }
    }

    private double invokeCalculateLayerAllocationScore(InjectionProfile profile) {
        try {
            Method method = InjectionProfileOptimizer.class.getDeclaredMethod(
                    "calculateLayerAllocationScore",
                    InjectionProfile.class, AdvancedFeaturesProperties.Profile.class);
            method.setAccessible(true);
            return (double) method.invoke(optimizer, profile, profileConfig);
        } catch (Exception e) {
            throw new RuntimeException("反射调用失败", e);
        }
    }

    private boolean invokeExecuteSmartWaterController(String wellId, List<InjectionProfile> profiles) {
        try {
            Method method = InjectionProfileOptimizer.class.getDeclaredMethod(
                    "executeSmartWaterControllerAdjustment",
                    String.class, List.class);
            method.setAccessible(true);
            return (boolean) method.invoke(optimizer, wellId, profiles);
        } catch (Exception e) {
            throw new RuntimeException("反射调用失败", e);
        }
    }

    private double calculateLayerContradiction(List<InjectionProfile> profiles) {
        if (profiles.size() < 2) return 0.0;

        double[] absIndices = new double[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            InjectionProfile p = profiles.get(i);
            double volume = p.getSuggestedInjectionVolume() != null ?
                    p.getSuggestedInjectionVolume() :
                    (p.getCurrentInjectionVolume() != null ? p.getCurrentInjectionVolume() : 0);
            double k = p.getPermeability() != null ? p.getPermeability() : 100;
            double h = p.getThickness() != null ? p.getThickness() : 10;
            double absIndex = volume / (k * h);
            absIndices[i] = absIndex;
        }

        double mean = Arrays.stream(absIndices).average().orElse(0);
        double variance = Arrays.stream(absIndices)
                .map(v -> Math.pow(v - mean, 2))
                .average().orElse(0);
        return Math.sqrt(variance) / mean;
    }

    private double calculateInjectionStdDev(List<InjectionProfile> profiles) {
        double[] volumes = profiles.stream()
                .mapToDouble(p -> p.getSuggestedInjectionVolume() != null ?
                        p.getSuggestedInjectionVolume() :
                        (p.getCurrentInjectionVolume() != null ? p.getCurrentInjectionVolume() : 0))
                .toArray();

        double mean = Arrays.stream(volumes).average().orElse(0);
        double variance = Arrays.stream(volumes)
                .map(v -> Math.pow(v - mean, 2))
                .average().orElse(0);
        return Math.sqrt(variance);
    }

    private double calculateAbsorptionStdDev(List<InjectionProfile> profiles) {
        double[] absorptions = profiles.stream()
                .mapToDouble(p -> p.getWaterAbsorptionRatio() != null ? p.getWaterAbsorptionRatio() : 0)
                .toArray();

        double mean = Arrays.stream(absorptions).average().orElse(0);
        double variance = Arrays.stream(absorptions)
                .map(v -> Math.pow(v - mean, 2))
                .average().orElse(0);
        return Math.sqrt(variance);
    }

    private double calculateAbsorptionBalance(List<InjectionProfile> profiles) {
        double[] absIndices = new double[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            InjectionProfile p = profiles.get(i);
            double volume = p.getSuggestedInjectionVolume() != null ?
                    p.getSuggestedInjectionVolume() :
                    (p.getCurrentInjectionVolume() != null ? p.getCurrentInjectionVolume() : 0);
            double k = p.getPermeability() != null ? p.getPermeability() : 100;
            double h = p.getThickness() != null ? p.getThickness() : 10;
            double dp = (p.getCurrentPressure() != null ? p.getCurrentPressure() : 10) -
                    (p.getStartingPressure() != null ? p.getStartingPressure() : 5);
            double absIndex = volume / (k * h * Math.max(dp, 0.1));
            absIndices[i] = absIndex;
        }

        double max = Arrays.stream(absIndices).max().orElse(1);
        double min = Arrays.stream(absIndices).min().orElse(0);
        return max > 0 ? (1.0 - (max - min) / max) : 0;
    }
}
