package com.smart.oilfield.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "fault_prediction")
public class FaultPrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prediction_id", length = 64, nullable = false, unique = true)
    private String predictionId;

    @Column(name = "well_id", length = 32, nullable = false)
    private String wellId;

    @Column(name = "fault_type", length = 50, nullable = false)
    private String faultType;

    @Column(name = "fault_probability", precision = 5, scale = 2, nullable = false)
    private Double faultProbability;

    @Column(name = "prediction_time", nullable = false)
    private LocalDateTime predictionTime;

    @Column(name = "predicted_fault_time")
    private LocalDateTime predictedFaultTime;

    @Column(name = "severity_level", length = 20)
    private String severityLevel;

    @Column(name = "anomaly_score", precision = 10, scale = 4)
    private Double anomalyScore;

    @Column(name = "fluid_level_trend", length = 20)
    private String fluidLevelTrend;

    @Column(name = "current_trend", length = 20)
    private String currentTrend;

    @Column(name = "average_fluid_level", precision = 10, scale = 2)
    private Double averageFluidLevel;

    @Column(name = "average_current", precision = 10, scale = 2)
    private Double averageCurrent;

    @Column(name = "fluid_level_deviation", precision = 10, scale = 2)
    private Double fluidLevelDeviation;

    @Column(name = "current_deviation", precision = 10, scale = 2)
    private Double currentDeviation;

    @Column(name = "analysis_window_hours")
    private Integer analysisWindowHours;

    @Column(name = "model_confidence", precision = 5, scale = 2)
    private Double modelConfidence;

    @Column(name = "symptoms", length = 500)
    private String symptoms;

    @Column(name = "recommended_action", length = 500)
    private String recommendedAction;

    @Column(name = "estimated_downtime_hours")
    private Integer estimatedDowntimeHours;

    @Column(name = "estimated_maintenance_cost", precision = 12, scale = 2)
    private Double estimatedMaintenanceCost;

    @Column(name = "is_acknowledged")
    private Boolean isAcknowledged = false;

    @Column(name = "acknowledge_time")
    private LocalDateTime acknowledgeTime;

    @Column(name = "acknowledged_by", length = 100)
    private String acknowledgedBy;

    @Column(name = "actual_fault_occurred")
    private Boolean actualFaultOccurred = false;

    @Column(name = "actual_fault_time")
    private LocalDateTime actualFaultTime;

    @Column(name = "prediction_accuracy", precision = 5, scale = 2)
    private Double predictionAccuracy;

    @Column(name = "model_version", length = 50)
    private String modelVersion;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }

    public String determineSeverityLevel() {
        if (faultProbability == null) return "UNKNOWN";
        if (faultProbability >= 0.8) return "CRITICAL";
        if (faultProbability >= 0.6) return "WARNING";
        if (faultProbability >= 0.4) return "NOTICE";
        return "LOW";
    }
}
