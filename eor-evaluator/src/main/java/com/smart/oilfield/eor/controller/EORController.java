package com.smart.oilfield.eor.controller;

import com.smart.oilfield.common.dto.EOREvaluationRequest;
import com.smart.oilfield.common.entity.EOREvaluation;
import com.smart.oilfield.eor.service.EOREvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/eor")
@RequiredArgsConstructor
public class EORController {

    private final EOREvaluationService eorEvaluationService;

    @PostMapping("/evaluate")
    public ResponseEntity<List<EOREvaluation>> evaluateBlockScenarios(
            @RequestBody EOREvaluationRequest request) {
        log.info("API: Evaluating EOR scenarios for block: {}", request.getBlockName());
        List<EOREvaluation> results = eorEvaluationService.evaluateBlockScenarios(request);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/block/{blockName}")
    public ResponseEntity<List<EOREvaluation>> getLatestEvaluations(@PathVariable String blockName) {
        log.info("API: Getting latest EOR evaluations for block: {}", blockName);
        List<EOREvaluation> results = eorEvaluationService.getLatestEvaluations(blockName);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/block/{blockName}/date/{date}")
    public ResponseEntity<List<EOREvaluation>> getEvaluationsForDate(
            @PathVariable String blockName,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        log.info("API: Getting EOR evaluations for block: {} on date: {}", blockName, date);
        List<EOREvaluation> results = eorEvaluationService.getEvaluationsForDate(blockName, date);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/block/{blockName}/history")
    public ResponseEntity<List<EOREvaluation>> getEvaluationHistory(
            @PathVariable String blockName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        log.info("API: Getting EOR evaluation history for block: {} from {} to {}", blockName, startDate, endDate);
        List<EOREvaluation> results = eorEvaluationService.getEvaluationHistory(blockName, startDate, endDate);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/block/{blockName}/comparative")
    public ResponseEntity<Map<String, Object>> getComparativeAnalysis(@PathVariable String blockName) {
        log.info("API: Getting comparative EOR analysis for block: {}", blockName);
        Map<String, Object> result = eorEvaluationService.getComparativeAnalysis(blockName);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/recommended")
    public ResponseEntity<List<EOREvaluation>> getAllRecommendedScenarios() {
        log.info("API: Getting all recommended EOR scenarios");
        List<EOREvaluation> results = eorEvaluationService.getAllRecommendedScenarios();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/block/{blockName}/top-recommended")
    public ResponseEntity<EOREvaluation> getTopRecommendedScenario(@PathVariable String blockName) {
        log.info("API: Getting top recommended EOR scenario for block: {}", blockName);
        Optional<EOREvaluation> result = eorEvaluationService.getTopRecommendedScenario(blockName);
        return result.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/run-all")
    public ResponseEntity<String> runAllBlocksEvaluation() {
        log.info("API: Running EOR evaluation for all blocks");
        eorEvaluationService.autoEvaluateAllBlocks();
        return ResponseEntity.ok("EOR evaluation triggered for all blocks");
    }
}
