package com.smart.oilfield.common.repository;

import com.smart.oilfield.common.entity.Alarm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AlarmRepository extends JpaRepository<Alarm, Long> {

    List<Alarm> findByWellIdOrderByAlarmTimeDesc(String wellId);

    List<Alarm> findByIsAcknowledgedFalseOrderByAlarmTimeDesc();

    List<Alarm> findByAlarmLevelAndIsAcknowledgedFalseOrderByAlarmTimeDesc(String alarmLevel);

    @Query("SELECT a FROM Alarm a WHERE a.wellId = :wellId " +
           "AND a.alarmTime = (SELECT MAX(a2.alarmTime) FROM Alarm a2 " +
           "WHERE a2.wellId = :wellId) ORDER BY a.alarmLevel DESC")
    List<Alarm> findLatestByWellId(@Param("wellId") String wellId);

    Optional<Alarm> findByAlarmId(String alarmId);

    @Query("SELECT a FROM Alarm a WHERE a.alarmTime >= :startTime " +
           "AND a.alarmTime <= :endTime ORDER BY a.alarmTime DESC")
    List<Alarm> findByAlarmTimeRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT a FROM Alarm a WHERE a.alarmLevel IN ('LEVEL_1', 'LEVEL_2') " +
           "AND a.isAcknowledged = false ORDER BY a.alarmTime DESC")
    List<Alarm> findHighPriorityUnacknowledged();

    @Query("SELECT COUNT(a) FROM Alarm a WHERE a.wellId = :wellId " +
           "AND a.isAcknowledged = false AND a.alarmTime >= :startTime")
    Long countUnacknowledgedByWell(
            @Param("wellId") String wellId,
            @Param("startTime") LocalDateTime startTime);

    @Query("SELECT DISTINCT a.wellId FROM Alarm a " +
           "WHERE a.alarmTime >= :startTime AND a.isAcknowledged = false")
    List<String> findWellsWithActiveAlarms(@Param("startTime") LocalDateTime startTime);

    @Query("SELECT a FROM Alarm a WHERE a.alarmTime >= :startTime " +
           "AND a.isAcknowledged = false ORDER BY a.alarmTime DESC")
    List<Alarm> findAllLatest(@Param("startTime") LocalDateTime startTime);
}
