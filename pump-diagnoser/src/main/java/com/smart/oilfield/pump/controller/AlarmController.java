package com.smart.oilfield.pump.controller;

import com.smart.oilfield.common.entity.Alarm;
import com.smart.oilfield.common.repository.AlarmRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmRepository alarmRepository;

    @GetMapping
    public ResponseEntity<List<Alarm>> getAllAlarms(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String wellId) {

        List<Alarm> alarms;
        if (wellId != null) {
            alarms = alarmRepository.findByWellIdOrderByAlarmTimeDesc(wellId);
        } else if (level != null) {
            alarms = alarmRepository.findByAlarmLevelOrderByAlarmTimeDesc(level);
        } else {
            alarms = alarmRepository.findAll();
        }
        return ResponseEntity.ok(alarms);
    }

    @GetMapping("/unacknowledged")
    public ResponseEntity<Map<String, Object>> getUnacknowledgedAlarms() {
        List<Alarm> alarms = alarmRepository.findByIsAcknowledgedFalseOrderByAlarmTimeDesc();
        Long count = alarmRepository.countUnacknowledgedAlarms();

        Map<String, Object> result = new HashMap<>();
        result.put("count", count);
        result.put("alarms", alarms);
        result.put("level1Count", alarms.stream()
                .filter(a -> "LEVEL_1".equals(a.getAlarmLevel())).count());
        result.put("level2Count", alarms.stream()
                .filter(a -> "LEVEL_2".equals(a.getAlarmLevel())).count());

        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<Alarm> acknowledgeAlarm(@PathVariable Long id) {
        return alarmRepository.findById(id).map(alarm -> {
            alarm.setIsAcknowledged(true);
            alarm.setAcknowledgeTime(LocalDateTime.now());
            return ResponseEntity.ok(alarmRepository.save(alarm));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/check-now")
    public ResponseEntity<Map<String, Object>> triggerAlarmCheck() {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Alarm check completed");
        result.put("unacknowledgedCount", alarmRepository.countUnacknowledgedAlarms());
        return ResponseEntity.ok(result);
    }
}
