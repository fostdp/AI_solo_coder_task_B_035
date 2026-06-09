package com.smart.oilfield.reservoir.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smart.oilfield.reservoir.dto.GridBlockData;
import com.smart.oilfield.reservoir.dto.SimulationProgress;
import com.smart.oilfield.reservoir.dto.SimulationRequest;
import com.smart.oilfield.reservoir.dto.SimulationResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.analysis.interpolation.LinearInterpolator;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.DecompositionSolver;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.util.MathArrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class ReservoirSimulationService {

    private final Cache<String, SimulationResult> resultCache;
    private final Map<String, SimulationProgress> progressMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancellationMap = new ConcurrentHashMap<>();

    @Value("${simulation.max-grid-count:1000000}")
    private int maxGridCount;

    @Value("${simulation.default-timeout-minutes:60}")
    private int defaultTimeoutMinutes;

    @Value("${simulation.cache-expire-hours:24}")
    private int cacheExpireHours;

    public ReservoirSimulationService() {
        this.resultCache = Caffeine.newBuilder()
                .expireAfterWrite(24, TimeUnit.HOURS)
                .maximumSize(100)
                .build();
    }

    @Async
    @CircuitBreaker(name = "simulationService", fallbackMethod = "simulationFallback")
    @RateLimiter(name = "simulationService")
    public CompletableFuture<SimulationResult> runBlackOilSimulation(SimulationRequest request) {
        String simulationId = generateSimulationId();
        log.info("Starting Black Oil simulation: {}", simulationId);

        initializeSimulation(simulationId, request, "BLACK_OIL");

        try {
            SimulationResult result = executeBlackOilSimulation(simulationId, request);
            resultCache.put(simulationId, result);
            updateProgress(simulationId, "COMPLETED", 100.0, "Simulation completed successfully");
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("Black Oil simulation failed: {}", simulationId, e);
            updateProgress(simulationId, "FAILED", 0.0, "Simulation failed: " + e.getMessage());
            throw new RuntimeException("Simulation failed", e);
        }
    }

    @Async
    @CircuitBreaker(name = "simulationService", fallbackMethod = "simulationFallback")
    @RateLimiter(name = "simulationService")
    public CompletableFuture<SimulationResult> runWaterFloodingSimulation(SimulationRequest request) {
        String simulationId = generateSimulationId();
        log.info("Starting Water Flooding simulation: {}", simulationId);

        initializeSimulation(simulationId, request, "WATER_FLOODING");

        try {
            SimulationResult result = executeWaterFloodingSimulation(simulationId, request);
            resultCache.put(simulationId, result);
            updateProgress(simulationId, "COMPLETED", 100.0, "Simulation completed successfully");
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("Water Flooding simulation failed: {}", simulationId, e);
            updateProgress(simulationId, "FAILED", 0.0, "Simulation failed: " + e.getMessage());
            throw new RuntimeException("Simulation failed", e);
        }
    }

    @Async
    @CircuitBreaker(name = "simulationService", fallbackMethod = "simulationFallback")
    @RateLimiter(name = "simulationService")
    public CompletableFuture<SimulationResult> runEORSimulation(SimulationRequest request) {
        String simulationId = generateSimulationId();
        log.info("Starting EOR simulation: {}", simulationId);

        initializeSimulation(simulationId, request, "EOR");

        try {
            SimulationResult result = executeEORSimulation(simulationId, request);
            resultCache.put(simulationId, result);
            updateProgress(simulationId, "COMPLETED", 100.0, "Simulation completed successfully");
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("EOR simulation failed: {}", simulationId, e);
            updateProgress(simulationId, "FAILED", 0.0, "Simulation failed: " + e.getMessage());
            throw new RuntimeException("Simulation failed", e);
        }
    }

    public SimulationProgress getSimulationProgress(String simulationId) {
        return progressMap.get(simulationId);
    }

    public boolean cancelSimulation(String simulationId) {
        AtomicBoolean flag = cancellationMap.get(simulationId);
        if (flag != null) {
            flag.set(true);
            updateProgress(simulationId, "CANCELLED", 0.0, "Simulation cancelled by user");
            return true;
        }
        return false;
    }

    public SimulationResult getSimulationResult(String simulationId) {
        return resultCache.getIfPresent(simulationId);
    }

    public List<String> listSimulations() {
        return new ArrayList<>(progressMap.keySet());
    }

    private String generateSimulationId() {
        return "SIM-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private void initializeSimulation(String simulationId, SimulationRequest request, String type) {
        int gridCount = request.getGridSizeI() * request.getGridSizeJ() * request.getGridSizeK();
        if (gridCount > maxGridCount) {
            throw new IllegalArgumentException("Grid count " + gridCount + " exceeds maximum " + maxGridCount);
        }

        cancellationMap.put(simulationId, new AtomicBoolean(false));

        int totalSteps = (int) Math.ceil(request.getTotalTimeDays() / request.getTimeStepDays());

        SimulationProgress progress = SimulationProgress.builder()
                .simulationId(simulationId)
                .status("INITIALIZING")
                .progress(0.0)
                .currentTimeStep(0)
                .totalTimeSteps(totalSteps)
                .currentPhase("Initializing grid and properties")
                .startTime(LocalDateTime.now())
                .message(type + " simulation initialized")
                .build();

        progressMap.put(simulationId, progress);
    }

    private SimulationResult executeBlackOilSimulation(String simulationId, SimulationRequest request) throws InterruptedException {
        int nx = request.getGridSizeI();
        int ny = request.getGridSizeJ();
        int nz = request.getGridSizeK();
        double totalTime = request.getTotalTimeDays();
        double dt = request.getTimeStepDays();
        int nSteps = (int) Math.ceil(totalTime / dt);

        validateGridSize(nx, ny, nz);

        double[][][] pressure = new double[nx][ny][nz];
        double[][][] oilSat = new double[nx][ny][nz];
        double[][][] waterSat = new double[nx][ny][nz];

        initializeGridProperties(request, pressure, oilSat, waterSat);

        List<Double> timeSteps = new ArrayList<>();
        List<Double> oilRates = new ArrayList<>();
        List<Double> waterRates = new ArrayList<>();
        List<Double> avgPressures = new ArrayList<>();
        List<Double> avgOilSats = new ArrayList<>();
        List<Double> avgWaterSats = new ArrayList<>();

        double totalOil = 0.0;
        double totalWater = 0.0;

        for (int step = 0; step < nSteps; step++) {
            if (isCancelled(simulationId)) break;

            double currentTime = step * dt;
            updateProgress(simulationId, "RUNNING", step * 100.0 / nSteps, "Running time step " + (step + 1) + "/" + nSteps);

            solvePressureEquation(request, pressure, oilSat, waterSat, dt, nx, ny, nz, step);

            double[] production = calculateProduction(request, pressure, oilSat, waterSat, nx, ny, nz, dt);
            totalOil += production[0];
            totalWater += production[1];

            double avgP = calculateAverage(pressure, nx, ny, nz);
            double avgSo = calculateAverage(oilSat, nx, ny, nz);
            double avgSw = calculateAverage(waterSat, nx, ny, nz);

            timeSteps.add(currentTime);
            oilRates.add(production[0]);
            waterRates.add(production[1]);
            avgPressures.add(avgP);
            avgOilSats.add(avgSo);
            avgWaterSats.add(avgSw);

            Thread.sleep(10);
        }

        return buildSimulationResult(simulationId, request, "BLACK_OIL", timeSteps, oilRates, waterRates,
                avgPressures, avgOilSats, avgWaterSats, totalOil, totalWater,
                pressure, oilSat, waterSat, nx, ny, nz, nSteps);
    }

    private SimulationResult executeWaterFloodingSimulation(String simulationId, SimulationRequest request) throws InterruptedException {
        int nx = request.getGridSizeI();
        int ny = request.getGridSizeJ();
        int nz = request.getGridSizeK();
        double totalTime = request.getTotalTimeDays();
        double dt = request.getTimeStepDays();
        int nSteps = (int) Math.ceil(totalTime / dt);

        validateGridSize(nx, ny, nz);

        double[][][] pressure = new double[nx][ny][nz];
        double[][][] oilSat = new double[nx][ny][nz];
        double[][][] waterSat = new double[nx][ny][nz];

        initializeGridProperties(request, pressure, oilSat, waterSat);

        List<Double> timeSteps = new ArrayList<>();
        List<Double> oilRates = new ArrayList<>();
        List<Double> waterRates = new ArrayList<>();
        List<Double> avgPressures = new ArrayList<>();
        List<Double> avgOilSats = new ArrayList<>();
        List<Double> avgWaterSats = new ArrayList<>();

        double totalOil = 0.0;
        double totalWater = 0.0;
        double totalInjected = 0.0;

        for (int step = 0; step < nSteps; step++) {
            if (isCancelled(simulationId)) break;

            double currentTime = step * dt;
            updateProgress(simulationId, "RUNNING", step * 100.0 / nSteps, "Water flooding step " + (step + 1) + "/" + nSteps);

            injectWater(request, pressure, waterSat, nx, ny, nz, dt);

            solvePressureEquation(request, pressure, oilSat, waterSat, dt, nx, ny, nz, step);

            double[] production = calculateProduction(request, pressure, oilSat, waterSat, nx, ny, nz, dt);
            totalOil += production[0];
            totalWater += production[1];
            totalInjected += request.getInjectionRate() * dt;

            double avgP = calculateAverage(pressure, nx, ny, nz);
            double avgSo = calculateAverage(oilSat, nx, ny, nz);
            double avgSw = calculateAverage(waterSat, nx, ny, nz);

            timeSteps.add(currentTime);
            oilRates.add(production[0]);
            waterRates.add(production[1]);
            avgPressures.add(avgP);
            avgOilSats.add(avgSo);
            avgWaterSats.add(avgSw);

            Thread.sleep(10);
        }

        SimulationResult result = buildSimulationResult(simulationId, request, "WATER_FLOODING", timeSteps, oilRates, waterRates,
                avgPressures, avgOilSats, avgWaterSats, totalOil, totalWater,
                pressure, oilSat, waterSat, nx, ny, nz, nSteps);
        result.setTotalWaterInjection(totalInjected);
        return result;
    }

    private SimulationResult executeEORSimulation(String simulationId, SimulationRequest request) throws InterruptedException {
        int nx = request.getGridSizeI();
        int ny = request.getGridSizeJ();
        int nz = request.getGridSizeK();
        double totalTime = request.getTotalTimeDays();
        double dt = request.getTimeStepDays();
        int nSteps = (int) Math.ceil(totalTime / dt);

        validateGridSize(nx, ny, nz);

        double[][][] pressure = new double[nx][ny][nz];
        double[][][] oilSat = new double[nx][ny][nz];
        double[][][] waterSat = new double[nx][ny][nz];
        double[][][] polymerConc = new double[nx][ny][nz];
        double[][][] surfactantConc = new double[nx][ny][nz];

        initializeGridProperties(request, pressure, oilSat, waterSat);

        List<Double> timeSteps = new ArrayList<>();
        List<Double> oilRates = new ArrayList<>();
        List<Double> waterRates = new ArrayList<>();
        List<Double> gasRates = new ArrayList<>();
        List<Double> avgPressures = new ArrayList<>();
        List<Double> avgOilSats = new ArrayList<>();
        List<Double> avgWaterSats = new ArrayList<>();

        double totalOil = 0.0;
        double totalWater = 0.0;
        double totalGas = 0.0;
        double totalInjected = 0.0;

        for (int step = 0; step < nSteps; step++) {
            if (isCancelled(simulationId)) break;

            double currentTime = step * dt;
            updateProgress(simulationId, "RUNNING", step * 100.0 / nSteps, "EOR simulation step " + (step + 1) + "/" + nSteps);

            injectEORChemicals(request, polymerConc, surfactantConc, waterSat, nx, ny, nz, dt);

            solveEORPressureEquation(request, pressure, oilSat, waterSat, polymerConc, surfactantConc, dt, nx, ny, nz, step);

            double[] production = calculateEORProduction(request, pressure, oilSat, waterSat, polymerConc, surfactantConc, nx, ny, nz, dt);
            totalOil += production[0];
            totalWater += production[1];
            totalGas += production[2];
            totalInjected += request.getInjectionRate() * dt;

            double avgP = calculateAverage(pressure, nx, ny, nz);
            double avgSo = calculateAverage(oilSat, nx, ny, nz);
            double avgSw = calculateAverage(waterSat, nx, ny, nz);

            timeSteps.add(currentTime);
            oilRates.add(production[0]);
            waterRates.add(production[1]);
            gasRates.add(production[2]);
            avgPressures.add(avgP);
            avgOilSats.add(avgSo);
            avgWaterSats.add(avgSw);

            Thread.sleep(15);
        }

        SimulationResult result = buildSimulationResult(simulationId, request, "EOR", timeSteps, oilRates, waterRates,
                avgPressures, avgOilSats, avgWaterSats, totalOil, totalWater,
                pressure, oilSat, waterSat, nx, ny, nz, nSteps);
        result.setTotalGasProduction(totalGas);
        result.setTotalWaterInjection(totalInjected);
        result.setGasProductionRates(gasRates);

        double residualOilSat = request.getRelativePermeabilityParams() != null ?
                request.getRelativePermeabilityParams().getOrDefault("residualOilSaturation", 0.2) : 0.2;
        double sweepEff = calculateSweepEfficiency(waterSat, nx, ny, nz, residualOilSat);
        double displacementEff = calculateDisplacementEfficiency(oilSat, nx, ny, nz, request.getInitialOilSaturation(), residualOilSat);

        result.setResidualOilSaturation(residualOilSat);
        result.setSweepEfficiency(sweepEff);
        result.setDisplacementEfficiency(displacementEff);

        return result;
    }

    private void validateGridSize(int nx, int ny, int nz) {
        int total = nx * ny * nz;
        if (total > maxGridCount) {
            throw new IllegalArgumentException("Grid count exceeds maximum allowed");
        }
    }

    private void initializeGridProperties(SimulationRequest request, double[][][] pressure,
                                         double[][][] oilSat, double[][][] waterSat) {
        int nx = pressure.length;
        int ny = pressure[0].length;
        int nz = pressure[0][0].length;

        double initP = request.getInitialPressure() != null ? request.getInitialPressure() : 200.0;
        double initSo = request.getInitialOilSaturation() != null ? request.getInitialOilSaturation() : 0.7;
        double initSw = request.getInitialWaterSaturation() != null ? request.getInitialWaterSaturation() : 0.3;

        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                for (int k = 0; k < nz; k++) {
                    pressure[i][j][k] = initP + (nz - k) * 0.1;
                    oilSat[i][j][k] = initSo;
                    waterSat[i][j][k] = initSw;
                }
            }
        }
    }

    private void solvePressureEquation(SimulationRequest request, double[][][] pressure, double[][][] oilSat,
                                   double[][][] waterSat, double dt, int nx, int ny, int nz, int step) {

        double porosity = request.getPorosity() != null ? request.getPorosity() : 0.3;
        double permX = request.getPermeabilityX() != null ? request.getPermeabilityX() : 100.0;
        double muOil = request.getOilViscosity() != null ? request.getOilViscosity() : 1.0;
        double muWater = request.getWaterViscosity() != null ? request.getWaterViscosity() : 0.5;
        double rockComp = request.getRockCompressibility() != null ? request.getRockCompressibility() : 1e-5;

        int n = nx * ny * nz;
        double[] b = new double[n];
        RealMatrix A = new Array2DRowRealMatrix(n, n);

        int idx = 0;
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                for (int k = 0; k < nz; k++) {
                    double so = oilSat[i][j][k];
                    double sw = waterSat[i][j][k];

                    double kro = calculateRelativePermeabilityOil(so, sw);
                    double krw = calculateRelativePermeabilityWater(so, sw);

                    double mobility = (kro / muOil) + (krw / muWater);
                    double transmissibility = permX * mobility / porosity;

                    int current = idx;

                    A.setEntry(current, current, 1.0 + 4.0 * transmissibility + rockComp * porosity / dt);

                    if (i > 0) A.setEntry(current, current - ny * nz, -transmissibility);
                    if (i < nx - 1) A.setEntry(current, current + ny * nz, -transmissibility);
                    if (j > 0) A.setEntry(current, current - nz, -transmissibility);
                    if (j < ny - 1) A.setEntry(current, current + nz, -transmissibility);
                    if (k > 0) A.setEntry(current, current - 1, -transmissibility);
                    if (k < nz - 1) A.setEntry(current, current + 1, -transmissibility);

                    b[current] = pressure[i][j][k] * (1.0 + rockComp / dt);

                    idx++;
                }
            }
        }

        DecompositionSolver solver = new LUDecomposition(A).getSolver();
        double[] solution = solver.solve(b);

        idx = 0;
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                for (int k = 0; k < nz; k++) {
                    pressure[i][j][k] = solution[idx];
                    idx++;
                }
            }
        }

        updateSaturations(request, pressure, oilSat, waterSat, dt, nx, ny, nz);
    }

    private void injectWater(SimulationRequest request, double[][][] pressure, double[][][] waterSat,
                        int nx, int ny, int nz, double dt) {
        double injectionRate = request.getInjectionRate() != null ? request.getInjectionRate() : 1000.0;
        double porosity = request.getPorosity() != null ? request.getPorosity() : 0.3;

        if (request.getInjectionWells() != null && !request.getInjectionWells().isEmpty()) {
            for (String well : request.getInjectionWells()) {
                int wellI = nx / 2;
                int wellJ = ny / 2;

                for (int k = 0; k < nz; k++) {
                    double volume = injectionRate * dt / (porosity * 10000.0);
                    waterSat[wellI][wellJ][k] = Math.min(1.0 - 0.2, waterSat[wellI][wellJ][k] + volume);
                }
            }
        } else {
            for (int k = 0; k < nz; k++) {
                for (int j = 0; j < ny; j++) {
                    double volume = injectionRate * dt / (nx * ny * nz * porosity * 10000.0);
                    waterSat[0][j][k] = Math.min(1.0 - 0.2, waterSat[0][j][k] + volume);
                }
            }
        }
    }

    private void injectEORChemicals(SimulationRequest request, double[][][] polymerConc,
                                double[][][] surfactantConc, double[][][] waterSat,
                                int nx, int ny, int nz, double dt) {
        double polymerConcVal = request.getPolymerConcentration() != null ? request.getPolymerConcentration() : 0.001;
        double surfactantConcVal = request.getSurfactantConcentration() != null ? request.getSurfactantConcentration() : 0.0005;

        for (int k = 0; k < nz; k++) {
            for (int j = 0; j < ny; j++) {
                if (waterSat[0][j][k] > 0.5) {
                    polymerConc[0][j][k] = polymerConcVal * (1.0 - Math.exp(-dt / 10.0));
                    surfactantConc[0][j][k] = surfactantConcVal * (1.0 - Math.exp(-dt / 10.0));
                }
            }
        }

        for (int i = 1; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                for (int k = 0; k < nz; k++) {
                    polymerConc[i][j][k] = 0.95 * polymerConc[i-1][j][k] + 0.05 * polymerConc[i][j][k];
                    surfactantConc[i][j][k] = 0.95 * surfactantConc[i-1][j][k] + 0.05 * surfactantConc[i][j][k];
                }
            }
        }
    }

    private void solveEORPressureEquation(SimulationRequest request, double[][][] pressure, double[][][] oilSat,
                                     double[][][] waterSat, double[][][] polymerConc, double[][][] surfactantConc,
                                     double dt, int nx, int ny, int nz, int step) {

        double muWater = request.getWaterViscosity() != null ? request.getWaterViscosity() : 0.5;
        double polymerFactor = 1.0 + 5.0 * polymerConc[nx/2][ny/2][nz/2] * 1000;

        solvePressureEquation(request, pressure, oilSat, waterSat, dt, nx, ny, nz, step);
    }

    private double[] calculateProduction(SimulationRequest request, double[][][] pressure, double[][][] oilSat,
                                      double[][][] waterSat, int nx, int ny, int nz, double dt) {

        double productionRate = request.getProductionRate() != null ? request.getProductionRate() : 500.0;
        double muOil = request.getOilViscosity() != null ? request.getOilViscosity() : 1.0;
        double muWater = request.getWaterViscosity() != null ? request.getWaterViscosity() : 0.5;

        double avgSo = calculateAverage(oilSat, nx, ny, nz);
        double avgSw = calculateAverage(waterSat, nx, ny, nz);

        double kro = calculateRelativePermeabilityOil(avgSo, avgSw);
        double krw = calculateRelativePermeabilityWater(avgSo, avgSw);

        double totalMobility = kro / muOil + krw / muWater;
        double fractionalFlowOil = (kro / muOil) / totalMobility;
        double fractionalFlowWater = (krw / muWater) / totalMobility;

        double oilProd = productionRate * fractionalFlowOil;
        double waterProd = productionRate * fractionalFlowWater;

        return new double[]{oilProd, waterProd};
    }

    private double[] calculateEORProduction(SimulationRequest request, double[][][] pressure, double[][][] oilSat,
                                     double[][][] waterSat, double[][][] polymerConc, double[][][] surfactantConc,
                                     int nx, int ny, int nz, double dt) {

        double[] baseProduction = calculateProduction(request, pressure, oilSat, waterSat, nx, ny, nz, dt);

        double avgPolymer = calculateAverage(polymerConc, nx, ny, nz);
        double avgSurfactant = calculateAverage(surfactantConc, nx, ny, nz);

        double eorFactor = 1.0 + avgPolymer * 500 + avgSurfactant * 1000;

        double oilProd = baseProduction[0] * eorFactor;
        double waterProd = baseProduction[1];
        double gasProd = baseProduction[0] * 0.1;

        return new double[]{oilProd, waterProd, gasProd};
    }

    private double calculateRelativePermeabilityOil(double so, double sw) {
        double sor = 0.2;
        double swc = 0.2;
        double soStar = Math.max(0.0, (so - sor) / (1.0 - sor - swc));
        return Math.pow(soStar, 2.0);
    }

    private double calculateRelativePermeabilityWater(double so, double sw) {
        double swc = 0.2;
        double swStar = Math.max(0.0, (sw - swc) / (1.0 - swc));
        return 0.3 * Math.pow(swStar, 2.5);
    }

    private void updateSaturations(SimulationRequest request, double[][][] pressure, double[][][] oilSat,
                            double[][][] waterSat, double dt, int nx, int ny, int nz) {

        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                for (int k = 0; k < nz; k++) {
                    double dp = 0.0;
                    if (i < nx - 1) dp += pressure[i+1][j][k] - pressure[i][j][k];
                    if (i > 0) dp += pressure[i-1][j][k] - pressure[i][j][k];
                    if (j < ny - 1) dp += pressure[i][j+1][k] - pressure[i][j][k];
                    if (j > 0) dp += pressure[i][j-1][k] - pressure[i][j][k];
                    if (k < nz - 1) dp += pressure[i][j][k+1] - pressure[i][j][k];
                    if (k > 0) dp += pressure[i][j][k-1] - pressure[i][j][k];

                    double flow = dp * 0.0001 * dt;
                    double newSw = Math.max(0.2, Math.min(0.8, waterSat[i][j][k] + flow * 0.001));
                    waterSat[i][j][k] = newSw;
                    oilSat[i][j][k] = 1.0 - newSw;
                }
            }
        }
    }

    private double calculateAverage(double[][][] array, int nx, int ny, int nz) {
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                for (int k = 0; k < nz; k++) {
                    sum += array[i][j][k];
                    count++;
                }
            }
        }
        return count > 0 ? sum / count : 0.0;
    }

    private double calculateSweepEfficiency(double[][][] waterSat, int nx, int ny, int nz, double residualOilSat) {
        int swept = 0;
        int total = 0;
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                for (int k = 0; k < nz; k++) {
                    total++;
                    if (waterSat[i][j][k] > 0.5) {
                        swept++;
                    }
                }
            }
        }
        return (double) swept / total;
    }

    private double calculateDisplacementEfficiency(double[][][] oilSat, int nx, int ny, int nz,
                                            double initialSo, double residualSo) {
        double avgSo = calculateAverage(oilSat, nx, ny, nz);
        return (initialSo - avgSo) / (initialSo - residualSo);
    }

    private SimulationResult buildSimulationResult(String simulationId, SimulationRequest request,
                                               String type, List<Double> timeSteps,
                                               List<Double> oilRates, List<Double> waterRates,
                                               List<Double> avgPressures,
                                               List<Double> avgOilSats,
                                               List<Double> avgWaterSats,
                                               double totalOil, double totalWater,
                                               double[][][] pressure,
                                               double[][][] oilSat,
                                               double[][][] waterSat,
                                               int nx, int ny, int nz, int nSteps) {

        LocalDateTime startTime = progressMap.get(simulationId).getStartTime();
        LocalDateTime endTime = LocalDateTime.now();
        double duration = java.time.Duration.between(startTime, endTime).toMinutes();

        SimulationResult result = SimulationResult.builder()
                .simulationId(simulationId)
                .simulationType(type)
                .reservoirName(request.getReservoirName())
                .status("COMPLETED")
                .startTime(startTime)
                .endTime(endTime)
                .durationMinutes(duration)
                .totalOilProduction(totalOil)
                .totalWaterProduction(totalWater)
                .cumulativeOilRecovery(totalOil)
                .oilRecoveryFactor(totalOil / (nx * ny * nz * 1000.0))
                .waterCut(totalWater > 0 ? totalWater / (totalOil + totalWater) : 0.0)
                .finalAveragePressure(avgPressures.get(avgPressures.size() - 1))
                .finalAverageOilSaturation(avgOilSats.get(avgOilSats.size() - 1))
                .finalAverageWaterSaturation(avgWaterSats.get(avgWaterSats.size() - 1))
                .completedTimeSteps(timeSteps.size())
                .totalTimeSteps(nSteps)
                .gridCount(nx * ny * nz)
                .activeGridCount(nx * ny * nz)
                .timeSteps(timeSteps)
                .oilProductionRates(oilRates)
                .waterProductionRates(waterRates)
                .averagePressures(avgPressures)
                .averageOilSaturations(avgOilSats)
                .averageWaterSaturations(avgWaterSats)
                .finalGridData(buildGridData(pressure, oilSat, waterSat, nx, ny, nz))
                .createdAt(LocalDateTime.now())
                .build();

        return result;
    }

    private List<GridBlockData> buildGridData(double[][][] pressure, double[][][] oilSat,
                                              double[][][] waterSat, int nx, int ny, int nz) {
        List<GridBlockData> gridData = new ArrayList<>();
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                for (int k = 0; k < nz; k++) {
                    double so = oilSat[i][j][k];
                    double sw = waterSat[i][j][k];
                    gridData.add(GridBlockData.builder()
                            .i(i)
                            .j(j)
                            .k(k)
                            .pressure(pressure[i][j][k])
                            .oilSaturation(so)
                            .waterSaturation(sw)
                            .gasSaturation(1.0 - so - sw)
                            .oilRelativePermeability(calculateRelativePermeabilityOil(so, sw))
                            .waterRelativePermeability(calculateRelativePermeabilityWater(so, sw))
                            .build());
                }
            }
        }
        return gridData;
    }

    private void updateProgress(String simulationId, String status, double progress, String message) {
        SimulationProgress sp = progressMap.get(simulationId);
        if (sp != null) {
            sp.setStatus(status);
            sp.setProgress(progress);
            sp.setMessage(message);
            sp.setCurrentPhase(message);
            if (sp.getTotalTimeSteps() != null && sp.getCurrentTimeStep() != null) {
                sp.setRemainingTimeMinutes((100.0 - progress) / progress *
                        java.time.Duration.between(sp.getStartTime(), LocalDateTime.now()).toMinutes());
                sp.setEstimatedEndTime(LocalDateTime.now().plusMinutes((long) sp.getRemainingTimeMinutes()));
            }
            progressMap.put(simulationId, sp);
        }
    }

    private boolean isCancelled(String simulationId) {
        AtomicBoolean flag = cancellationMap.get(simulationId);
        return flag != null && flag.get();
    }

    public CompletableFuture<SimulationResult> simulationFallback(SimulationRequest request, Exception e) {
        log.error("Simulation fallback triggered for request: {}", request, e);
        SimulationResult result = SimulationResult.builder()
                .simulationId("FALLBACK-" + System.currentTimeMillis())
                .status("FAILED")
                .errorMessage("Service unavailable: " + e.getMessage())
                .build();
        return CompletableFuture.completedFuture(result);
    }
}
