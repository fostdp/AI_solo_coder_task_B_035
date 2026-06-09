package com.smart.oilfield.common.event;

import org.springframework.context.ApplicationEvent;

public class WellDataReceivedEvent extends ApplicationEvent {

    private final String wellId;
    private final String dataType;

    public WellDataReceivedEvent(Object source, String wellId, String dataType) {
        super(source);
        this.wellId = wellId;
        this.dataType = dataType;
    }

    public String getWellId() {
        return wellId;
    }

    public String getDataType() {
        return dataType;
    }
}
