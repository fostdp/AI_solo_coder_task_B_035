package com.smart.oilfield.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class EOREvaluationRequest {

    private String blockName;
    private LocalDate evaluationDate;
    private Integer predictionTimeHorizonMonths = 24;
    private BigDecimal oilPricePerBarrel = new BigDecimal("70");
    private Double discountRate = 0.08;
    private List<String> eorTypes;
    private List<ScenarioParameter> scenarioParameters;
    private Boolean generateComparativeAnalysis = true;
    private String modelVersion = "v2.0";

    @Data
    public static class ScenarioParameter {
        private String eorType;
        private Double chemicalConcentration;
        private Double injectionSlugSize;
        private Double injectionRate;
        private BigDecimal chemicalCostPerTon;
    }
}
