package com.smart.oilfield.common.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "block_daily_summary", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"block_name", "summary_date"})
})
public class BlockDailySummary {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "block_name", length = 100, nullable = false)
    private String blockName;

    @Column(name = "summary_date", nullable = false)
    private LocalDate summaryDate;

    @Column(name = "total_oil_production", precision = 12, scale = 2, nullable = false)
    private Double totalOilProduction;

    @Column(name = "total_water_injection", precision = 12, scale = 2, nullable = false)
    private Double totalWaterInjection;

    @Column(name = "average_water_cut", precision = 5, scale = 2, nullable = false)
    private Double averageWaterCut;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }
}
