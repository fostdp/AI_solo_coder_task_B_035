package com.smart.oilfield.common.event;

import com.smart.oilfield.common.entity.InjectionProfile;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.time.LocalDateTime;
import java.util.List;

@Getter
public class ProfileAdjustmentCompletedEvent extends ApplicationEvent {

    private final String wellId;
    private final List<InjectionProfile> adjustedProfiles;
    private final LocalDateTime adjustmentTime;
    private final boolean adjustmentSuccess;
    private final String message;

    public ProfileAdjustmentCompletedEvent(Object source, String wellId,
                                           List<InjectionProfile> adjustedProfiles,
                                           boolean adjustmentSuccess, String message) {
        super(source);
        this.wellId = wellId;
        this.adjustedProfiles = adjustedProfiles;
        this.adjustmentTime = LocalDateTime.now();
        this.adjustmentSuccess = adjustmentSuccess;
        this.message = message;
    }
}
