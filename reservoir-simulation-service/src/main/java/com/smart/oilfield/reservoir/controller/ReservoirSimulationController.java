package com.smart.oilfield.reservoir.controller;

import com.smart.oilfield.reservoir.dto.SimulationProgress;
import com.smart.oilfield.reservoir.dto.SimulationRequest;
import com.smart.oilfield.reservoir.dto.SimulationResult;
import com.smart.oilfield.reservoir.service.ReservoirSimulationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
public class ReservoirSimulationController {

    private final ReservoirSimulationService simulationService;

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runSimulation(@RequestBody SimulationRequest request) {
        log.info("Received simulation request: type={}, reservoir={}",
                request.getSimulationType(), request.getReservoirName());

        String simulationType = request.getSimulationType() != null ?
                request.getSimulationType().toUpperCase() : "BLACK_OIL";

        CompletableFuture<SimulationResult> future;
        switch (simulationType) {
            case "WATER_FLOODING":
                future = simulationService.runWaterFloodingSimulation(request);
                break;
            case "EOR":
                future = simulationService.runEORSimulation(request);
                break;
            case "BLACK_OIL":
            default:
                future = simulationService.runBlackOilSimulation(request);
                break;
        }

        SimulationProgress progress = null;
        try {
            Thread.sleep(100);
            List<String> simulations = simulationService.listSimulations();
            if (!simulations.isEmpty()) {
                progress = simulationService.getSimulationProgress(
                        simulations.get(simulations.size() - 1));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<String, Object> response = new HashMap<>();
        if (progress != null) {
            response.put("simulationId", progress.getSimulationId());
            response.put("status", progress.getStatus());
            response.put("message", "Simulation task submitted successfully");
        } else {
            response.put("message", "Simulation task submitted");
        }

        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SimulationResult> getSimulationResult(@PathVariable String id) {
        log.info("Getting simulation result for: {}", id);
        SimulationResult result = simulationService.getSimulationResult(id);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/progress")
    public ResponseEntity<SimulationProgress> getSimulationProgress(@PathVariable String id) {
        log.info("Getting simulation progress for: {}", id);
        SimulationProgress progress = simulationService.getSimulationProgress(id);
        if (progress == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(progress);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> cancelSimulation(@PathVariable String id) {
        log.info("Cancelling simulation: {}", id);
        boolean cancelled = simulationService.cancelSimulation(id);

        Map<String, Object> response = new HashMap<>();
        response.put("simulationId", id);
        if (cancelled) {
            response.put("status", "CANCELLED");
            response.put("message", "Simulation cancelled successfully");
            return ResponseEntity.ok(response);
        } else {
            response.put("status", "NOT_FOUND");
            response.put("message", "Simulation not found or already completed");
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/list")
    public ResponseEntity<List<String>> listSimulations() {
        log.info("Listing all simulations");
        List<String> simulations = simulationService.listSimulations();
        return ResponseEntity.ok(simulations);
    }
}
