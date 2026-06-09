package com.smart.oilfield.repository;

import com.smart.oilfield.entity.WellConnectivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WellConnectivityRepository extends JpaRepository<WellConnectivity, Long> {

    List<WellConnectivity> findByInjectionWellIdAndAnalysisDate(String injectionWellId, LocalDate analysisDate);

    List<WellConnectivity> findByProductionWellIdAndAnalysisDate(String productionWellId, LocalDate analysisDate);

    List<WellConnectivity> findByAnalysisDate(LocalDate analysisDate);

    @Query("SELECT w FROM WellConnectivity w WHERE w.injectionWellId = :injectionWellId " +
           "AND w.analysisDate = (SELECT MAX(w2.analysisDate) FROM WellConnectivity w2 " +
           "WHERE w2.injectionWellId = :injectionWellId)")
    List<WellConnectivity> findLatestByInjectionWellId(@Param("injectionWellId") String injectionWellId);

    @Query("SELECT w FROM WellConnectivity w WHERE w.productionWellId = :productionWellId " +
           "AND w.analysisDate = (SELECT MAX(w2.analysisDate) FROM WellConnectivity w2 " +
           "WHERE w2.productionWellId = :productionWellId)")
    List<WellConnectivity> findLatestByProductionWellId(@Param("productionWellId") String productionWellId);

    @Query("SELECT w FROM WellConnectivity w WHERE " +
           "((w.injectionWellId = :wellId) OR (w.productionWellId = :wellId)) " +
           "AND w.analysisDate = (SELECT MAX(w2.analysisDate) FROM WellConnectivity w2 " +
           "WHERE (w2.injectionWellId = :wellId OR w2.productionWellId = :wellId))")
    List<WellConnectivity> findLatestByWellId(@Param("wellId") String wellId);

    Optional<WellConnectivity> findByInjectionWellIdAndProductionWellIdAndAnalysisDate(
            String injectionWellId, String productionWellId, LocalDate analysisDate);

    @Query("SELECT w FROM WellConnectivity w WHERE w.analysisDate >= :startDate " +
           "AND w.analysisDate <= :endDate AND w.isSignificant = true " +
           "ORDER BY w.connectivityStrength DESC")
    List<WellConnectivity> findSignificantInDateRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT DISTINCT w.injectionWellId FROM WellConnectivity w " +
           "WHERE w.analysisDate = (SELECT MAX(w2.analysisDate) FROM WellConnectivity w2)")
    List<String> findAllInjectionWellIdsWithLatestData();

    @Query("SELECT w FROM WellConnectivity w WHERE w.analysisDate = :analysisDate " +
           "AND w.connectivityStrength >= :minStrength ORDER BY w.connectivityStrength DESC")
    List<WellConnectivity> findByDateAndMinStrength(
            @Param("analysisDate") LocalDate analysisDate,
            @Param("minStrength") Double minStrength);
}
