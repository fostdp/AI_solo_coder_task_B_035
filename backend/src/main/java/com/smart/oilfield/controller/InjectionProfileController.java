package com.smart.oilfield.controller;

import com.smart.oilfield.dto.ProfileAdjustmentRequest;
import com.smart.oilfield.entity.InjectionProfile;
import com.smart.oilfield.service.InjectionProfileOptimizer;
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
@RequestMapping("/injection-profile")
@RequiredArgsConstructor
public class InjectionProfileController {

    private final InjectionProfileOptimizer profileOptimizer;

    @PostMapping("/adjust")
    public ResponseEntity<List<InjectionProfile>> adjustWellProfile(
            @RequestBody ProfileAdjustmentRequest request) {
        log.info("API: Adjusting injection profile for well: {}", request.getWellId());
        List<InjectionProfile> results = profileOptimizer.adjustWellProfile(request);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/well/{wellId}")
    public ResponseEntity<List<InjectionProfile>> getLatestProfile(@PathVariable String wellId) {
        log.info("API: Getting latest profile for well: {}", wellId);
        List<InjectionProfile> results = profileOptimizer.getLatestProfile(wellId);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/well/{wellId}/date/{date}")
    public ResponseEntity<List<InjectionProfile>> getProfileForDate(
            @PathVariable String wellId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        log.info("API: Getting profile for well: {} on date: {}", wellId, date);
        List<InjectionProfile> results = profileOptimizer.getProfileForDate(wellId, date);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/well/{wellId}/history")
    public ResponseEntity<List<InjectionProfile>> getProfileHistory(
            @PathVariable String wellId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        log.info("API: Getting profile history for well: {} from {} to {}", wellId, startDate, endDate);
        List<InjectionProfile> results = profileOptimizer.getProfileHistory(wellId, startDate, endDate);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/well/{wellId}/summary")
    public ResponseEntity<Map<String, Object>> getProfileSummary(@PathVariable String wellId) {
        log.info("API: Getting profile summary for well: {}", wellId);
        Map<String, Object> result = profileOptimizer.getProfileSummary(wellId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/pending")
    public ResponseEntity<List<InjectionProfile>> getPendingAdjustments() {
        log.info("API: Getting pending profile adjustments");
        List<InjectionProfile> results = profileOptimizer.getPendingAdjustments();
        return ResponseEntity.ok(results);
    }

    @PostMapping("/run-all")
    public ResponseEntity<String> runAllProfilesAdjustment() {
        log.info("API: Running profile adjustment for all wells");
        profileOptimizer.autoAdjustAllProfiles();
        return ResponseEntity.ok("Profile adjustment triggered for all wells");
    }
}
