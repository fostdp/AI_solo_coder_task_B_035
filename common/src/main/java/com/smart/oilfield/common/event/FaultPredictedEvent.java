package com.smart.oilfield.common.event;

import com.smart.oilfield.common.entity.FaultPrediction;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.time.LocalDateTime;
import java.util.List;

@Getter
public class FaultPredictedEvent extends ApplicationEvent {

    private final String wellId;
    private final List<FaultPrediction> predictions;
    private final LocalDateTime predictionTime;
    private final int criticalFaultCount;
    private final int warningFaultCount;

    public FaultPredictedEvent(Object source, String wellId, List<FaultPrediction> predictions) {
        super(source);
        this.wellId = wellId;
        this.predictions = predictions;
        this.predictionTime = LocalDateTime.now();
        this.criticalFaultCount = (int) predictions.stream()
                .filter(p -> "CRITICAL".equals(p.getSeverityLevel())).count();
        this.warningFaultCount = (int) predictions.stream()
                .filter(p -> "WARNING".equals(p.getSeverityLevel())).count();
    }
}
