package com.smart.oilfield.common.repository;

import com.smart.oilfield.common.entity.ProductionData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ProductionDataRepository extends JpaRepository<ProductionData, Long> {

    List<ProductionData> findByWellIdOrderByReportDateDesc(String wellId);

    List<ProductionData> findByWellIdAndReportDateBetweenOrderByReportDate(
            String wellId, LocalDate startDate, LocalDate endDate);

    @Query("SELECT COALESCE(SUM(p.oilVolume), 0) FROM ProductionData p " +
           "WHERE p.reportDate = :reportDate")
    Double sumOilVolumeByDate(@Param("reportDate") LocalDate reportDate);

    @Query("SELECT COALESCE(SUM(p.oilVolume), 0) FROM ProductionData p " +
           "JOIN Well well ON p.wellId = well.wellId " +
           "WHERE p.reportDate = :reportDate AND well.blockName = :blockName")
    Double sumOilVolumeByDateAndBlock(@Param("reportDate") LocalDate reportDate,
                                      @Param("blockName") String blockName);

    @Query("SELECT COALESCE(AVG(p.waterCut), 0) FROM ProductionData p " +
           "WHERE p.reportDate = :reportDate")
    Double avgWaterCutByDate(@Param("reportDate") LocalDate reportDate);

    @Query("SELECT COALESCE(AVG(p.waterCut), 0) FROM ProductionData p " +
           "JOIN Well well ON p.wellId = well.wellId " +
           "WHERE p.reportDate = :reportDate AND well.blockName = :blockName")
    Double avgWaterCutByDateAndBlock(@Param("reportDate") LocalDate reportDate,
                                     @Param("blockName") String blockName);

    @Query("SELECT p FROM ProductionData p WHERE p.wellId = :wellId " +
           "ORDER BY p.reportDate DESC LIMIT 1")
    ProductionData findLatestByWellId(@Param("wellId") String wellId);

    @Query("SELECT p FROM ProductionData p WHERE p.wellId = :wellId " +
           "AND p.reportDate >= :startDate ORDER BY p.reportDate DESC")
    List<ProductionData> findByWellIdFromDate(@Param("wellId") String wellId,
                                              @Param("startDate") LocalDate startDate);
}
