package com.smart.oilfield.common.repository;

import com.smart.oilfield.common.entity.Well;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WellRepository extends JpaRepository<Well, String> {

    List<Well> findByWellType(String wellType);

    List<Well> findByBlockName(String blockName);

    List<Well> findByWellTypeAndBlockName(String wellType, String blockName);

    @Query("SELECT w FROM Well w WHERE w.status = 'ACTIVE'")
    List<Well> findAllActiveWells();

    @Query("SELECT w FROM Well w WHERE w.wellType = :wellType AND w.status = 'ACTIVE'")
    List<Well> findActiveWellsByType(@Param("wellType") String wellType);

    @Query("SELECT w FROM Well w WHERE w.blockName = :blockName AND w.wellType = :wellType")
    List<Well> findByBlockNameAndWellType(@Param("blockName") String blockName, @Param("wellType") String wellType);

    @Query("SELECT DISTINCT w.blockName FROM Well w")
    List<String> findDistinctBlockNames();

    Optional<Well> findByWellId(String wellId);
}
