package com.smart.oilfield.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "injection_profile", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"well_id", "layer_number", "profile_date"})
})
public class InjectionProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "well_id", length = 32, nullable = false)
    private String wellId;

    @Column(name = "layer_number", nullable = false)
    private Integer layerNumber;

    @Column(name = "layer_name", length = 50)
    private String layerName;

    @Column(name = "profile_date", nullable = false)
    private LocalDate profileDate;

    @Column(name = "top_depth", precision = 10, scale = 2)
    private Double topDepth;

    @Column(name = "bottom_depth", precision = 10, scale = 2)
    private Double bottomDepth;

    @Column(name = "thickness", precision = 10, scale = 2)
    private Double thickness;

    @Column(name = "permeability", precision = 10, scale = 2)
    private Double permeability;

    @Column(name = "porosity", precision = 5, scale = 2)
    private Double porosity;

    @Column(name = "current_injection_volume", precision = 10, scale = 2)
    private Double currentInjectionVolume;

    @Column(name = "water_absorption_ratio", precision = 5, scale = 2)
    private Double waterAbsorptionRatio;

    @Column(name = "starting_pressure", precision = 10, scale = 2)
    private Double startingPressure;

    @Column(name = "current_pressure", precision = 10, scale = 2)
    private Double currentPressure;

    @Column(name = "skin_factor", precision = 10, scale = 2)
    private Double skinFactor;

    @Column(name = "suggested_injection_volume", precision = 10, scale = 2)
    private Double suggestedInjectionVolume;

    @Column(name = "adjustment_amount", precision = 10, scale = 2)
    private Double adjustmentAmount;

    @Column(name = "adjustment_direction", length = 10)
    private String adjustmentDirection;

    @Column(name = "allocation_status", length = 20)
    private String allocationStatus;

    @Column(name = "last_adjustment_time")
    private LocalDateTime lastAdjustmentTime;

    @Column(name = "adjustment_success")
    private Boolean adjustmentSuccess;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }

    public String determineAdjustmentDirection() {
        if (adjustmentAmount == null || adjustmentAmount == 0) return "KEEP";
        return adjustmentAmount > 0 ? "INCREASE" : "DECREASE";
    }
}
