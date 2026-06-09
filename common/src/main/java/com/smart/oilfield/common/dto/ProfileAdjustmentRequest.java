package com.smart.oilfield.common.dto;

import lombok.Data;
import java.time.LocalDate;
import java.util.List;

@Data
public class ProfileAdjustmentRequest {

    private String wellId;
    private LocalDate profileDate;
    private Double totalTargetVolume;
    private Boolean maintainTotalVolume = true;
    private Double maxAdjustmentPercentage = 30.0;
    private Boolean executeAutoAdjustment = false;
    private List<LayerAdjustmentOverride> layerOverrides;

    @Data
    public static class LayerAdjustmentOverride {
        private Integer layerNumber;
        private Double targetVolume;
        private String adjustmentReason;
    }
}
