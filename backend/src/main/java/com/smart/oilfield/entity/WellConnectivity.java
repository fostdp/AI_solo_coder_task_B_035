package com.smart.oilfield.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "well_connectivity", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"injection_well_id", "production_well_id", "analysis_date"})
public class WellConnectivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "injection_well_id", length = 32, nullable = false)
    private String injectionWellId;

    @Column(name = "production_well_id", length = 32, nullable = false)
    private String productionWellId;

    @Column(name = "analysis_date", nullable = false)
    private LocalDate analysisDate;

    @Column(name = "pearson_correlation", precision = 10, scale = 4)
    private Double pearsonCorrelation;

    @Column(name = "time_lag_hours")
    private Integer timeLagHours;

    @Column(name = "cross_correlation", precision = 10, scale = 4)
    private Double crossCorrelation;

    @Column(name = "connectivity_strength", precision = 5, scale = 2)
    private Double connectivityStrength;

    @Column(name = "connectivity_type", length = 20)
    private String connectivityType;

    @Column(name = "confidence_level", precision = 5, scale = 2)
    private Double confidenceLevel;

    @Column(name = "analysis_window_days")
    private Integer analysisWindowDays;

    @Column(name = "pressure_data_quality", precision = 5, scale = 2)
    private Double pressureDataQuality;

    @Column(name = "is_significant")
    private Boolean isSignificant = false;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }

    public String determineConnectivityType() {
        if (connectivityStrength == null) return "UNKNOWN";
        if (connectivityStrength >= 0.8) return "STRONG";
        if (connectivityStrength >= 0.5) return "MODERATE";
        if (connectivityStrength >= 0.3) return "WEAK";
        return "NONE";
    }
}
