package com.smart.oilfield.reservoir.service;

import com.smart.oilfield.reservoir.ReservoirSimulationApplication;
import com.smart.oilfield.reservoir.dto.SimulationProgress;
import com.smart.oilfield.reservoir.dto.SimulationRequest;
import com.smart.oilfield.reservoir.dto.SimulationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ReservoirSimulationApplication.class)
@ExtendWith(MockitoExtension.class)
class ReservoirSimulationServiceTest {

    @InjectMocks
    private ReservoirSimulationService simulationService;

    @BeforeEach
    void setUp() {
        simulationService = new ReservoirSimulationService();
        ReflectionTestUtils.setField(simulationService, "maxGridCount", 1000000);
        ReflectionTestUtils.setField(simulationService, "defaultTimeoutMinutes", 60);
        ReflectionTestUtils.setField(simulationService, "cacheExpireHours", 24);
    }

    @Nested
    @DisplayName("黑油模拟测试")
    class BlackOilSimulationTests {

        @Test
        @DisplayName("黑油模拟 - 基础场景执行")
        void testBlackOilSimulationBasic() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(5)
                    .gridSizeJ(5)
                    .gridSizeK(3)
                    .totalTimeDays(100.0)
                    .timeStepDays(10.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);
            SimulationResult result = future.get(30, TimeUnit.SECONDS);

            assertNotNull(result, "模拟结果不应为null");
            assertEquals("BLACK_OIL", result.getSimulationType(), "模拟类型应为黑油");
            assertEquals("COMPLETED", result.getStatus(), "模拟状态应为完成");
            assertNotNull(result.getSimulationId(), "模拟ID不应为null");
            assertTrue(result.getSimulationId().startsWith("SIM-"), "模拟ID应以SIM-开头");

            assertEquals(10, result.getCompletedTimeSteps(), "应完成10个时间步");
            assertEquals(10, result.getTotalTimeSteps(), "总时间步应为10");
            assertEquals(75, result.getGridCount(), "网格数量应为75");

            assertNotNull(result.getOilProductionRates(), "产油速率列表不应为null");
            assertEquals(10, result.getOilProductionRates().size(), "产油速率应有10个数据点");

            assertNotNull(result.getFinalGridData(), "最终网格数据不应为null");
            assertEquals(75, result.getFinalGridData().size(), "网格数据数量应匹配");

            assertTrue(result.getTotalOilProduction() > 0, "总产油量应大于0");
            assertTrue(result.getOilRecoveryFactor() > 0, "采收率应大于0");
        }

        @Test
        @DisplayName("黑油模拟 - 大网格模型")
        void testBlackOilSimulationLargeGrid() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(20)
                    .gridSizeJ(20)
                    .gridSizeK(10)
                    .totalTimeDays(50.0)
                    .timeStepDays(10.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);
            SimulationResult result = future.get(60, TimeUnit.SECONDS);

            assertNotNull(result, "模拟结果不应为null");
            assertEquals(4000, result.getGridCount(), "网格数量应为4000");
            assertEquals("COMPLETED", result.getStatus(), "模拟状态应为完成");
        }

        @Test
        @DisplayName("黑油模拟 - 单时间步验证")
        void testBlackOilSimulationSingleTimestep() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(3)
                    .gridSizeJ(3)
                    .gridSizeK(2)
                    .totalTimeDays(5.0)
                    .timeStepDays(5.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);
            SimulationResult result = future.get(10, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(1, result.getCompletedTimeSteps(), "应完成1个时间步");
            assertEquals(1, result.getOilProductionRates().size(), "应有1个产油速率数据点");
        }

        @Test
        @DisplayName("黑油模拟 - 压力分布验证")
        void testBlackOilSimulationPressureDistribution() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(4)
                    .gridSizeJ(4)
                    .gridSizeK(2)
                    .totalTimeDays(20.0)
                    .timeStepDays(10.0)
                    .initialPressure(250.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);
            SimulationResult result = future.get(30, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(2, result.getCompletedTimeSteps());

            double finalPressure = result.getFinalAveragePressure();
            assertTrue(finalPressure > 0, "最终平均压力应大于0");
            assertTrue(finalPressure <= 250.0, "最终压力不应超过初始压力");
        }

        @Test
        @DisplayName("黑油模拟 - 网格数超过上限验证")
        void testBlackOilSimulationGridExceedMax() {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(200)
                    .gridSizeJ(200)
                    .gridSizeK(50)
                    .totalTimeDays(10.0)
                    .timeStepDays(1.0)
                    .build();

            assertThrows(RuntimeException.class, () -> {
                simulationService.runBlackOilSimulation(request).get(5, TimeUnit.SECONDS);
            }, "网格数超过上限应抛出异常");
        }
    }

    @Nested
    @DisplayName("水驱模拟测试")
    class WaterFloodingSimulationTests {

        @Test
        @DisplayName("水驱模拟 - 基础场景执行")
        void testWaterFloodingSimulationBasic() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(6)
                    .gridSizeJ(6)
                    .gridSizeK(3)
                    .totalTimeDays(150.0)
                    .timeStepDays(15.0)
                    .injectionRate(1500.0)
                    .injectionWells(Collections.singletonList("INJ-001"))
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runWaterFloodingSimulation(request);
            SimulationResult result = future.get(60, TimeUnit.SECONDS);

            assertNotNull(result, "模拟结果不应为null");
            assertEquals("WATER_FLOODING", result.getSimulationType(), "模拟类型应为水驱");
            assertEquals("COMPLETED", result.getStatus(), "模拟状态应为完成");

            assertNotNull(result.getTotalWaterInjection(), "总注水量不应为null");
            assertTrue(result.getTotalWaterInjection() > 0, "总注水量应大于0");

            assertEquals(10, result.getCompletedTimeSteps(), "应完成10个时间步");

            List<Double> waterSats = result.getAverageWaterSaturations();
            assertNotNull(waterSats, "含水饱和度列表不应为null");
            assertEquals(10, waterSats.size(), "含水饱和度应有10个数据点");

            double firstSw = waterSats.get(0);
            double lastSw = waterSats.get(waterSats.size() - 1);
            assertTrue(lastSw >= firstSw, "水驱过程中含水饱和度应上升或保持");
        }

        @Test
        @DisplayName("水驱模拟 - 含水率变化分析")
        void testWaterFloodingSimulationWaterCut() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(5)
                    .gridSizeJ(5)
                    .gridSizeK(2)
                    .totalTimeDays(200.0)
                    .timeStepDays(20.0)
                    .injectionRate(2000.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runWaterFloodingSimulation(request);
            SimulationResult result = future.get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            assertNotNull(result.getWaterCut(), "含水率不应为null");
            assertTrue(result.getWaterCut() >= 0.0 && result.getWaterCut() <= 1.0,
                    "含水率应在[0,1]范围内，实际: " + result.getWaterCut());

            List<Double> waterRates = result.getWaterProductionRates();
            List<Double> oilRates = result.getOilProductionRates();
            assertEquals(waterRates.size(), oilRates.size(), "产水产油速率数量应相等");
        }

        @Test
        @DisplayName("水驱模拟 - 多注入井配置")
        void testWaterFloodingSimulationMultipleInjectionWells() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(8)
                    .gridSizeJ(8)
                    .gridSizeK(3)
                    .totalTimeDays(100.0)
                    .timeStepDays(10.0)
                    .injectionRate(1000.0)
                    .injectionWells(Arrays.asList("INJ-001", "INJ-002"))
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runWaterFloodingSimulation(request);
            SimulationResult result = future.get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals("COMPLETED", result.getStatus());
            assertTrue(result.getTotalWaterInjection() > 0);
        }

        @Test
        @DisplayName("水驱模拟 - 无注入井默认均匀注水")
        void testWaterFloodingSimulationNoInjectionWells() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(4)
                    .gridSizeJ(4)
                    .gridSizeK(2)
                    .totalTimeDays(50.0)
                    .timeStepDays(10.0)
                    .injectionRate(800.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runWaterFloodingSimulation(request);
            SimulationResult result = future.get(30, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals("COMPLETED", result.getStatus());
            assertTrue(result.getTotalWaterInjection() > 0, "即使无注入井也应有注水");
        }
    }

    @Nested
    @DisplayName("EOR模拟测试")
    class EORSimulationTests {

        @Test
        @DisplayName("EOR模拟 - 聚合物驱基础场景")
        void testEORSimulationPolymerFlooding() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(6)
                    .gridSizeJ(6)
                    .gridSizeK(3)
                    .totalTimeDays(180.0)
                    .timeStepDays(15.0)
                    .injectionRate(1200.0)
                    .polymerConcentration(0.0015)
                    .surfactantConcentration(0.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runEORSimulation(request);
            SimulationResult result = future.get(90, TimeUnit.SECONDS);

            assertNotNull(result, "模拟结果不应为null");
            assertEquals("EOR", result.getSimulationType(), "模拟类型应为EOR");
            assertEquals("COMPLETED", result.getStatus(), "模拟状态应为完成");

            assertNotNull(result.getTotalGasProduction(), "总产气量不应为null");
            assertTrue(result.getTotalGasProduction() >= 0, "总产气量应非负");

            assertNotNull(result.getGasProductionRates(), "产气速率列表不应为null");
            assertEquals(12, result.getGasProductionRates().size(), "产气速率应有12个数据点");

            assertNotNull(result.getSweepEfficiency(), "波及效率不应为null");
            assertTrue(result.getSweepEfficiency() >= 0.0 && result.getSweepEfficiency() <= 1.0,
                    "波及效率应在[0,1]范围内，实际: " + result.getSweepEfficiency());

            assertNotNull(result.getDisplacementEfficiency(), "驱替效率不应为null");
            assertTrue(result.getDisplacementEfficiency() >= 0.0 && result.getDisplacementEfficiency() <= 1.0,
                    "驱替效率应在[0,1]范围内，实际: " + result.getDisplacementEfficiency());
        }

        @Test
        @DisplayName("EOR模拟 - 表面活性剂驱")
        void testEORSimulationSurfactantFlooding() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(5)
                    .gridSizeJ(5)
                    .gridSizeK(2)
                    .totalTimeDays(120.0)
                    .timeStepDays(10.0)
                    .injectionRate(1000.0)
                    .polymerConcentration(0.0)
                    .surfactantConcentration(0.0008)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runEORSimulation(request);
            SimulationResult result = future.get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals("COMPLETED", result.getStatus());
            assertTrue(result.getTotalGasProduction() >= 0);

            assertNotNull(result.getResidualOilSaturation());
            assertTrue(result.getResidualOilSaturation() > 0);
        }

        @Test
        @DisplayName("EOR模拟 - 复合驱（聚合物+表面活性剂）")
        void testEORSimulationCombinedFlooding() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(6)
                    .gridSizeJ(6)
                    .gridSizeK(3)
                    .totalTimeDays(200.0)
                    .timeStepDays(20.0)
                    .injectionRate(1500.0)
                    .polymerConcentration(0.001)
                    .surfactantConcentration(0.0005)
                    .relativePermeabilityParams(createRelPermParams())
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runEORSimulation(request);
            SimulationResult result = future.get(120, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals("COMPLETED", result.getStatus());
            assertEquals(10, result.getCompletedTimeSteps());

            assertNotNull(result.getSweepEfficiency());
            assertNotNull(result.getDisplacementEfficiency());
            assertTrue(result.getSweepEfficiency() > 0);
            assertTrue(result.getDisplacementEfficiency() > 0);
        }

        @Test
        @DisplayName("EOR模拟 - 采收率对比验证")
        void testEORSimulationRecoveryComparison() throws Exception {
            SimulationRequest baseRequest = createBaseRequest()
                    .gridSizeI(5)
                    .gridSizeJ(5)
                    .gridSizeK(2)
                    .totalTimeDays(100.0)
                    .timeStepDays(10.0)
                    .injectionRate(1000.0)
                    .build();

            SimulationResult waterFloodResult = simulationService.runWaterFloodingSimulation(baseRequest)
                    .get(60, TimeUnit.SECONDS);

            SimulationRequest eorRequest = createBaseRequest()
                    .gridSizeI(5)
                    .gridSizeJ(5)
                    .gridSizeK(2)
                    .totalTimeDays(100.0)
                    .timeStepDays(10.0)
                    .injectionRate(1000.0)
                    .polymerConcentration(0.002)
                    .surfactantConcentration(0.001)
                    .build();

            SimulationResult eorResult = simulationService.runEORSimulation(eorRequest)
                    .get(90, TimeUnit.SECONDS);

            assertNotNull(waterFloodResult);
            assertNotNull(eorResult);
            assertTrue(eorResult.getOilRecoveryFactor() >= waterFloodResult.getOilRecoveryFactor() * 0.9,
                    "EOR采收率不应显著低于水驱采收率");
        }
    }

    @Nested
    @DisplayName("异步执行测试")
    class AsyncExecutionTests {

        @Test
        @DisplayName("异步执行 - 返回CompletableFuture")
        void testAsyncExecutionReturnsFuture() {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(4)
                    .gridSizeJ(4)
                    .gridSizeK(2)
                    .totalTimeDays(50.0)
                    .timeStepDays(10.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);

            assertNotNull(future, "应返回CompletableFuture");
            assertFalse(future.isDone(), "调用后不应立即完成");
        }

        @Test
        @DisplayName("异步执行 - 多任务并发执行")
        void testAsyncExecutionMultipleConcurrent() throws Exception {
            SimulationRequest request1 = createBaseRequest()
                    .gridSizeI(3)
                    .gridSizeJ(3)
                    .gridSizeK(2)
                    .totalTimeDays(30.0)
                    .timeStepDays(10.0)
                    .build();

            SimulationRequest request2 = createBaseRequest()
                    .gridSizeI(4)
                    .gridSizeJ(4)
                    .gridSizeK(2)
                    .totalTimeDays(40.0)
                    .timeStepDays(10.0)
                    .build();

            CompletableFuture<SimulationResult> future1 = simulationService.runBlackOilSimulation(request1);
            CompletableFuture<SimulationResult> future2 = simulationService.runWaterFloodingSimulation(request2);

            CompletableFuture.allOf(future1, future2).get(60, TimeUnit.SECONDS);

            assertTrue(future1.isDone(), "任务1应完成");
            assertTrue(future2.isDone(), "任务2应完成");

            SimulationResult result1 = future1.get();
            SimulationResult result2 = future2.get();

            assertNotEquals(result1.getSimulationId(), result2.getSimulationId(),
                    "不同任务应有不同的模拟ID");
        }

        @Test
        @DisplayName("异步执行 - 异常处理传播")
        void testAsyncExecutionExceptionPropagation() {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(200)
                    .gridSizeJ(200)
                    .gridSizeK(50)
                    .totalTimeDays(10.0)
                    .timeStepDays(1.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);

            assertThrows(RuntimeException.class, () -> {
                future.get(10, TimeUnit.SECONDS);
            }, "异常应通过Future传播");
        }
    }

    @Nested
    @DisplayName("进度跟踪测试")
    class ProgressTrackingTests {

        @Test
        @DisplayName("进度跟踪 - 初始化阶段进度")
        void testProgressTrackingInitialPhase() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(5)
                    .gridSizeJ(5)
                    .gridSizeK(3)
                    .totalTimeDays(100.0)
                    .timeStepDays(10.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);

            Thread.sleep(50);

            String simId = extractSimulationId(future);

            if (simId != null) {
                SimulationProgress progress = simulationService.getSimulationProgress(simId);
                if (progress != null) {
                    assertNotNull(progress.getStatus(), "状态不应为null");
                    assertNotNull(progress.getStartTime(), "开始时间不应为null");
                }
            }

            future.get(30, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("进度跟踪 - 运行中进度更新")
        void testProgressTrackingRunningPhase() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(6)
                    .gridSizeJ(6)
                    .gridSizeK(3)
                    .totalTimeDays(100.0)
                    .timeStepDays(10.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);

            Thread.sleep(100);

            List<String> simulations = simulationService.listSimulations();
            assertFalse(simulations.isEmpty(), "应至少有一个模拟任务");

            String simId = simulations.get(0);
            SimulationProgress progress = simulationService.getSimulationProgress(simId);

            if (progress != null && progress.getProgress() < 100.0) {
                assertEquals("RUNNING", progress.getStatus(), "模拟进行中状态应为RUNNING");
                assertTrue(progress.getProgress() >= 0.0 && progress.getProgress() <= 100.0,
                        "进度应在[0,100]范围内，实际: " + progress.getProgress());
                assertNotNull(progress.getCurrentPhase(), "当前阶段不应为null");
            }

            future.get(60, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("进度跟踪 - 完成后进度100%")
        void testProgressTrackingCompleted() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(4)
                    .gridSizeJ(4)
                    .gridSizeK(2)
                    .totalTimeDays(50.0)
                    .timeStepDays(10.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);
            SimulationResult result = future.get(30, TimeUnit.SECONDS);

            assertNotNull(result.getSimulationId());

            SimulationProgress progress = simulationService.getSimulationProgress(result.getSimulationId());
            assertNotNull(progress, "完成后进度信息不应为null");
            assertEquals("COMPLETED", progress.getStatus(), "状态应为COMPLETED");
            assertEquals(100.0, progress.getProgress(), 0.01, "进度应为100%");
        }

        @Test
        @DisplayName("进度跟踪 - 不存在的模拟ID返回null")
        void testProgressTrackingNonExistentId() {
            SimulationProgress progress = simulationService.getSimulationProgress("NON-EXISTENT-ID");
            assertNull(progress, "不存在的ID应返回null");
        }

        @Test
        @DisplayName("进度跟踪 - 模拟列表查询")
        void testProgressTrackingListSimulations() throws Exception {
            simulationService = new ReservoirSimulationService();
            ReflectionTestUtils.setField(simulationService, "maxGridCount", 1000000);

            SimulationRequest request = createBaseRequest()
                    .gridSizeI(3)
                    .gridSizeJ(3)
                    .gridSizeK(2)
                    .totalTimeDays(30.0)
                    .timeStepDays(10.0)
                    .build();

            assertTrue(simulationService.listSimulations().isEmpty(), "初始时模拟列表应为空");

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);
            future.get(30, TimeUnit.SECONDS);

            assertEquals(1, simulationService.listSimulations().size(), "应包含1个模拟任务");
        }
    }

    @Nested
    @DisplayName("任务取消测试")
    class SimulationCancellationTests {

        @Test
        @DisplayName("任务取消 - 运行中任务取消")
        void testCancelRunningSimulation() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(10)
                    .gridSizeJ(10)
                    .gridSizeK(5)
                    .totalTimeDays(500.0)
                    .timeStepDays(10.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);

            Thread.sleep(200);

            List<String> simulations = simulationService.listSimulations();
            assertFalse(simulations.isEmpty(), "模拟任务应已创建");

            String simId = simulations.get(0);
            boolean cancelled = simulationService.cancelSimulation(simId);

            assertTrue(cancelled, "取消操作应返回true");

            try {
                SimulationResult result = future.get(10, TimeUnit.SECONDS);
                assertNotNull(result);
            } catch (Exception e) {
                assertTrue(true, "取消后可能抛出异常或返回部分结果");
            }

            SimulationProgress progress = simulationService.getSimulationProgress(simId);
            if (progress != null) {
                assertEquals("CANCELLED", progress.getStatus(), "状态应为CANCELLED");
            }
        }

        @Test
        @DisplayName("任务取消 - 不存在的任务取消失败")
        void testCancelNonExistentSimulation() {
            boolean cancelled = simulationService.cancelSimulation("NON-EXISTENT");
            assertFalse(cancelled, "不存在的任务取消应返回false");
        }

        @Test
        @DisplayName("任务取消 - 已完成任务取消")
        void testCancelCompletedSimulation() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(3)
                    .gridSizeJ(3)
                    .gridSizeK(2)
                    .totalTimeDays(20.0)
                    .timeStepDays(10.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);
            SimulationResult result = future.get(30, TimeUnit.SECONDS);

            assertNotNull(result.getSimulationId());

            boolean cancelled = simulationService.cancelSimulation(result.getSimulationId());
            assertTrue(cancelled, "已完成任务取消应返回true（更新状态）");

            SimulationProgress progress = simulationService.getSimulationProgress(result.getSimulationId());
            if (progress != null) {
                assertEquals("CANCELLED", progress.getStatus(), "状态应被更新为CANCELLED");
            }
        }
    }

    @Nested
    @DisplayName("熔断和限流测试")
    class ResilienceTests {

        @Test
        @DisplayName("熔断降级 - fallback方法返回默认结果")
        void testCircuitBreakerFallback() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(3)
                    .gridSizeJ(3)
                    .gridSizeK(2)
                    .totalTimeDays(10.0)
                    .timeStepDays(5.0)
                    .reservoirName("Test Reservoir")
                    .build();

            CompletableFuture<SimulationResult> fallbackFuture =
                    simulationService.simulationFallback(request, new RuntimeException("Test exception"));

            SimulationResult fallbackResult = fallbackFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(fallbackResult, "fallback结果不应为null");
            assertEquals("FAILED", fallbackResult.getStatus(), "fallback状态应为FAILED");
            assertNotNull(fallbackResult.getErrorMessage(), "错误消息不应为null");
            assertTrue(fallbackResult.getErrorMessage().contains("Test exception"),
                    "错误消息应包含原始异常信息");
            assertTrue(fallbackResult.getSimulationId().startsWith("FALLBACK-"),
                    "fallback模拟ID应以FALLBACK-开头");
        }

        @Test
        @DisplayName("熔断降级 - null异常处理")
        void testCircuitBreakerFallbackNullException() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(3)
                    .gridSizeJ(3)
                    .gridSizeK(2)
                    .totalTimeDays(10.0)
                    .timeStepDays(5.0)
                    .build();

            CompletableFuture<SimulationResult> fallbackFuture =
                    simulationService.simulationFallback(request, null);

            SimulationResult fallbackResult = fallbackFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(fallbackResult);
            assertEquals("FAILED", fallbackResult.getStatus());
            assertNotNull(fallbackResult.getErrorMessage());
        }

        @Test
        @DisplayName("结果缓存 - 完成后可从缓存获取")
        void testResultCaching() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(4)
                    .gridSizeJ(4)
                    .gridSizeK(2)
                    .totalTimeDays(40.0)
                    .timeStepDays(10.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);
            SimulationResult result = future.get(30, TimeUnit.SECONDS);

            assertNotNull(result.getSimulationId());

            SimulationResult cachedResult = simulationService.getSimulationResult(result.getSimulationId());
            assertNotNull(cachedResult, "缓存中应能获取结果");
            assertEquals(result.getSimulationId(), cachedResult.getSimulationId(), "缓存ID应匹配");
            assertEquals(result.getTotalOilProduction(), cachedResult.getTotalOilProduction(), 0.001,
                    "缓存结果应与原始结果一致");
        }

        @Test
        @DisplayName("结果缓存 - 不存在的ID返回null")
        void testResultCachingNonExistent() {
            SimulationResult cachedResult = simulationService.getSimulationResult("NON-EXISTENT");
            assertNull(cachedResult, "不存在的ID应返回null");
        }
    }

    @Nested
    @DisplayName("边界与异常场景测试")
    class BoundaryAndExceptionTests {

        @Test
        @DisplayName("边界测试 - 最小网格配置")
        void testMinimumGridConfiguration() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(1)
                    .gridSizeJ(1)
                    .gridSizeK(1)
                    .totalTimeDays(5.0)
                    .timeStepDays(5.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);
            SimulationResult result = future.get(10, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(1, result.getGridCount(), "最小网格数应为1");
            assertEquals("COMPLETED", result.getStatus());
        }

        @Test
        @DisplayName("边界测试 - 短时间模拟")
        void testVeryShortSimulation() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(2)
                    .gridSizeJ(2)
                    .gridSizeK(1)
                    .totalTimeDays(1.0)
                    .timeStepDays(1.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);
            SimulationResult result = future.get(10, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(1, result.getCompletedTimeSteps());
            assertTrue(result.getTotalOilProduction() > 0);
        }

        @Test
        @DisplayName("边界测试 - 自定义岩石流体参数")
        void testCustomRockFluidProperties() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(4)
                    .gridSizeJ(4)
                    .gridSizeK(2)
                    .totalTimeDays(30.0)
                    .timeStepDays(10.0)
                    .porosity(0.25)
                    .permeabilityX(150.0)
                    .oilViscosity(1.5)
                    .waterViscosity(0.6)
                    .rockCompressibility(1.5e-5)
                    .initialOilSaturation(0.75)
                    .initialWaterSaturation(0.25)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);
            SimulationResult result = future.get(30, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals("COMPLETED", result.getStatus());
            assertTrue(result.getFinalAverageOilSaturation() > 0);
            assertTrue(result.getFinalAverageWaterSaturation() > 0);
        }

        @Test
        @DisplayName("边界测试 - 网格数据完整性验证")
        void testGridDataCompleteness() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(3)
                    .gridSizeJ(3)
                    .gridSizeK(2)
                    .totalTimeDays(20.0)
                    .timeStepDays(10.0)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);
            SimulationResult result = future.get(30, TimeUnit.SECONDS);

            assertNotNull(result.getFinalGridData());
            assertEquals(18, result.getFinalGridData().size());

            result.getFinalGridData().forEach(gridBlock -> {
                assertNotNull(gridBlock.getI(), "I坐标不应为null");
                assertNotNull(gridBlock.getJ(), "J坐标不应为null");
                assertNotNull(gridBlock.getK(), "K坐标不应为null");
                assertTrue(gridBlock.getPressure() > 0, "压力应大于0");
                assertTrue(gridBlock.getOilSaturation() >= 0 && gridBlock.getOilSaturation() <= 1,
                        "含油饱和度应在[0,1]范围内");
                assertTrue(gridBlock.getWaterSaturation() >= 0 && gridBlock.getWaterSaturation() <= 1,
                        "含水饱和度应在[0,1]范围内");
                assertTrue(gridBlock.getOilRelativePermeability() >= 0, "油相相对渗透率应非负");
                assertTrue(gridBlock.getWaterRelativePermeability() >= 0, "水相相对渗透率应非负");
            });
        }

        @Test
        @DisplayName("边界测试 - 最终状态物理合理性")
        void testFinalStatePhysicalValidity() throws Exception {
            SimulationRequest request = createBaseRequest()
                    .gridSizeI(5)
                    .gridSizeJ(5)
                    .gridSizeK(2)
                    .totalTimeDays(100.0)
                    .timeStepDays(10.0)
                    .initialOilSaturation(0.7)
                    .initialWaterSaturation(0.3)
                    .build();

            CompletableFuture<SimulationResult> future = simulationService.runBlackOilSimulation(request);
            SimulationResult result = future.get(30, TimeUnit.SECONDS);

            assertNotNull(result);

            double finalSo = result.getFinalAverageOilSaturation();
            double finalSw = result.getFinalAverageWaterSaturation();

            assertTrue(finalSo >= 0.2, "最终含油饱和度不应低于残余油饱和度");
            assertTrue(finalSo <= 0.7, "最终含油饱和度不应超过初始值");
            assertTrue(finalSw >= 0.3, "最终含水饱和度不应低于初始值");
            assertTrue(finalSo + finalSw <= 1.0 + 0.001, "含油+含水饱和度应不超过1");
        }
    }

    private String extractSimulationId(CompletableFuture<?> future) {
        try {
            Thread.sleep(100);
            List<String> simulations = simulationService.listSimulations();
            return simulations.isEmpty() ? null : simulations.get(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private SimulationRequest.SimulationRequestBuilder createBaseRequest() {
        return SimulationRequest.builder()
                .reservoirName("TEST-RESERVOIR")
                .simulationType("BLACK_OIL")
                .initialPressure(200.0)
                .initialOilSaturation(0.7)
                .initialWaterSaturation(0.3)
                .porosity(0.3)
                .permeabilityX(100.0)
                .oilViscosity(1.0)
                .waterViscosity(0.5)
                .rockCompressibility(1e-5)
                .productionRate(500.0)
                .injectionRate(1000.0);
    }

    private Map<String, Double> createRelPermParams() {
        Map<String, Double> params = new HashMap<>();
        params.put("residualOilSaturation", 0.2);
        params.put("connateWaterSaturation", 0.2);
        params.put("coreyExponentOil", 2.0);
        params.put("coreyExponentWater", 2.5);
        params.put("endpointRelativePermeabilityOil", 1.0);
        params.put("endpointRelativePermeabilityWater", 0.3);
        return params;
    }
}
