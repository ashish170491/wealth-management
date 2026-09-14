package com.example.trading.portfolio;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PortfolioTargetWeightRepository extends JpaRepository<PortfolioTargetWeightEntity, Long> {

    List<PortfolioTargetWeightEntity> findByProfileId(Long profileId);

    List<PortfolioTargetWeightEntity> findByProfileIdAndBucketType(Long profileId, String bucketType);

    @Modifying
    @Query("DELETE FROM PortfolioTargetWeightEntity w WHERE w.profileId = :profileId")
    void deleteByProfileId(@Param("profileId") Long profileId);
}
