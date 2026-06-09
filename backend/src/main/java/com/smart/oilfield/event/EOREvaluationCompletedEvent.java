package com.smart.oilfield.event;

import com.smart.oilfield.entity.EOREvaluation;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.time.LocalDateTime;
import java.util.List;

@Getter
public class EOREvaluationCompletedEvent extends ApplicationEvent {

    private final String blockName;
    private final List<EOREvaluation> evaluations;
    private final LocalDateTime evaluationTime;
    private final EOREvaluation recommendedScenario;

    public EOREvaluationCompletedEvent(Object source, String blockName,
                                       List<EOREvaluation> evaluations,
                                       EOREvaluation recommendedScenario) {
        super(source);
        this.blockName = blockName;
        this.evaluations = evaluations;
        this.evaluationTime = LocalDateTime.now();
        this.recommendedScenario = recommendedScenario;
    }
}
