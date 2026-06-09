package com.smart.oilfield.common.repository;

import com.smart.oilfield.common.entity.BlockDailySummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BlockDailySummaryRepository extends JpaRepository<BlockDailySummary, Long> {

    List<BlockDailySummary> findByBlockNameOrderBySummaryDateDesc(String blockName);

    Optional<BlockDailySummary> findByBlockNameAndSummaryDate(String blockName, LocalDate summaryDate);

    @Query("SELECT b FROM BlockDailySummary b WHERE b.summaryDate = :summaryDate")
    List<BlockDailySummary> findBySummaryDate(@Param("summaryDate") LocalDate summaryDate);

    @Query("SELECT b FROM BlockDailySummary b " +
           "WHERE b.summaryDate >= :startDate ORDER BY b.summaryDate DESC")
    List<BlockDailySummary> findFromDate(@Param("startDate") LocalDate startDate);

    @Query("SELECT NEW com.smart.oilfield.common.dto.BlockSummaryDTO(" +
           "'ALL', :date, " +
           "COALESCE(SUM(b.totalOilProduction), 0), " +
           "COALESCE(SUM(b.totalWaterInjection), 0), " +
           "COALESCE(AVG(b.averageWaterCut), 0)) " +
           "FROM BlockDailySummary b WHERE b.summaryDate = :date")
    com.smart.oilfield.common.dto.BlockSummaryDTO findAllBlocksSummaryByDate(@Param("date") LocalDate date);
}
