package com.smart.oilfield.common.event;

import com.smart.oilfield.common.entity.WellConnectivity;
import org.springframework.context.ApplicationEvent;
import java.util.List;

public class ConnectivityAnalysisCompletedEvent extends ApplicationEvent {

    private final String blockName;
    private final List<WellConnectivity> results;

    public ConnectivityAnalysisCompletedEvent(Object source, String blockName, List<WellConnectivity> results) {
        super(source);
        this.blockName = blockName;
        this.results = results;
    }

    public String getBlockName() {
        return blockName;
    }

    public List<WellConnectivity> getResults() {
        return results;
    }
}
