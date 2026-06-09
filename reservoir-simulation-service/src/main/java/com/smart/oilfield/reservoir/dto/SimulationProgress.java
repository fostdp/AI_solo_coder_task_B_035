package com.smart.oilfield.reservoir.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationProgress implements Serializable {

    private String simulationId;
    private String status;
    private Double progress;
    private Integer currentTimeStep;
    private Integer totalTimeSteps;
    private String currentPhase;
    private LocalDateTime startTime;
    private LocalDateTime estimatedEndTime;
    private String message;
    private Double remainingTimeMinutes;
}
