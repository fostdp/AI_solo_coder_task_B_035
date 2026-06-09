package com.smart.oilfield.reservoir.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationResult implements Serializable {

    private String simulationId;
    private String simulationType;
    private String reservoirName;
    private String status;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Double durationMinutes;

    private Double totalOilProduction;
    private Double totalWaterProduction;
    private Double totalGasProduction;
    private Double totalWaterInjection;
    private Double cumulativeOilRecovery;
    private Double oilRecoveryFactor;
    private Double waterCut;
    private Double gasOilRatio;

    private Double finalAveragePressure;
    private Double finalAverageOilSaturation;
    private Double finalAverageWaterSaturation;

    private Integer completedTimeSteps;
    private Integer totalTimeSteps;
    private Integer gridCount;
    private Integer activeGridCount;

    private List<Double> timeSteps;
    private List<Double> oilProductionRates;
    private List<Double> waterProductionRates;
    private List<Double> gasProductionRates;
    private List<Double> averagePressures;
    private List<Double> averageOilSaturations;
    private List<Double> averageWaterSaturations;

    private List<GridBlockData> finalGridData;
    private Map<String, List<Double>> wellProductionData;
    private Map<String, List<Double>> wellInjectionData;

    private Double residualOilSaturation;
    private Double sweepEfficiency;
    private Double displacementEfficiency;

    private String errorMessage;
    private String warningMessages;
    private Map<String, Object> additionalMetrics;
    private LocalDateTime createdAt;
}
