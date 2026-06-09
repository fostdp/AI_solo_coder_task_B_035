package com.smart.oilfield.common.repository;

import com.smart.oilfield.common.entity.AllocationSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AllocationSuggestionRepository extends JpaRepository<AllocationSuggestion, Long> {

    List<AllocationSuggestion> findByWellIdOrderBySuggestionDateDesc(String wellId);

    List<AllocationSuggestion> findBySuggestionDate(LocalDate suggestionDate);

    @Query("SELECT a FROM AllocationSuggestion a " +
           "WHERE a.suggestionDate = (SELECT MAX(a2.suggestionDate) FROM AllocationSuggestion a2)")
    List<AllocationSuggestion> findLatestSuggestions();

    @Query("SELECT a FROM AllocationSuggestion a " +
           "WHERE a.suggestionDate >= :startDate " +
           "ORDER BY a.suggestionDate DESC")
    List<AllocationSuggestion> findFromDate(@Param("startDate") LocalDate startDate);
}
