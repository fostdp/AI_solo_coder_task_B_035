package com.smart.oilfield.common.entity;

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

    @Column(name = "stroke_length", precision = 10, scale