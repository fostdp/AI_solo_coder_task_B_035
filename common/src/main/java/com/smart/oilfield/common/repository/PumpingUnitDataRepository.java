package com.smart.oilfield.common.repository;

import com.smart.oilfield.common.entity.PumpingUnitData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PumpingUnitDataRepository extends JpaRepository<PumpingUnitData, Long> {

    List<PumpingUnitData> findByWellIdOrderByRecordTimeDesc(String wellId);

    @Query("SELECT p FROM PumpingUnitData p WHERE p.wellId = :wellId " +
           "AND p.recordTime >= :startTime AND p.recordTime <= :endTime " +
           "ORDER BY p.recordTime")
    List<PumpingUnitData> findByWellIdAndTimeRange(
            @Param("wellId") String wellId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT p FROM PumpingUnitData p WHERE p.wellId = :wellId " +
           "ORDER BY p.recordTime DESC LIMIT :limit")
    List<PumpingUnitData> findLatestByWellId(@Param("wellId") String wellId, @Param("limit") int limit);

    @Query("SELECT p FROM PumpingUnitData p WHERE p.wellId = :wellId " +
           "AND p.recordTime = (SELECT MAX(p2.recordTime) FROM PumpingUnitData p2 " +
           "WHERE p2.wellId = :wellId)")
    Optional<PumpingUnitData> findLatestRecordByWellId(@Param("wellId") String wellId);

    @Query("SELECT p FROM PumpingUnitData p WHERE p.isAnomaly = true " +
           "AND p.recordTime >= :startTime ORDER BY p.recordTime DESC")
    List<PumpingUnitData> findRecentAnomalies(@Param("startTime") LocalDateTime startTime);

    @Query("SELECT AVG(p.dynamicFluidLevel) FROM PumpingUnitData p " +
           "WHERE p.wellId = :wellId AND p.recordTime >= :startTime")
    Double calculateAverageFluidLevel(
            @Param("wellId") String wellId,
            @Param("startTime") LocalDateTime startTime);

    @Query("SELECT AVG(p.motorCurrent) FROM PumpingUnitData p " +
           "WHERE p.wellId = :wellId AND p.recordTime >= :startTime")
    Double calculateAverageCurrent(
            @Param("wellId") String wellId,
            @Param("startTime") LocalDateTime startTime);

    @Query("SELECT STDDEV(p.dynamicFluidLevel) FROM PumpingUnitData p " +
           "WHERE p.wellId = :wellId AND p.recordTime >= :startTime")
    Double calculateStdDevFluidLevel(
            @Param("wellId") String wellId,
            @Param("startTime") LocalDateTime startTime);

    @Query("SELECT STDDEV(p.motorCurrent) FROM PumpingUnitData p " +
           "WHERE p.wellId = :wellId AND p.recordTime >= :startTime")
    Double calculateStdDevCurrent(
            @Param("wellId") String wellId,
            @Param("startTime") LocalDateTime startTime);

    @Query("SELECT DISTINCT p.wellId FROM PumpingUnitData p WHERE p.recordTime >= :startTime")
    List<String> findWellsWithRecentData(@Param("startTime") LocalDateTime startTime);

    List<PumpingUnitData> findByWellIdAndIsAnomalyTrueOrderByRecordTimeDesc(String wellId);
}
