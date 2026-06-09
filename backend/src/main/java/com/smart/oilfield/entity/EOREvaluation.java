package com.smart.oilfield.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "eor_evaluation", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"block_name", "eor_type", "evaluation_date"})
})
public class EOREvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "block_name", length = 100, nullable = false)
    private String blockName;

    @Column(name = "eor_type", length = 50, nullable = false)
    private String eorType;

    @Column(name = "evaluation_date", nullable = false)
    private LocalDate evaluationDate;

    @Column(name = "scenario_name", length = 100)
    private String scenarioName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "current_oil_production", precision = 12, scale = 2)
    private Double currentOilProduction;

    @Column(name = "current_water_cut", precision = 5, scale = 2)
    private Double currentWaterCut;

    @Column(name = "remaining_oil_saturation", precision = 5, scale = 2)
    private Double remainingOilSaturation;

    @Column(name = "reservoir_temperature", precision = 10, scale = 2)
    private Double reservoirTemperature;

    @Column(name = "reservoir_pressure", precision = 10, scale = 2)
    private Double reservoirPressure;

    @Column(name = "permeability", precision = 10, scale = 2)
    private Double permeability;

    @Column(name = "porosity", precision = 5, scale = 2)
    private Double porosity;

    @Column(name = "chemical_concentration", precision = 5, scale = 2)
    private Double chemicalConcentration;

    @Column(name = "injection_slug_size", precision = 10, scale = 2)
    private Double injectionSlugSize;

    @Column(name = "injection_rate", precision = 10, scale = 2)
    private Double injectionRate;

    @Column(name = "predicted_oil_increment", precision = 12, scale = 2)
    private Double predictedOilIncrement;

    @Column(name = "predicted_water_cut_reduction", precision = 5, scale = 2)
    private Double predictedWaterCutReduction;

    @Column(name = "prediction_time_horizon_months")
    private Integer predictionTimeHorizonMonths;

    @Column(name = "chemical_cost_per_ton", precision = 12, scale = 2)
    private BigDecimal chemicalCostPerTon;

    @Column(name = "total_chemical_cost", precision = 15, scale = 2)
    private BigDecimal totalChemicalCost;

    @Column(name = "oil_price_per_barrel", precision = 10, scale = 2)
    private BigDecimal oilPricePerBarrel;

    @Column(name = "total_revenue", precision = 15, scale = 2)
    private BigDecimal totalRevenue;

    @Column(name = "net_profit", precision = 15, scale = 2)
    private BigDecimal netProfit;

    @Column(name = "roi_percentage", precision = 10, scale = 2)
    private Double roiPercentage;

    @Column(name = "payback_period_months")
    private Integer paybackPeriodMonths;

    @Column(name = "technical_feasibility", precision = 5, scale = 2)
    private Double technicalFeasibility;

    @Column(name = "economic_viability", precision = 5, scale = 2)
    private Double economicViability;

    @Column(name = "overall_score", precision = 5, scale = 2)
    private Double overallScore;

    @Column(name = "recommendation", length = 20)
    private String recommendation;

    @Column(name = "risk_level", length = 20)
    private String riskLevel;

    @Column(name = "model_version", length = 50)
    private String modelVersion;

    @Column(name = "is_recommended")
    private Boolean isRecommended = false;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }

    public String determineRecommendation() {
        if (overallScore == null) return "PENDING";
        if (overallScore >= 0.8) return "RECOMMENDED";
        if (overallScore >= 0.6) return "CONSIDER";
        return "NOT_RECOMMENDED";
    }

    public String determineRiskLevel() {
        double risk = 1.0 - (technicalFeasibility != null ? technicalFeasibility : 0.5) *
                              (economicViability != null ? economicViability : 0.5);
        if (risk < 0.3) return "LOW";
        if (risk < 0.6) return "MEDIUM";
        return "HIGH";
    }
}
