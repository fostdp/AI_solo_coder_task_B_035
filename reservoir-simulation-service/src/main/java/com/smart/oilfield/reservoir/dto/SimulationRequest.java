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
public class SimulationRequest implements Serializable {

    private String simulationType;
    private String reservoirName;
    private String description;

    private Integer gridSizeI;
    private Integer gridSizeJ;
    private Integer gridSizeK;
    private Double totalTimeDays;
    private Double timeStepDays;

    private Double initialPressure;
    private Double initialOilSaturation;
    private Double initialWaterSaturation;
    private Double initialGasSaturation;

    private Double referenceDepth;
    private Double oilViscosity;
    private Double waterViscosity;
    private Double gasViscosity;
    private Double oilDensity;
    private Double waterDensity;
    private Double gasDensity;
    private Double rockCompressibility;
    private Double formationVolumeFactorOil;
    private Double formationVolumeFactorWater;
    private Double formationVolumeFactorGas;

    private Double injectionRate;
    private Double productionRate;
    private Double bottomHolePressure;
    private List<String> injectionWells;
    private List<String> productionWells;

    private Map<String, Double> relativePermeabilityParams;
    private Map<String, Double> capillaryPressureParams;

    private Double porosity;
    private Double permeabilityX;
    private Double permeabilityY;
    private Double permeabilityZ;
    private Double netPayThickness;

    private Double waterInjectionConcentration;
    private Double polymerConcentration;
    private Double surfactantConcentration;

    private Integer maxIterations;
    private Double convergenceTolerance;
    private String solverType;

    private LocalDateTime scheduledStartTime;
    private Boolean enableParallelComputing;
    private Integer numThreads;
    private Boolean saveIntermediateResults;
}
