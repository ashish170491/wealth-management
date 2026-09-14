package com.example.trading.portfolio;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One target-weight row within a portfolio profile.
 *
 * <p>{@link #bucketType} is one of {@code SECTOR}, {@code MARKET_CAP}, {@code STOCK},
 * {@code ASSET_CLASS}. {@link #bucketKey} is the bucket identifier (e.g. {@code "IT"},
 * {@code "LARGE_CAP"}, {@code "NSE:RELIANCE"}, {@code "EQUITY"}).
 */
@Entity
@Table(name = "portfolio_target_weight",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ptw_profile_bucket",
                columnNames = {"profile_id", "bucket_type", "bucket_key"}),
        indexes = @Index(name = "idx_ptw_profile", columnList = "profile_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioTargetWeightEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    @Column(name = "bucket_type", nullable = false, length = 32)
    private String bucketType;

    @Column(name = "bucket_key", nullable = false, length = 128)
    private String bucketKey;

    /** Target weight as a percentage (0–100). */
    @Column(nullable = false)
    private Double targetWeight;
}
