package com.smart.oilfield.common.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "injection_production_relation", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"injection_well_id", "production_well_id"})
})
public class InjectionProductionRelation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "injection_well_id", length = 32, nullable = false)
    private String injectionWellId;

    @Column(name = "production_well_id", length = 32, nullable = false)
    private String productionWellId;

    @Column(name = "effectiveness_type", length = 20, nullable = false)
    private String effectivenessType;

    @Column(name = "effectiveness_degree", precision = 5, scale = 2)
    private Double effectivenessDegree;

    @Column(name = "distance", precision = 10, scale = 2)
    private Double distance;

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
}
