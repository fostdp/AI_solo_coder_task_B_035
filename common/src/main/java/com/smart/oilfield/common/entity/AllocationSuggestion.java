package com.smart.oilfield.common.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "allocation_suggestion", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"well_id", "suggestion_date"})
})
public class AllocationSuggestion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "well_id", length = 32, nullable = false)
    private String wellId;

    @Column(name = "suggestion_date", nullable = false)
    private LocalDate suggestionDate;

    @Column(name = "current_water_volume", precision = 10, scale = 2, nullable = false)
    private Double currentWaterVolume;

    @Column(name = "suggested_water_volume", precision = 10, scale = 2, nullable = false)
    private Double suggestedWaterVolume;

    @Column(name = "adjustment_direction", length = 10, nullable = false)
    private String adjustmentDirection;

    @Column(name = "adjustment_amount", precision = 10, scale = 2, nullable = false)
    private Double adjustmentAmount;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "model_version", length = 50)
    private String modelVersion;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }
}
