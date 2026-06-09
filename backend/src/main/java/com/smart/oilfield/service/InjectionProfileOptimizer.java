package com.smart.oilfield.service;

import com.smart.oilfield.config.AdvancedFeaturesProperties;
import com.smart.oilfield.dto.ProfileAdjustmentRequest;
import com.smart.oilfield.entity.InjectionProfile;
import com.smart.oilfield.entity.Well;
import com.smart.oilfield.event.ProfileAdjustmentCompletedEvent;
import com.smart.oilfield.event.WellDataReceivedEvent;
import com.smart.oilfield.repository.InjectionProfileRepository;
import com.smart.oilfield.repository.WellRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InjectionProfileOptimizer {

    private final InjectionProfileRepository profileRepository;
    private final WellRepository wellRepository;
    private final AdvancedFeaturesProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final RestTemplate restTemplate = new RestTemplate();

    @Async
    @EventListener
    @Transactional
    public void onDataReceived(WellDataReceivedEvent event) {
        log.info("Checking profile adjustment for well: {}", event.getWellId());
        Well well = wellRepository.findByWellId(event.getWellId()).orElse(null);
        if (well == null || !"injection".equals(well.getWellType())) {
            return;
        }
        if (properties.getProfile().isEnableAutoAdjustment()) {
            ProfileAdjustmentRequest request = new ProfileAdjustmentRequest();
            request.setWellId(event.getWellId());
            request.setProfileDate(LocalDate.now());
            request.setMaintainTotalVolume(true);
            request.setExecuteAutoAdjustment(true);
            adjustWellProfile(request);
        }
    }

    @Scheduled(cron = "${advanced.profile.auto-adjustment-schedule:0 0 4 * * ?}")
    @Transactional
    public void autoAdjustAllProfiles() {
        if (!properties.getProfile().isEnableAutoAdjustment()) {
            log.info("Auto profile adjustment is disabled");
            return;
        }
        log.info("Starting scheduled profile adjustment for all wells");
        List<String> wellIds = profileRepository.findAllWellIdsWithLatestProfile();
        for (String wellId : wellIds) {
            try {
                ProfileAdjustmentRequest request = new ProfileAdjustmentRequest();
                request.setWellId(wellId);
                request.setProfileDate(LocalDate.now());
                request.setMaintainTotalVolume(true);
                request.setExecuteAutoAdjustment(true);
                adjustWellProfile(request);
            } catch (Exception e) {
                log.error("Failed to adjust profile for well: {}", wellId, e);
            }
        }
    }

    @Transactional
    public List<InjectionProfile> adjustWellProfile(ProfileAdjustmentRequest request) {
        log.info("Starting profile adjustment for well: {}", request.getWellId());

        AdvancedFeaturesProperties.Profile config = properties.getProfile();
        AdvancedFeaturesProperties.SmartWaterController controllerConfig = properties.getSmartWaterController();
        LocalDate profileDate = request.getProfileDate() != null ? request.getProfileDate() : LocalDate.now();
        double maxAdjustmentPct = request.getMaxAdjustmentPercentage() != null ?
                request.getMaxAdjustmentPercentage() : config.getMaxAdjustmentPercentage();

        List<InjectionProfile> profiles = profileRepository
                .findByWellIdAndProfileDateOrderByLayerNumber(request.getWellId(), profileDate);

        if (profiles.isEmpty()) {
            profiles = generateInitialProfiles(request.getWellId(), profileDate);
        }

        Map<Integer, Double> historicalPredictions = new HashMap<>();
        Map<Integer, Double> historicalActuals = new HashMap<>();
        if (controllerConfig.isEnableSmithPredictor()) {
            loadHistoricalPredictionData(request.getWellId(), profileDate,
                    controllerConfig.getFeedbackDelayHours(),
                    historicalPredictions, historicalActuals);
        }

        Double currentTotal = profileRepository.sumCurrentInjectionByWellAndDate(request.getWellId(), profileDate);
        double totalCurrentVolume = currentTotal != null ? currentTotal :
                profiles.stream().mapToDouble(p -> p.getCurrentInjectionVolume() != null ? p.getCurrentInjectionVolume() : 0.0).sum();

        double targetTotalVolume = request.getTotalTargetVolume() != null ?
                request.getTotalTargetVolume() :
                (request.getMaintainTotalVolume() ? totalCurrentVolume : totalCurrentVolume);

        Map<Integer, Double> layerOverrides = new HashMap<>();
        if (request.getLayerOverrides() != null) {
            for (ProfileAdjustmentRequest.LayerAdjustmentOverride override : request.getLayerOverrides()) {
                layerOverrides.put(override.getLayerNumber(), override.getTargetVolume());
            }
        }

        List<InjectionProfile> adjustedProfiles = calculateOptimalLayerAllocation(
                profiles, totalCurrentVolume, targetTotalVolume,
                maxAdjustmentPct, layerOverrides, config);

        if (controllerConfig.isEnableSmithPredictor()) {
            adjustedProfiles = applySmithPredictorCompensation(
                    adjustedProfiles, historicalPredictions, historicalActuals,
                    totalCurrentVolume, targetTotalVolume, controllerConfig);
        }

        List<InjectionProfile> savedProfiles = profileRepository.saveAll(adjustedProfiles);

        boolean adjustmentSuccess = true;
        String message = "Profile adjustment completed successfully";

        if (request.getExecuteAutoAdjustment()) {
            adjustmentSuccess = executeSmartWaterControllerAdjustment(request.getWellId(), savedProfiles);
            message = adjustmentSuccess ?
                    "Profile adjustment and smart controller execution completed" :
                    "Profile adjustment completed but smart controller execution failed";

            for (InjectionProfile profile : savedProfiles) {
                profile.setAllocationStatus(adjustmentSuccess ? "EXECUTED" : "PENDING");
                profile.setLastAdjustmentTime(LocalDateTime.now());
                profile.setAdjustmentSuccess(adjustmentSuccess);
            }
            profileRepository.saveAll(savedProfiles);
        }

        eventPublisher.publishEvent(new ProfileAdjustmentCompletedEvent(
                this, request.getWellId(), savedProfiles, adjustmentSuccess, message));

        log.info("Profile adjustment for well {} completed. Success: {}", request.getWellId(), adjustmentSuccess);
        return savedProfiles;
    }

    private List<InjectionProfile> calculateOptimalLayerAllocation(
            List<InjectionProfile> profiles, double currentTotal, double targetTotal,
            double maxAdjustmentPct, Map<Integer, Double> layerOverrides,
            AdvancedFeaturesProperties.Profile config) {

        List<InjectionProfile> result = new ArrayList<>();
        Map<Integer, Double> allocationScores = new HashMap<>();

        for (InjectionProfile profile : profiles) {
            double score = calculateLayerAllocationScore(profile, config);
            allocationScores.put(profile.getLayerNumber(), score);
        }

        double totalScore = allocationScores.values().stream().mapToDouble(Double::doubleValue).sum();

        double remainingVolume = targetTotal;
        Map<Integer, Double> preliminaryVolumes = new HashMap<>();

        for (InjectionProfile profile : profiles) {
            Integer layerNum = profile.getLayerNumber();
            if (layerOverrides.containsKey(layerNum)) {
                double overrideVolume = layerOverrides.get(layerNum);
                preliminaryVolumes.put(layerNum, overrideVolume);
                remainingVolume -= overrideVolume;
            }
        }

        for (InjectionProfile profile : profiles) {
            Integer layerNum = profile.getLayerNumber();
            if (!preliminaryVolumes.containsKey(layerNum)) {
                double score = allocationScores.get(layerNum);
                double ratio = totalScore > 0 ? score / totalScore : 1.0 / profiles.size();
                double calculatedVolume = remainingVolume * ratio;
                preliminaryVolumes.put(layerNum, calculatedVolume);
            }
        }

        for (InjectionProfile profile : profiles) {
            InjectionProfile adjusted = new InjectionProfile();
            adjusted.setId(profile.getId());
            adjusted.setWellId(profile.getWellId());
            adjusted.setLayerNumber(profile.getLayerNumber());
            adjusted.setLayerName(profile.getLayerName());
            adjusted.setProfileDate(profile.getProfileDate());
            adjusted.setTopDepth(profile.getTopDepth());
            adjusted.setBottomDepth(profile.getBottomDepth());
            adjusted.setThickness(profile.getThickness());
            adjusted.setPermeability(profile.getPermeability());
            adjusted.setPorosity(profile.getPorosity());
            adjusted.setCurrentInjectionVolume(profile.getCurrentInjectionVolume());
            adjusted.setWaterAbsorptionRatio(profile.getWaterAbsorptionRatio());
            adjusted.setStartingPressure(profile.getStartingPressure());
            adjusted.setCurrentPressure(profile.getCurrentPressure());
            adjusted.setSkinFactor(profile.getSkinFactor());
            adjusted.setAllocationStatus("PENDING");

            double currentVolume = profile.getCurrentInjectionVolume() != null ? profile.getCurrentInjectionVolume() : 0;
            double rawSuggestedVolume = preliminaryVolumes.get(profile.getLayerNumber());
            double maxIncrease = currentVolume * (1 + maxAdjustmentPct / 100.0);
            double maxDecrease = Math.max(currentVolume * (1 - maxAdjustmentPct / 100.0), config.getMinLayerInjectionVolume());

            double suggestedVolume = Math.min(Math.max(rawSuggestedVolume, maxDecrease), maxIncrease);
            suggestedVolume = Math.max(suggestedVolume, config.getMinLayerInjectionVolume());

            BigDecimal bd = BigDecimal.valueOf(suggestedVolume).setScale(2, RoundingMode.HALF_UP);
            adjusted.setSuggestedInjectionVolume(bd.doubleValue());

            double adjustmentAmount = suggestedVolume - currentVolume;
            adjusted.setAdjustmentAmount(adjustmentAmount);
            adjusted.setAdjustmentDirection(adjusted.determineAdjustmentDirection());

            result.add(adjusted);
        }

        double sumSuggested = result.stream()
                .mapToDouble(p -> p.getSuggestedInjectionVolume() != null ? p.getSuggestedInjectionVolume() : 0)
                .sum();

        if (Math.abs(sumSuggested - targetTotal) > 0.01 && result.size() > 0) {
            double diff = targetTotal - sumSuggested;
            double perLayerAdjustment = diff / result.size();
            for (InjectionProfile profile : result) {
                profile.setSuggestedInjectionVolume(
                        profile.getSuggestedInjectionVolume() + perLayerAdjustment);
                profile.setAdjustmentAmount(
                        profile.getSuggestedInjectionVolume() -
                        (profile.getCurrentInjectionVolume() != null ? profile.getCurrentInjectionVolume() : 0));
                profile.setAdjustmentDirection(profile.determineAdjustmentDirection());
            }
        }

        return result;
    }

    private double calculateLayerAllocationScore(InjectionProfile profile,
                                                  AdvancedFeaturesProperties.Profile config) {
        double permeability = profile.getPermeability() != null ? profile.getPermeability() : 100.0;
        double thickness = profile.getThickness() != null ? profile.getThickness() : 10.0;
        double absorptionRatio = profile.getWaterAbsorptionRatio() != null ? profile.getWaterAbsorptionRatio() : 0.5;
        double startingPressure = profile.getStartingPressure() != null ? profile.getStartingPressure() : 0.0;
        double skinFactor = profile.getSkinFactor() != null ? profile.getSkinFactor() : 0.0;

        double normalizedPermeability = Math.min(permeability / 500.0, 1.0);
        double normalizedThickness = Math.min(thickness / 50.0, 1.0);
        double normalizedAbsorption = Math.max(Math.min(absorptionRatio, 1.0), 0.0);
        double pressurePenalty = Math.max(0, startingPressure / 30.0) * config.getStartingPressurePenaltyFactor();
        double skinPenalty = Math.max(0, skinFactor / 10.0) * config.getSkinFactorPenaltyFactor();

        double score = config.getPermeabilityWeight() * normalizedPermeability +
                       config.getThicknessWeight() * normalizedThickness +
                       config.getAbsorptionWeight() * normalizedAbsorption -
                       pressurePenalty -
                       skinPenalty;

        return Math.max(score, 0.01);
    }

    private List<InjectionProfile> generateInitialProfiles(String wellId, LocalDate date) {
        log.info("Generating initial injection profiles for well: {}", wellId);
        AdvancedFeaturesProperties.Profile config = properties.getProfile();
        int layerCount = config.getDefaultLayerCount();
        List<InjectionProfile> profiles = new ArrayList<>();

        Random random = new Random(wellId.hashCode());
        double baseDepth = 1500.0 + random.nextDouble() * 500.0;

        for (int i = 1; i <= layerCount; i++) {
            InjectionProfile profile = new InjectionProfile();
            profile.setWellId(wellId);
            profile.setLayerNumber(i);
            profile.setLayerName("Layer " + i);
            profile.setProfileDate(date);

            double topDepth = baseDepth + (i - 1) * (20 + random.nextDouble() * 30);
            double thickness = 5 + random.nextDouble() * 15;
            profile.setTopDepth(topDepth);
            profile.setBottomDepth(topDepth + thickness);
            profile.setThickness(thickness);

            profile.setPermeability(50 + random.nextDouble() * 450);
            profile.setPorosity(0.15 + random.nextDouble() * 0.2);
            profile.setCurrentInjectionVolume(20 + random.nextDouble() * 80);
            profile.setWaterAbsorptionRatio(0.3 + random.nextDouble() * 0.5);
            profile.setStartingPressure(5 + random.nextDouble() * 15);
            profile.setCurrentPressure(8 + random.nextDouble() * 20);
            profile.setSkinFactor(-2 + random.nextDouble() * 10);
            profile.setAllocationStatus("INITIAL");

            profiles.add(profile);
        }

        return profileRepository.saveAll(profiles);
    }

    private boolean executeSmartWaterControllerAdjustment(String wellId, List<InjectionProfile> profiles) {
        AdvancedFeaturesProperties.SmartWaterController config = properties.getSmartWaterController();

        if (config.isEnableSimulation()) {
            return simulateSmartWaterControllerAdjustment(wellId, profiles, config);
        }

        try {
            String url = config.getBaseUrl() + "/wells/" + wellId + "/adjust";
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("wellId", wellId);
            requestBody.put("timestamp", LocalDateTime.now().toString());
            List<Map<String, Object>> layers = new ArrayList<>();
            for (InjectionProfile profile : profiles) {
                Map<String, Object> layer = new HashMap<>();
                layer.put("layerNumber", profile.getLayerNumber());
                layer.put("targetVolume", profile.getSuggestedInjectionVolume());
                layers.add(layer);
            }
            requestBody.put("layers", layers);

            for (int attempt = 0; attempt < config.getMaxRetryAttempts(); attempt++) {
                try {
                    Map<String, Object> response = restTemplate.postForObject(url, requestBody, Map.class);
                    if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                        log.info("Smart water controller adjustment successful for well: {}", wellId);
                        return true;
                    }
                } catch (Exception e) {
                    log.warn("Attempt {} failed for well {}: {}", attempt + 1, wellId, e.getMessage());
                    if (attempt < config.getMaxRetryAttempts() - 1) {
                        Thread.sleep(config.getRetryDelayMs());
                    }
                }
            }
            log.error("All retry attempts failed for well: {}", wellId);
            return false;
        } catch (Exception e) {
            log.error("Failed to execute smart water controller adjustment for well: {}", wellId, e);
            return false;
        }
    }

    private boolean simulateSmartWaterControllerAdjustment(
            String wellId, List<InjectionProfile> profiles,
            AdvancedFeaturesProperties.SmartWaterController config) {
        try {
            Thread.sleep(config.getSimulationDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        double successRate = config.getSimulationSuccessRate();
        boolean success = Math.random() < successRate;

        if (success) {
            log.info("SIMULATION: Smart water controller adjustment successful for well: {}", wellId);
        } else {
            log.warn("SIMULATION: Smart water controller adjustment failed for well: {}", wellId);
        }

        return success;
    }

    private void loadHistoricalPredictionData(String wellId, LocalDate currentDate,
                                               int delayHours,
                                               Map<Integer, Double> historicalPredictions,
                                               Map<Integer, Double> historicalActuals) {
        try {
            LocalDate historyDate = currentDate.minusDays(Math.max(1, delayHours / 24 + 1));
            List<InjectionProfile> historyProfiles = profileRepository
                    .findByWellIdAndProfileDateOrderByLayerNumber(wellId, historyDate);

            for (InjectionProfile profile : historyProfiles) {
                if (profile.getPredictedVolume() != null) {
                    historicalPredictions.put(profile.getLayerNumber(), profile.getPredictedVolume());
                }
                if (profile.getCurrentInjectionVolume() != null) {
                    historicalActuals.put(profile.getLayerNumber(), profile.getCurrentInjectionVolume());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load historical prediction data for well: {}", wellId, e);
        }
    }

    private List<InjectionProfile> applySmithPredictorCompensation(
            List<InjectionProfile> profiles,
            Map<Integer, Double> historicalPredictions,
            Map<Integer, Double> historicalActuals,
            double currentTotal, double targetTotal,
            AdvancedFeaturesProperties.SmartWaterController config) {

        List<InjectionProfile> compensatedProfiles = new ArrayList<>();
        double totalCompensatedVolume = 0.0;

        for (InjectionProfile profile : profiles) {
            InjectionProfile compensated = new InjectionProfile();
            copyProfileFields(profile, compensated);

            Integer layerNum = profile.getLayerNumber();
            double currentVolume = profile.getCurrentInjectionVolume() != null ?
                    profile.getCurrentInjectionVolume() : 0.0;
            double suggestedVolume = profile.getSuggestedInjectionVolume() != null ?
                    profile.getSuggestedInjectionVolume() : currentVolume;

            double predictedVolume = suggestProcessModel(currentVolume, suggestedVolume, config);
            compensated.setPredictedVolume(predictedVolume);

            double modelError = 0.0;
            if (historicalPredictions.containsKey(layerNum) &&
                    historicalActuals.containsKey(layerNum)) {
                double histPred = historicalPredictions.get(layerNum);
                double histActual = historicalActuals.get(layerNum);
                modelError = histActual - histPred;
                compensated.setModelPredictionError(modelError);
            }

            double delayCompensated = calculateSmithPredictorOutput(
                    currentVolume, suggestedVolume, predictedVolume,
                    modelError, config);

            double maxCompensation = currentVolume * config.getMaxOvershootCompensation();
            double adjustment = delayCompensated - currentVolume;
            if (Math.abs(adjustment) > maxCompensation) {
                adjustment = Math.signum(adjustment) * maxCompensation;
                delayCompensated = currentVolume + adjustment;
                compensated.setOvershootMitigationApplied(true);
            } else {
                compensated.setOvershootMitigationApplied(false);
            }

            delayCompensated = Math.max(delayCompensated, 5.0);
            compensated.setDelayCompensatedVolume(delayCompensated);
            compensated.setSuggestedInjectionVolume(delayCompensated);
            compensated.setFeedbackDelayHours(config.getFeedbackDelayHours());

            double newAdjustment = delayCompensated - currentVolume;
            compensated.setAdjustmentAmount(newAdjustment);
            compensated.setAdjustmentDirection(compensated.determineAdjustmentDirection());

            totalCompensatedVolume += delayCompensated;
            compensatedProfiles.add(compensated);
        }

        if (Math.abs(totalCompensatedVolume - targetTotal) > 0.01 && !compensatedProfiles.isEmpty()) {
            double scaleFactor = targetTotal / totalCompensatedVolume;
            scaleFactor = Math.max(0.8, Math.min(1.2, scaleFactor));

            for (InjectionProfile profile : compensatedProfiles) {
                double scaledVolume = profile.getSuggestedInjectionVolume() * scaleFactor;
                double currentVolume = profile.getCurrentInjectionVolume() != null ?
                        profile.getCurrentInjectionVolume() : 0.0;
                double maxAllowed = currentVolume * (1 + config.getMaxOvershootCompensation());
                double minAllowed = currentVolume * (1 - config.getMaxOvershootCompensation());
                scaledVolume = Math.max(minAllowed, Math.min(maxAllowed, scaledVolume));
                scaledVolume = Math.max(scaledVolume, 5.0);

                profile.setSuggestedInjectionVolume(scaledVolume);
                profile.setDelayCompensatedVolume(scaledVolume);
                profile.setAdjustmentAmount(scaledVolume - currentVolume);
                profile.setAdjustmentDirection(profile.determineAdjustmentDirection());
            }
        }

        log.info("Smith predictor compensation applied for {} layers. Overshoot mitigation applied to {} layers",
                compensatedProfiles.size(),
                compensatedProfiles.stream().filter(InjectionProfile::getOvershootMitigationApplied).count());

        return compensatedProfiles;
    }

    private double suggestProcessModel(double currentVolume, double targetVolume,
                                        AdvancedFeaturesProperties.SmartWaterController config) {
        double processGain = 0.9;
        double timeConstant = config.getFeedbackDelayHours();
        double stepSize = 1.0;
        int steps = config.getPredictionHorizonSteps();

        double volume = currentVolume;
        for (int i = 0; i < steps; i++) {
            double error = targetVolume - volume;
            double rate = processGain * error / timeConstant;
            volume += rate * stepSize;
        }

        return volume;
    }

    private double calculateSmithPredictorOutput(double currentVolume, double targetVolume,
                                                 double predictedVolume, double modelError,
                                                 AdvancedFeaturesProperties.SmartWaterController config) {

        double predictionError = modelError * config.getModelErrorCorrectionFactor();
        double adjustedTarget = targetVolume - predictionError;

        double baseAdjustment = (adjustedTarget - currentVolume) * config.getSmithPredictorGain();

        double delayCompensation = (predictedVolume - currentVolume) *
                (1.0 - Math.exp(-1.0 / config.getFeedbackDelayHours()));

        double compensatedAdjustment = baseAdjustment - delayCompensation;

        return currentVolume + compensatedAdjustment;
    }

    private void copyProfileFields(InjectionProfile source, InjectionProfile target) {
        target.setId(source.getId());
        target.setWellId(source.getWellId());
        target.setLayerNumber(source.getLayerNumber());
        target.setLayerName(source.getLayerName());
        target.setProfileDate(source.getProfileDate());
        target.setTopDepth(source.getTopDepth());
        target.setBottomDepth(source.getBottomDepth());
        target.setThickness(source.getThickness());
        target.setPermeability(source.getPermeability());
        target.setPorosity(source.getPorosity());
        target.setCurrentInjectionVolume(source.getCurrentInjectionVolume());
        target.setWaterAbsorptionRatio(source.getWaterAbsorptionRatio());
        target.setStartingPressure(source.getStartingPressure());
        target.setCurrentPressure(source.getCurrentPressure());
        target.setSkinFactor(source.getSkinFactor());
        target.setAllocationStatus(source.getAllocationStatus());
    }

    @Transactional(readOnly = true)
    public List<InjectionProfile> getLatestProfile(String wellId) {
        return profileRepository.findLatestByWellId(wellId);
    }

    @Transactional(readOnly = true)
    public List<InjectionProfile> getProfileForDate(String wellId, LocalDate date) {
        return profileRepository.findByWellIdAndProfileDateOrderByLayerNumber(wellId, date);
    }

    @Transactional(readOnly = true)
    public List<InjectionProfile> getProfileHistory(String wellId, LocalDate startDate, LocalDate endDate) {
        return profileRepository.findByWellIdAndDateRange(wellId, startDate, endDate);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getProfileSummary(String wellId) {
        List<InjectionProfile> profiles = getLatestProfile(wellId);

        Map<String, Object> summary = new HashMap<>();
        summary.put("wellId", wellId);
        summary.put("layerCount", profiles.size());
        summary.put("profiles", profiles);

        double currentTotal = profiles.stream()
                .mapToDouble(p -> p.getCurrentInjectionVolume() != null ? p.getCurrentInjectionVolume() : 0)
                .sum();
        double suggestedTotal = profiles.stream()
                .mapToDouble(p -> p.getSuggestedInjectionVolume() != null ? p.getSuggestedInjectionVolume() : 0)
                .sum();

        summary.put("currentTotalVolume", currentTotal);
        summary.put("suggestedTotalVolume", suggestedTotal);
        summary.put("totalAdjustmentAmount", suggestedTotal - currentTotal);

        long increaseCount = profiles.stream()
                .filter(p -> "INCREASE".equals(p.getAdjustmentDirection())).count();
        long decreaseCount = profiles.stream()
                .filter(p -> "DECREASE".equals(p.getAdjustmentDirection())).count();
        long keepCount = profiles.stream()
                .filter(p -> "KEEP".equals(p.getAdjustmentDirection())).count();

        summary.put("increaseCount", increaseCount);
        summary.put("decreaseCount", decreaseCount);
        summary.put("keepCount", keepCount);

        Optional<InjectionProfile> latest = profiles.stream().findFirst();
        latest.ifPresent(p -> summary.put("profileDate", p.getProfileDate()));

        return summary;
    }

    @Transactional(readOnly = true)
    public List<InjectionProfile> getPendingAdjustments() {
        return profileRepository.findPendingAdjustments();
    }

    @Transactional
    public InjectionProfile saveProfile(InjectionProfile profile) {
        return profileRepository.save(profile);
    }

    @Transactional
    public List<InjectionProfile> saveAllProfiles(List<InjectionProfile> profiles) {
        return profileRepository.saveAll(profiles);
    }
}
