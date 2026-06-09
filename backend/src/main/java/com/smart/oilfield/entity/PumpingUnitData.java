package com.smart.oilfield.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "pumping_unit_data", indexes = {
    @Index(name = "idx_pumping_well_time", columnList = "well_id, record_time DESC")
})
public class PumpingUnitData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "well_id", length = 32, nullable = false)
    private String wellId;

    @Column(name = "record_time", nullable = false)
    private LocalDateTime recordTime;

    @Column(name = "dynamic_fluid_level", precision = 10, scale = 2)
    private Double dynamicFluidLevel;

    @Column(name = "motor_current", precision = 10, scale = 2)
    private Double motorCurrent;

    @Column(name = "motor_voltage", precision = 10, scale = 2)
    private Double motorVoltage;

    @Column(name = "motor_power", precision = 10, scale = 2)
    private Double motorPower;

    @Column(name = "stroke_length", precision = 10, scale = 2)
    private Double strokeLength;

    @Column(name = "stroke_frequency", precision = 10, scale = 2)
    private Double strokeFrequency;

    @Column(name = "polished_rod_load", precision = 10, scale = 2)
    private Double polishedRodLoad;

    @Column(name = "casing_pressure", precision = 10, scale = 2)
    private Double casingPressure;

    @Column(name = "tubing_pressure", precision = 10, scale = 2)
    private Double tubingPressure;

    @Column(name = "flow_rate", precision = 10, scale = 2)
    private Double flowRate;

    @Column(name = "pump_efficiency", precision = 5, scale = 2)
    private Double pumpEfficiency;

    @Column(name = "system_efficiency", precision = 5, scale = 2)
    private Double systemEfficiency;

    @Column(name = "vibration_level", precision = 10, scale = 2)
    private Double vibrationLevel;

    @Column(name = "temperature", precision = 10, scale = 2)
    private Double temperature;

    @Column(name = "is_anomaly")
    private Boolean isAnomaly = false;

    @Column(name = "anomaly_type", length = 50)
    private String anomalyType;

    @Column(name = "anomaly_score", precision = 10, scale = 4)
    private Double anomalyScore;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }
}
