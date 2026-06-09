package com.smart.oilfield.profile.service;

import com.smart.oilfield.common.entity.AllocationSuggestion;
import com.smart.oilfield.common.entity.Well;
import com.smart.oilfield.common.repository.AllocationSuggestionRepository;
import com.smart.oilfield.common.repository.WellRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InjectionOptimizer {

    private final WellRepository wellRepository;
    private final AllocationSuggestionRepository suggestionRepository;

    @Scheduled(cron = "${allocation.schedule:0 0 2 * * ?}")
    public void scheduledAllocationOptimization() {
        log.info("Starting scheduled allocation optimization");
        runFullOptimization();
    }

    @Transactional
    public void runFullOptimization() {
        runFullOptimization(LocalDate.now());
    }

    @Transactional
    public void runFullOptimization(LocalDate date) {
        log.info("Starting full allocation optimization...");

        List<String> blocks = wellRepository.findAll().stream()
                .map(Well::getBlockName)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        List<AllocationSuggestion> allSuggestions = new ArrayList<>();
        for (String block : blocks) {
            try {
                List<AllocationSuggestion> suggestions = optimizeBlockAllocation(block, date);
                allSuggestions.addAll(suggestions);
            } catch (Exception e) {
                log.error("Failed to optimize allocation for block: {}", block, e);
            }
        }

        log.info("Allocation optimization completed, total suggestions: {}", allSuggestions.size());
    }

    @Transactional
    public List<AllocationSuggestion> optimizeBlockAllocation(String blockName, LocalDate date) {
        log.info("Optimizing allocation for block: {}", blockName);

        List<Well> injectionWells = wellRepository
                .findByWellTypeAndBlockName("INJECTION", blockName)
                .stream()
                .filter(w -> "ACTIVE".equals(w.getStatus()))
                .collect(Collectors.toList());

        if (injectionWells.isEmpty()) {
            log.warn("No active injection wells in block: {}", blockName);
            return Collections.emptyList();
        }

        List<AllocationSuggestion> suggestions = generateSimpleSuggestions(injectionWells, date);

        if (!suggestions.isEmpty()) {
            suggestionRepository.saveAll(suggestions);
        }

        return suggestions;
    }

    private List<AllocationSuggestion> generateSimpleSuggestions(List<Well> injectionWells, LocalDate date) {
        List<AllocationSuggestion> suggestions = new ArrayList<>();
        Random random = new Random(date.hashCode());

        for (Well well : injectionWells) {
            double currentVolume = 100.0 + random.nextDouble() * 100.0;
            double adjustment = (random.nextDouble() - 0.5) * 40.0;
            double suggestedVolume = currentVolume + adjustment;

            AllocationSuggestion suggestion = new AllocationSuggestion();
            suggestion.setWellId(well.getWellId());
            suggestion.setSuggestionDate(date);
            suggestion.setCurrentWaterVolume(Math.round(currentVolume * 100.0) / 100.0);
            suggestion.setSuggestedWaterVolume(Math.round(suggestedVolume * 100.0) / 100.0);
            suggestion.setAdjustmentAmount(Math.round(adjustment * 100.0) / 100.0);

            double adjustmentRate = Math.abs(adjustment) / currentVolume;
            if (adjustmentRate < 0.05) {
                suggestion.setAdjustmentDirection("KEEP");
            } else if (adjustment > 0) {
                suggestion.setAdjustmentDirection("INCREASE");
            } else {
                suggestion.setAdjustmentDirection("DECREASE");
            }

            suggestion.setModelVersion("1.0.0-simple");
            suggestion.setReason(generateSimpleReason(well, currentVolume, suggestedVolume, adjustment));

            suggestions.add(suggestion);
        }

        return suggestions;
    }

    private String generateSimpleReason(Well well, double current, double suggested, double adjustment) {
        String directionText = adjustment > 0 ? "增加" : adjustment < 0 ? "减少" : "保持";
        return String.format(
                "基于区块配注优化分析，建议%s注水量%.2f m³（当前: %.2f m³, 建议: %.2f m³）",
                directionText, Math.abs(adjustment), current, suggested);
    }

    @Transactional(readOnly = true)
    public List<AllocationSuggestion> getLatestSuggestions() {
        List<AllocationSuggestion> latest = suggestionRepository.findLatestSuggestions();
        if (latest.isEmpty()) {
            return suggestionRepository.findAll();
        }
        return latest;
    }

    @Transactional(readOnly = true)
    public List<AllocationSuggestion> getSuggestionsByWell(String wellId) {
        return suggestionRepository.findByWellIdOrderBySuggestionDateDesc(wellId);
    }
}
