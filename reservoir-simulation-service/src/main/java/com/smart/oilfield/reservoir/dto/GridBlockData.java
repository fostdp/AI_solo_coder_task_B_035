package com.smart.oilfield.reservoir.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GridBlockData implements Serializable {

    private Integer i;
    private Integer j;
    private Integer k;
    private Double pressure;
    private Double oilSaturation;
    private Double waterSaturation;
    private Double gasSaturation;
    private Double permeability;
    private Double porosity;
    private Double thickness;
    private Double oilRelativePermeability;
    private Double waterRelativePermeability;
    private Double gasRelativePermeability;
    private Double capillaryPressure;
    private Double oilFlowRate;
    private Double waterFlowRate;
    private Double gasFlowRate;
}
