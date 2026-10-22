package com.example.trading.valuation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of the method-validation record (SPEC §57.6): the valuation-gap lens's own deterministic
 * <b>inputs and fit</b>, frozen when they were computed and before any outcome exists, so that the
 * method - a log-linear earnings trend over five to ten years - can later be graded against
 * <i>filed</i> FY2027 to FY2031 results by the pre-registered rule VG-V1.
 *
 * <p><b>What this table is not.</b> It grades a method, not a business. It steers nothing: no
 * report, score, recommendation or threshold reads it. It stores <b>no future earnings level, no
 * grid, no reference multiple, no verdict, no AI text and no analyst data</b> - a future level is a
 * deterministic function of the stored slope and intercept, so storing one would add exposure
 * under SPEC §25.3 and no evidence. Nothing here is presented as an estimate of anything.
 *
 * <p><b>Append-only, enforced three ways.</b> {@link Immutable} makes Hibernate refuse to issue an
 * UPDATE for the entity; the repository exposes no delete and no bulk save; and a source-scan test
 * fails if anything else refers to the repository. A row is written only when the method's inputs
 * change ({@link #inputsHash}), so one stock yields about one row per fiscal-year filing plus
 * revisions, not one per week.
 *
 * <p><b>Schema rules, each a recorded gotcha.</b> Wrapper types only (a primitive added to a
 * populated table is B-026); status and reason are plain strings, not {@code @Enumerated}, because
 * Hibernate would bake last month's enum into a CHECK constraint that {@code ddl-auto=update}
 * never rewrites (Gotcha 148); JSON lives in {@code TEXT} columns.
 *
 * <p><b>Retained forever.</b> No cleanup arm exists and none may be added: the evidence must
 * outlive the reference year, since FY2031 filings land around April to June 2031.
 */
@Entity
@Immutable
@Table(name = "valuation_gap_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_vgap_snapshot_symbol_ruleset_inputs",
                columnNames = {"symbol", "ruleset", "inputs_hash"}),
        indexes = {
                @Index(name = "idx_vgap_snapshot_date", columnList = "snapshot_date"),
                @Index(name = "idx_vgap_snapshot_symbol_base", columnList = "symbol, base_fiscal_year"),
                @Index(name = "idx_vgap_snapshot_status", columnList = "status")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValuationGapSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The price date: the latest <i>complete</i> screening date the row was built from. */
    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    /** The KNOWLEDGE date: when this row was written. Every look-ahead check uses this, not the price date. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** As screened, e.g. {@code NSE:RELIANCE}. */
    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    /** The spelling whose annual rows were used (Gotcha 84, 107). */
    @Column(name = "symbol_answered", length = 32)
    private String symbolAnswered;

    @Column(name = "ruleset", nullable = false, length = 16)
    private String ruleset;

    /** Hash of every constant, including the validation rule and the reference multiples. */
    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    /** Of the price row; null means unknown provenance (SPEC §38.5). */
    @Column(name = "scoring_version", length = 64)
    private String scoringVersion;

    /** {@code SCHEDULED} or {@code MANUAL}. */
    @Column(name = "capture_origin", nullable = false, length = 16)
    private String captureOrigin;

    /** {@code MEASURED}, {@code PARTIAL} or {@code UNAVAILABLE}. */
    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "reason_code", length = 40)
    private String reasonCode;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "price") private Double price;
    @Column(name = "market_cap_cr") private Double marketCapCr;
    @Column(name = "pe0") private Double pe0;

    @Column(name = "base_fiscal_year") private Integer baseFiscalYear;
    @Column(name = "first_fiscal_year") private Integer firstFiscalYear;
    @Column(name = "observations") private Integer observations;
    @Column(name = "consolidated") private Boolean consolidated;

    @Column(name = "latest_eps") private Double latestEps;
    /** The pre-registered anchor for grading (rule VG-V1). */
    @Column(name = "normalised_eps") private Double normalisedEps;
    @Column(name = "normalisation_flags", length = 120) private String normalisationFlags;

    /** Fitted ln(EPS) at the base fiscal year. With the slope this is the whole fit. */
    @Column(name = "trend_ln_intercept") private Double trendLnIntercept;
    @Column(name = "trend_ln_slope") private Double trendLnSlope;
    @Column(name = "trend_growth_pct") private Double trendGrowthPct;
    @Column(name = "trend_std_err_pp") private Double trendStdErrPp;
    @Column(name = "trend_r_squared") private Double trendRSquared;
    /** {@code GATES_PASSED}, {@code GATES_WITHHELD} or {@code NOT_FITTED}: a label, not a precondition. */
    @Column(name = "trend_cohort", length = 16) private String trendCohort;
    @Column(name = "endpoint_cagr_pct") private Double endpointCagrPct;

    @Column(name = "share_count_base_cr") private Double shareCountBaseCr;
    @Column(name = "face_value_base") private Double faceValueBase;

    /** The existing reverse-DCF reading, copied for provenance only. */
    @Column(name = "dcf_implied_growth_pct") private Double dcfImpliedGrowthPct;
    @Column(name = "crosscheck_ratio") private Double crosscheckRatio;

    /** Raw and adjusted series per year with provenance, kept on refusal rows too. */
    @Column(name = "eps_series_json", columnDefinition = "TEXT")
    private String epsSeriesJson;

    /** Corporate actions divided out. */
    @Column(name = "actions_json", columnDefinition = "TEXT")
    private String actionsJson;

    /** SHA-256 of the method's canonical inputs. Not price, market cap or the reverse-DCF columns. */
    @Column(name = "inputs_hash", nullable = false, length = 64)
    private String inputsHash;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
