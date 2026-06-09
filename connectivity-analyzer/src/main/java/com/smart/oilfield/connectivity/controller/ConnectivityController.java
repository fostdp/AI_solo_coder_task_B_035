package com.smart.oilfield.connectivity.controller;

import com.smart.oilfield.common.dto.ConnectivityAnalysisRequest;
import com.smart.oilfield.common.entity.WellConnectivity;
import com.smart.oilfield.connectivity.service.WellConnectivityAnalyzer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/connectivity")
@RequiredArgsConstructor
public class ConnectivityController {

    private final WellConnectivityAnalyzer connectivityAnalyzer;

    @PostMapping("/analyze")
    public ResponseEntity<List<WellConnectivity>> analyzeBlockConnectivity(
            @RequestBody ConnectivityAnalysisRequest request) {
        log.info("API: Analyzing connectivity for block: {}", request.getBlockName());
        List<WellConnectivity> results = connectivityAnalyzer.analyzeBlockConnectivity(request);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/well/{wellId}")
    public ResponseEntity<List<WellConnectivity>> getWellConnectivity(@PathVariable String wellId) {
        log.info("API: Getting connectivity for well: {}", wellId);
        List<WellConnectivity> results = connectivityAnalyzer.getLatestConnectivityForWell(wellId);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/date/{date}")
    public ResponseEntity<List<WellConnectivity>> getConnectivityForDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        log.info("API: Getting connectivity for date: {}", date);
        List<WellConnectivity> results = connectivityAnalyzer.getConnectivityForDate(date);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/significant")
    public ResponseEntity<List<WellConnectivity>> getSignificantConnectivity(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        log.info("API: Getting significant connectivity from {} to {}", startDate, endDate);
        List<WellConnectivity> results = connectivityAnalyzer.getSignificantConnectivity(startDate, endDate);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/map-data")
    public ResponseEntity<Map<String, Object>> getConnectivityMapData(
            @RequestParam String blockName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        log.info("API: Getting connectivity map data for block: {}, date: {}", blockName, date);
        Map<String, Object> result = connectivityAnalyzer.getConnectivityMapData(blockName, date);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/results")
    public ResponseEntity<Map<String, Object>> getConnectivityResults(
            @RequestParam(required = false) String blockName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        log.info("API: Getting connectivity results for block: {}, date: {}", blockName, date);
        Map<String, Object> result = connectivityAnalyzer.getConnectivityMapData(
                blockName != null ? blockName : "ALL", date);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/run-all")
    public ResponseEntity<String> runAllBlocksAnalysis() {
        log.info("API: Running connectivity analysis for all blocks");
        connectivityAnalyzer.autoAnalyzeAllBlocks();
        return ResponseEntity.ok("Connectivity analysis triggered for all blocks");
    }
}
