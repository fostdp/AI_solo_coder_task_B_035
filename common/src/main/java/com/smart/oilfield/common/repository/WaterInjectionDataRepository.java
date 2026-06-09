package com.smart.oilfield.common.repository;

import com.smart.oilfield.common.entity.WaterInjectionData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WaterInjectionDataRepository extends JpaRepository<WaterInjectionData, Long> {

    List<WaterInjectionData> findByWellIdOrderByReportDateDesc(String wellId);

    List<WaterInjectionData> findByWellIdAndReportDateBetweenOrderByReportDate(
            String wellId, LocalDate startDate, LocalDate endDate);

    Optional<WaterInjectionData> findTopByWellIdOrderByReportDateDesc(String wellId);

    @Query("SELECT COALESCE(SUM(w.waterVolume), 0) FROM WaterInjectionData w " +
           "WHERE w.reportDate = :reportDate")
    Double sumWaterVolumeByDate(@Param("reportDate") LocalDate reportDate);

    @Query("SELECT COALESCE(SUM(w.waterVolume), 0) FROM WaterInjectionData w " +
           "JOIN Well well ON w.wellId = well.wellId " +
           "WHERE w.reportDate = :reportDate AND well.blockName = :blockName")
    Double sumWaterVolumeByDateAndBlock(@Param("reportDate") LocalDate reportDate,
                                        @Param("blockName") String blockName);

    @Query("SELECT w FROM WaterInjectionData w WHERE w.wellId = :wellId " +
           "ORDER BY w.reportDate DESC LIMIT 1")
    WaterInjectionData findLatestByWellId(@Param("wellId") String wellId);

    @Query("SELECT w FROM WaterInjectionData w WHERE w.reportDate >= :startDate " +
           "ORDER BY w.wellId, w.reportDate")
    List<WaterInjectionData> findAllFromDate(@Param("startDate") LocalDate startDate);
}
