package com.smart.oilfield.common.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "water_injection_data", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"well_id", "report_date"})
})
public class WaterInjectionData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "well_id", length = 32, nullable = false)
    private String wellId;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "water_volume", precision = 10, scale = 2, nullable = false)
    private Double waterVolume;

    @Column(name = "injection_pressure", precision = 10, scale = 2, nullable = false)
    private Double injectionPressure;

    @Column(name = "water_absorption_index", precision = 10, scale = 2, nullable = false)
    private Double waterAbsorptionIndex;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }
}
