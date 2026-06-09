package com.smart.oilfield.event;

import com.smart.oilfield.entity.WellConnectivity;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.time.LocalDateTime;
import java.util.List;

@Getter
public class ConnectivityAnalysisCompletedEvent extends ApplicationEvent {

    private final String blockName;
    private final List<WellConnectivity> connectivityResults;
    private final LocalDateTime analysisTime;
    private final int wellPairCount;

    public ConnectivityAnalysisCompletedEvent(Object source, String blockName,
                                              List<WellConnectivity> connectivityResults) {
        super(source);
        this.blockName = blockName;
        this.connectivityResults = connectivityResults;
        this.analysisTime = LocalDateTime.now();
        this.wellPairCount = connectivityResults != null ? connectivityResults.size() : 0;
    }
}
