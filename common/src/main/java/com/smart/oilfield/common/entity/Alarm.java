package com.smart.oilfield.common.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "alarms")
public class Alarm {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alarm_id", length = 64, nullable = false, unique = true)
    private String alarmId;

    @Column(name = "well_id", length = 32, nullable = false)
    private String wellId;

    @Column(name = "alarm_level", length = 20, nullable = false)
    private String alarmLevel;

    @Column(name = "alarm_type", length = 50, nullable = false)
    private String alarmType;

    @Column(name = "alarm_message", length = 500, nullable = false)
    private String alarmMessage;

    @Column(name = "alarm_value", precision = 10, scale = 2)
    private Double alarmValue;

    @Column(name = "threshold_value", precision = 10, scale = 2)
    private Double thresholdValue;

    @Column(name = "alarm_time", nullable = false)
    private LocalDateTime alarmTime;

    @Column(name = "is_pushed")
    private Boolean isPushed = false;

    @Column(name = "is_acknowledged")
    private Boolean isAcknowledged = false;

    @Column(name = "acknowledge_time")
    private LocalDateTime acknowledgeTime;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }
}
