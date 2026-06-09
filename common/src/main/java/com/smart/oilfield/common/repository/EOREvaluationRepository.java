package com.smart.oilfield.common.repository;

import com.smart.oilfield.common.entity.EOREvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface EOREvaluationRepository extends JpaRepository<EOREvaluation, Long> {

    List<EOREvaluation> findByBlockNameOrderByEvaluationDateDesc(String blockName);

    List<EOREvaluation> findByEorTypeOrderByEvaluationDateDesc(String eorType);

    List<EOREvaluation> findByBlockNameAndEvaluationDate(String blockName, LocalDate evaluationDate);

    @Query("SELECT e FROM EOREvaluation e WHERE e.blockName = :blockName " +
           "AND e.evaluationDate = (SELECT MAX(e2.evaluationDate) FROM EOREvaluation e2 " +
           "WHERE e2.blockName = :blockName) ORDER BY e.overallScore DESC")
    List<EOREvaluation> findLatestByBlockName(@Param("blockName") String blockName);

    Optional<EOREvaluation> findByBlockNameAndEorTypeAndEvaluationDate(
            String blockName, String eorType, LocalDate evaluationDate);

    @Query("SELECT e FROM EOREvaluation e WHERE e.isRecommended = true " +
           "ORDER BY e.evaluationDate DESC, e.overallScore DESC")
    List<EOREvaluation> findAllRecommended();

    @Query("SELECT e FROM EOREvaluation e WHERE e.blockName = :blockName " +
           "AND e.evaluationDate >= :startDate AND e.evaluationDate <= :endDate " +
           "ORDER BY e.evaluationDate DESC, e.overallScore DESC")
    List<EOREvaluation> findByBlockNameAndDateRange(
            @Param("blockName") String blockName,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT e FROM EOREvaluation e WHERE e.overallScore >= :minScore " +
           "AND e.recommendation = 'RECOMMENDED' ORDER BY e.overallScore DESC")
    List<EOREvaluation> findByMinScoreAndRecommended(@Param("minScore") Double minScore);

    @Query("SELECT DISTINCT e.blockName FROM EOREvaluation e")
    List<String> findAllBlockNames();

    @Query("SELECT DISTINCT e.eorType FROM EOREvaluation e")
    List<String> findAllEorTypes();

    @Query("SELECT e FROM EOREvaluation e WHERE e.blockName = :blockName " +
           "AND e.isRecommended = true ORDER BY e.overallScore DESC LIMIT 1")
    Optional<EOREvaluation> findTopRecommendedByBlockName(@Param("blockName") String blockName);
}
