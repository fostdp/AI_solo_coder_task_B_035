package com.smart.oilfield.common.dto;

import lombok.Data;
import java.time.LocalDate;

@Data
public class BlockSummaryDTO {
    private String blockName;
    private LocalDate summaryDate;
    private Double totalOilProduction;
    private Double totalWaterInjection;
    private Double averageWaterCut;
}
