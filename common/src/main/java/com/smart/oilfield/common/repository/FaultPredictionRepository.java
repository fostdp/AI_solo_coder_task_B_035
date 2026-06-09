package com.smart.oilfield.common.repository;

import com.smart.oilfield.common.entity.FaultPrediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FaultPredictionRepository extends JpaRepository<FaultPrediction, Long> {

    List<FaultPrediction> findByWellIdOrderByPredictionTimeDesc(String wellId);

    List<FaultPrediction> findByIsAcknowledgedFalseOrderByPredictionTimeDesc();

    List<FaultPrediction> findBySeverityLevelAndIsAcknowledgedFalseOrderByPredictionTimeDesc(String severityLevel);

    @Query("SELECT f FROM FaultPrediction f WHERE f.wellId = :wellId " +
           "AND f.predictionTime = (SELECT MAX(f2.predictionTime) FROM FaultPrediction f2 " +
           "WHERE f2.wellId = :wellId) ORDER BY f.faultProbability DESC")
    List<FaultPrediction> findLatestByWellId(@Param("wellId") String wellId);

    Optional<FaultPrediction> findByPredictionId(String predictionId);

    @Query("SELECT f FROM FaultPrediction f WHERE f.predictionTime >= :startTime " +
           "AND f.predictionTime <= :endTime ORDER BY f.predictionTime DESC")
    List<FaultPrediction> findByPredictionTimeRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT f FROM FaultPrediction f WHERE f.wellId = :wellId " +
           "AND f.faultType = :faultType AND f.isAcknowledged = false " +
           "ORDER BY f.predictionTime DESC LIMIT 1")
    Optional<FaultPrediction> findLatestUnacknowledgedByWellAndType(
            @Param("wellId") String wellId,
            @Param("faultType") String faultType);

    @Query("SELECT f FROM FaultPrediction f WHERE f.severityLevel IN ('CRITICAL', 'WARNING') " +
           "AND f.isAcknowledged = false ORDER BY f.predictionTime DESC")
    List<FaultPrediction> findHighPriorityUnacknowledged();

    @Query("SELECT COUNT(f) FROM FaultPrediction f WHERE f.wellId = :wellId " +
           "AND f.actualFaultOccurred = true AND f.predictionTime >= :startTime")
    Long countActualFaultsByWell(
            @Param("wellId") String wellId,
            @Param("startTime") LocalDateTime startTime);

    @Query("SELECT f FROM FaultPrediction f WHERE f.actualFaultOccurred = true " +
           "ORDER BY f.actualFaultTime DESC")
    List<FaultPrediction> findActualFaults();

    @Query("SELECT DISTINCT f.wellId FROM FaultPrediction f " +
           "WHERE f.predictionTime >= :startTime AND f.isAcknowledged = false")
    List<String> findWellsWithActivePredictions(@Param("startTime") LocalDateTime startTime);

    @Query("SELECT MAX(f.faultProbability) FROM FaultPrediction f " +
           "WHERE f.wellId = :wellId AND f.predictionTime >= :startTime")
    Double findMaxFaultProbabilityByWell(
            @Param("wellId") String wellId,
            @Param("startTime") LocalDateTime startTime);

    List<FaultPrediction> findByWellIdAndPredictionTimeAfterOrderByPredictionTimeDesc(
            String wellId, LocalDateTime predictionTime);

    @Query("SELECT f FROM FaultPrediction f WHERE f.predictionTime >= :startTime " +
           "AND f.isAcknowledged = false ORDER BY f.predictionTime DESC")
    List<FaultPrediction> findAllLatest(@Param("startTime") LocalDateTime startTime);

    @Query("SELECT f FROM FaultPrediction f WHERE f.predictionTime >= :startTime " +
           "ORDER BY f.predictionTime DESC")
    List<FaultPrediction> findAllLatestWithAcknowledged(@Param("startTime") LocalDateTime startTime);
}
