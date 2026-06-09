package com.smart.oilfield.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.math.BigDecimal;

@Data
@Configuration
@ConfigurationProperties(prefix = "advanced")
public class AdvancedFeaturesProperties {

    private Connectivity connectivity = new Connectivity();
    private Profile profile = new Profile();
    private Eor eor = new Eor();
    private Fault fault = new Fault();
    private SmartWaterController smartWaterController = new SmartWaterController();

    @Data
    public static class Connectivity {
        private int defaultAnalysisWindowDays = 30;
        private int minAnalysisWindowDays = 7;
        private double significanceThreshold = 0.5;
        private int maxTimeLagHours = 168;
        private double strongConnectivityThreshold = 0.8;
        private double moderateConnectivityThreshold = 0.5;
        private double weakConnectivityThreshold = 0.3;
        private double minDataQualityThreshold = 0.6;
        private int autoAnalysisDaysInterval = 7;
        private String autoAnalysisSchedule = "0 0 3 * * ?";
        private boolean includeWeakConnectionsInMap = false;
        private double partialCorrelationThreshold = 0.3;
        private boolean enableGraphModelScreening = true;
        private int maxConditioningVariables = 3;
        private double graphModelSignificanceLevel = 0.05;
        private double spuriousEdgeThreshold = 0.15;
    }

    @Data
    public static class Profile {
        private int defaultLayerCount = 5;
        private double maxAdjustmentPercentage = 30.0;
        private double minLayerInjectionVolume = 5.0;
        private double permeabilityWeight = 0.4;
        private double thicknessWeight = 0.3;
        private double absorptionWeight = 0.3;
        private double startingPressurePenaltyFactor = 0.1;
        private double skinFactorPenaltyFactor = 0.05;
        private int autoAdjustmentDaysInterval = 3;
        private String autoAdjustmentSchedule = "0 0 4 * * ?";
        private boolean enableAutoAdjustment = false;
    }

    @Data
    public static class Eor {
        private int defaultPredictionHorizonMonths = 24;
        private double defaultOilPricePerBarrel = 70.0;
        private double defaultDiscountRate = 0.08;
        private double minRoiThreshold = 15.0;
        private double minTechnicalFeasibility = 0.6;
        private double polymerDefaultConcentration = 1500.0;
        private double surfactantDefaultConcentration = 300.0;
        private double polymerDefaultSlugSize = 0.5;
        private double surfactantDefaultSlugSize = 0.3;
        private double polymerDefaultInjectionRate = 500.0;
        private double surfactantDefaultInjectionRate = 300.0;
        private double polymerOilIncrementFactor = 0.15;
        private double surfactantOilIncrementFactor = 0.25;
        private double combinedOilIncrementFactor = 0.35;
        private double polymerWaterCutReduction = 10.0;
        private double surfactantWaterCutReduction = 15.0;
        private double combinedWaterCutReduction = 20.0;
        private BigDecimal polymerCostPerTon = new BigDecimal("15000");
        private BigDecimal surfactantCostPerTon = new BigDecimal("25000");
        private int autoEvaluationDaysInterval = 30;
        private String autoEvaluationSchedule = "0 0 5 * * ?";
        private String modelVersion = "v2.0";
        private boolean enableHistoryMatching = true;
        private int historyMatchingWindowMonths = 12;
        private double historyMatchingTolerance = 0.05;
        private int maxOptimizationIterations = 100;
        private double optimizationLearningRate = 0.01;
        private double parameterAdjustmentFactor = 0.8;
        private double minHistoryDataPoints = 6;
        private double maximumAllowableDeviation = 0.15;
    }

    @Data
    public static class Fault {
        private int defaultAnalysisWindowHours = 72;
        private int minAnalysisWindowHours = 24;
        private double faultProbabilityThreshold = 0.6;
        private double criticalFaultThreshold = 0.8;
        private double warningFaultThreshold = 0.6;
        private double noticeFaultThreshold = 0.4;
        private double rodBreakCurrentDeviationThreshold = 2.0;
        private double pumpLeakFluidLevelRiseThreshold = 50.0;
        private double gasLockCurrentDropThreshold = 0.3;
        private double valveLeakEfficiencyDropThreshold = 0.2;
        private int autoPredictionHoursInterval = 6;
        private String autoPredictionSchedule = "0 0 */6 * * ?";
        private int predictionLeadTimeHours = 48;
        private double rodBreakMaintenanceCost = 50000.0;
        private double pumpLeakMaintenanceCost = 30000.0;
        private double gasLockMaintenanceCost = 10000.0;
        private double valveLeakMaintenanceCost = 15000.0;
        private int rodBreakDowntimeHours = 24;
        private int pumpLeakDowntimeHours = 12;
        private int gasLockDowntimeHours = 4;
        private int valveLeakDowntimeHours = 8;
        private boolean enableAutoAlarm = true;
        private String modelVersion = "v1.5";
        private boolean enableAdaptiveThreshold = true;
        private boolean enableTransferLearning = true;
        private double adaptiveThresholdSensitivity = 0.5;
        private int workingConditionWindowHours = 24;
        private double minThresholdAdjustmentFactor = 0.8;
        private double maxThresholdAdjustmentFactor = 1.5;
        private double transferLearningWeight = 0.3;
        private int historicalReferenceDays = 30;
        private double workingConditionStabilityThreshold = 0.3;
        private double falsePositivePenaltyFactor = 0.9;
    }

    @Data
    public static class SmartWaterController {
        private String baseUrl = "http://smart-water-controller:8081/api";
        private int connectionTimeoutMs = 5000;
        private int readTimeoutMs = 10000;
        private int maxRetryAttempts = 3;
        private int retryDelayMs = 1000;
        private String apiKey = "${SMART_WATER_API_KEY:default-key}";
        private boolean enableSimulation = true;
        private double simulationSuccessRate = 0.95;
        private int simulationDelayMs = 500;
        private boolean enableSmithPredictor = true;
        private int feedbackDelayHours = 6;
        private double smithPredictorGain = 0.8;
        private double maxOvershootCompensation = 0.3;
        private double modelErrorCorrectionFactor = 0.1;
        private int predictionHorizonSteps = 5;
    }
}
