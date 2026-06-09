package com.smart.oilfield.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class FaultPredictionRequest {

    private String wellId;
    private LocalDateTime analysisEndTime;
    private Integer analysisWindowHours = 72;
    private Double faultProbabilityThreshold = 0.6;
    private List<String> faultTypesToCheck;
    private Boolean generateMaintenanceRecommendation = true;
    private Boolean autoPublishAlarm = false;
    private String modelVersion = "v1.5";
}
