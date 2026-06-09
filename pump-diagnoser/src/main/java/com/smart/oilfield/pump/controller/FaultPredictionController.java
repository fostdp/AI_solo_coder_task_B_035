package com.smart.oilfield.pump.controller;

import com.smart.oilfield.common.entity.FaultPrediction;
import com.smart.oilfield.pump.dto.FaultPredictionRequest;
import com.smart.oilfield.pump.service.OnnxInferenceService;
import com.smart.oilfield.pump.service.PumpingUnitFaultPredictor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/fault-prediction")
@RequiredArgsConstructor
public class FaultPredictionController {

    private final PumpingUnitFaultPredictor faultPredictor;
    private final OnnxInferenceService onnxInferenceService;

    @PostMapping("/predict")
    public ResponseEntity<List<FaultPrediction>> predictWellFaults(
            @RequestBody FaultPredictionRequest request) {
        log.info("API: Predicting faults for well: {}", request.getWellId());
        List<FaultPrediction> results = faultPredictor.predictWellFaults(request);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/well/{wellId}")
    public ResponseEntity<List<FaultPrediction>> getLatestPredictions(@PathVariable String wellId) {
        log.info("API: Getting latest fault predictions for well: {}", wellId);
        List<FaultPrediction> results = faultPredictor.getLatestPredictions(wellId);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/unacknowledged")
    public ResponseEntity<List<FaultPrediction>> getUnacknowledgedPredictions() {
        log.info("API: Getting unacknowledged fault predictions");
        List<FaultPrediction> results = faultPredictor.getUnacknowledgedPredictions();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/high-priority")
    public ResponseEntity<List<FaultPrediction>> getHighPriorityPredictions() {
        log.info("API: Getting high priority fault predictions");
        List<FaultPrediction> results = faultPredictor.getHighPriorityPredictions();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/well/{wellId}/summary")
    public ResponseEntity<Map<String, Object>> getPredictionSummary(@PathVariable String wellId) {
        log.info("API: Getting fault prediction summary for well: {}", wellId);
        Map<String, Object> result = faultPredictor.getPredictionSummary(wellId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/all-probabilities")
    public ResponseEntity<Map<String, Double>> getAllWellFaultProbabilities() {
        log.info("API: Getting fault probabilities for all wells");
        Map<String, Double> results = faultPredictor.getAllWellFaultProbabilities();
        return ResponseEntity.ok(results);
    }

    @PostMapping("/{predictionId}/acknowledge")
    public ResponseEntity<FaultPrediction> acknowledgePrediction(
            @PathVariable String predictionId,
            @RequestParam(required = false) String acknowledgedBy) {
        log.info("API: Acknowledging fault prediction: {}", predictionId);
        FaultPrediction result = faultPredictor.acknowledgePrediction(
                predictionId, acknowledgedBy != null ? acknowledgedBy : "system");
        return result != null ? ResponseEntity.ok(result) : ResponseEntity.notFound().build();
    }

    @PostMapping("/{predictionId}/record-fault")
    public ResponseEntity<FaultPrediction> recordActualFault(
            @PathVariable String predictionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime actualFaultTime) {
        log.info("API: Recording actual fault for prediction: {}", predictionId);
        if (actualFaultTime == null) {
            actualFaultTime = LocalDateTime.now();
        }
        FaultPrediction result = faultPredictor.recordActualFault(predictionId, actualFaultTime);
        return result != null ? ResponseEntity.ok(result) : ResponseEntity.notFound().build();
    }

    @GetMapping("/block-predictions")
    public ResponseEntity<Map<String, Object>> getBlockPredictions(
            @RequestParam(required = false) String blockName) {
        log.info("API: Getting fault predictions for block: {}", blockName);
        Map<String, Object> result = faultPredictor.getBlockPredictions(blockName);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/run-all")
    public ResponseEntity<String> runAllWellsPrediction() {
        log.info("API: Running fault prediction for all wells");
        faultPredictor.autoPredictAllWells();
        return ResponseEntity.ok("Fault prediction triggered for all wells");
    }

    @GetMapping("/onnx-performance")
    public ResponseEntity<Map<String, Object>> getOnnxPerformance() {
        log.info("API: Getting ONNX inference performance metrics");
        Map<String, Object> performance = onnxInferenceService.getInferencePerformance();
        return ResponseEntity.ok(performance);
    }

    @GetMapping("/compare-inference")
    public ResponseEntity<Map<String, Object>> compareTraditionalVsOnnx() {
        log.info("API: Comparing traditional vs ONNX inference performance");
        Map<String, Object> comparison = faultPredictor.compareTraditionalVsOnnxInference();
        return ResponseEntity.ok(comparison);
    }
}
