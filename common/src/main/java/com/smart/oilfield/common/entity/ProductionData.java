package com.smart.oilfield.common.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "production_data", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"well_id", "report_date"})
})
public class ProductionData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "well_id", length = 32, nullable = false)
    private String wellId;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "liquid_volume", precision = 10, scale = 2, nullable = false)
    private Double liquidVolume;

    @Column(name = "oil_volume", precision = 10, scale = 2, nullable = false)
    private Double oilVolume;

    @Column(name = "water_cut", precision = 5, scale = 2, nullable = false)
    private Double waterCut;

    @Column(name = "dynamic_fluid_level", precision = 10, scale = 2, nullable = false)
    private Double dynamicFluidLevel;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }
}
