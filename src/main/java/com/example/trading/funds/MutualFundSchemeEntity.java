package com.example.trading.funds;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One mutual fund scheme as AMFI describes it (SPEC §52.4).
 *
 * <p><b>{@code amfiSchemeCode} is the key, and that is a measured decision, not a convention.</b>
 * The live file carries 14,375 rows with 14,375 distinct scheme codes and <em>five duplicate
 * ISINs</em>. Kite returns the ISIN as its {@code tradingsymbol} on {@code /mf/holdings}, so the
 * ISIN lookup is a first-hit-wins hazard (Gotcha 107) and is indexed but never unique.
 *
 * <p><b>{@code categoryRaw} is stored beside the resolved {@link #category}</b> on purpose. AMFI
 * spells every equity category two ways and Sectoral/Thematic three; keeping its own wording is
 * what makes a mapping bug diagnosable instead of invisible (B-096's lesson).
 *
 * <p><b>Rows are never deleted.</b> A scheme that stops appearing in the feed has been merged or
 * wound up, and {@code lastSeenInFile} records when — which is precisely what makes survivorship
 * measurable rather than a hand-wave (SPEC §52.7, §25.1). Deleting it would delete the evidence.
 *
 * <p>Nullable by design: {@code nav} is null and never 0 when AMFI writes {@code N.A.} (Gotcha 21).
 */
@Entity
@Table(name = "mf_scheme",
        uniqueConstraints = @UniqueConstraint(name = "uk_mf_scheme_code", columnNames = "amfiSchemeCode"),
        indexes = {
                @Index(name = "idx_mf_scheme_isin", columnList = "isinGrowth"),
                @Index(name = "idx_mf_scheme_category", columnList = "category"),
                @Index(name = "idx_mf_scheme_screenable", columnList = "screenable")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MutualFundSchemeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** AMFI's scheme code. Unique across the whole feed — this is the identity. */
    @Column(nullable = false)
    private Long amfiSchemeCode;

    /** Indexed, <b>not</b> unique: five ISINs collide in the live file. */
    @Column(length = 24)
    private String isinGrowth;

    @Column(length = 24)
    private String isinReinvest;

    @Column(nullable = false, length = 300)
    private String schemeName;

    @Column(length = 200)
    private String amcName;

    /** AMFI's own header text, verbatim, so a category mapping bug can be diagnosed. */
    @Column(length = 300)
    private String categoryRaw;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private SebiCategory category;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private AssetClass assetClass;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SchemeStructure structure;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SchemePlanKey.Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SchemePlanKey.Option navOption;

    /**
     * True for the set this module measures: open-ended, actively managed equity, Direct plan,
     * Growth option. Denormalised so the common query is an index hit rather than four predicates.
     */
    private boolean screenable;

    /** Latest published NAV. Null, never 0, when AMFI published none. */
    @Column(precision = 18, scale = 6)
    private BigDecimal nav;

    /**
     * The date of the <b>value</b> — a bare date, so it is reported in days and never in hours
     * (B-119). Distinct from {@code mf_sync_run.lastPulledAt}, which is when the job ran.
     */
    private LocalDate navDate;

    private LocalDate firstSeen;

    /** When this scheme last appeared in the feed. A scheme that stops appearing has been merged
     *  or wound up; the row stays so the closure is on the record. */
    private LocalDate lastSeenInFile;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
