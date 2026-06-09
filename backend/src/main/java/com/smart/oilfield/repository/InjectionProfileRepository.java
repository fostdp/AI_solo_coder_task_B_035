package com.smart.oilfield.repository;

import com.smart.oilfield.entity.InjectionProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface InjectionProfileRepository extends JpaRepository<InjectionProfile, Long> {

    List<InjectionProfile> findByWellIdOrderByLayerNumber(String wellId);

    List<InjectionProfile> findByWellIdAndProfileDateOrderByLayerNumber(String wellId, LocalDate profileDate);

    @Query("SELECT p FROM InjectionProfile p WHERE p.wellId = :wellId " +
           "AND p.profileDate = (SELECT MAX(p2.profileDate) FROM InjectionProfile p2 " +
           "WHERE p2.wellId = :wellId) ORDER BY p.layerNumber")
    List<InjectionProfile> findLatestByWellId(@Param("wellId") String wellId);

    Optional<InjectionProfile> findByWellIdAndLayerNumberAndProfileDate(
            String wellId, Integer layerNumber, LocalDate profileDate);

    @Query("SELECT p FROM InjectionProfile p WHERE p.wellId = :wellId " +
           "AND p.profileDate >= :startDate AND p.profileDate <= :endDate " +
           "ORDER BY p.profileDate, p.layerNumber")
    List<InjectionProfile> findByWellIdAndDateRange(
            @Param("wellId") String wellId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT DISTINCT p.wellId FROM InjectionProfile p " +
           "WHERE p.profileDate = (SELECT MAX(p2.profileDate) FROM InjectionProfile p2)")
    List<String> findAllWellIdsWithLatestProfile();

    @Query("SELECT p FROM InjectionProfile p WHERE p.adjustmentDirection != 'KEEP' " +
           "AND p.allocationStatus = 'PENDING' ORDER BY p.profileDate DESC")
    List<InjectionProfile> findPendingAdjustments();

    @Query("SELECT SUM(p.currentInjectionVolume) FROM InjectionProfile p " +
           "WHERE p.wellId = :wellId AND p.profileDate = :profileDate")
    Double sumCurrentInjectionByWellAndDate(
            @Param("wellId") String wellId,
            @Param("profileDate") LocalDate profileDate);

    @Query("SELECT SUM(p.suggestedInjectionVolume) FROM InjectionProfile p " +
           "WHERE p.wellId = :wellId AND p.profileDate = :profileDate")
    Double sumSuggestedInjectionByWellAndDate(
            @Param("wellId") String wellId,
            @Param("profileDate") LocalDate profileDate);

    List<InjectionProfile> findByAllocationStatusOrderByProfileDateDesc(String allocationStatus);
}
