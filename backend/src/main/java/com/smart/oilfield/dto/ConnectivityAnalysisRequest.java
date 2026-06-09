package com.smart.oilfield.dto;

import lombok.Data;
import java.time.LocalDate;

@Data
public class ConnectivityAnalysisRequest {

    private String blockName;
    private LocalDate analysisDate;
    private Integer analysisWindowDays = 30;
    private Double significanceThreshold = 0.5;
    private Integer maxTimeLagHours = 168;
    private Boolean includeWeakConnections = false;
}
