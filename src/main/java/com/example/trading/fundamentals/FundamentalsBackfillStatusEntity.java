package com.example.trading.fundamentals;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * How far the annual-history backfill has got with one symbol (SPEC §32.6).
 *
 * <p><b>Why a status table at all.</b> The backfill is roughly 4,000 NSE requests across the
 * screening universe, spread over weeks of small batches. Without per-symbol state two things
 * are impossible. A run that dies part-way leaves no record of what was done. And, more subtly,
 * nothing can tell <b>"never attempted"</b> apart from <b>"attempted, and this company genuinely
 * only ever filed three annual results"</b> — both look like a symbol with three years of
 * history. A batch picker ordering by fewest-years-held then loops on the shallowest symbols
 * forever and never reaches the untouched ones.
 *
 * <p>It also makes the rollout observable: "how deep is the universe?" becomes a query rather
 * than a guess, which is what {@code GET /api/fundamentals/coverage} serves.
 */
@Entity
@Table(name = "fundamentals_backfill_status",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_fundamentals_backfill_symbol", columnNames = {"symbol"}),
        indexes = @Index(name = "idx_fundamentals_backfill_status", columnList = "status"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundamentalsBackfillStatusEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String symbol;

    /**
     * PENDING / COMPLETE / PARTIAL / FAILED / UNAVAILABLE.
     *
     * <p>{@code UNAVAILABLE} is a <b>finding, not a failure</b>: the archive listing returned
     * nothing for this symbol. That happens for a delisted predecessor, a company whose year end
     * is not 31 March (those filings are skipped upstream), and insurers behind the same data
     * wall that already returns NO_DATA for capital efficiency. It is recorded so the CSV import
     * can be pointed at exactly those names, rather than being silently indistinguishable from a
     * symbol nobody has got to yet.
     *
     * <p>{@code FAILED} is reserved for a listing call that errored. Recording a fetch failure as
     * UNAVAILABLE would turn a fact about our network into a fact about the business.
     */
    @Column(length = 16)
    private String status;

    private LocalDateTime lastAttemptAt;

    /** Attempts so far. Bounded by config so a permanently broken symbol stops consuming budget. */
    private Integer attempts;

    /** Annual filings the archive listed, after the max-years cut. */
    private Integer yearsInArchive;

    /** Years actually stored. COMPLETE is {@code yearsWritten + yearsSkippedBasis >= yearsInArchive}. */
    private Integer yearsWritten;

    /**
     * Years dropped because only an off-basis filing exists for them (Gotcha 73).
     *
     * <p>These count toward completion: the year was seen and deliberately left out to keep one
     * reporting basis across the series, so re-fetching would drop it again. A gap the downstream
     * checks already handle beats a fabricated collapse they cannot detect.
     */
    private Integer yearsSkippedBasis;

    /** Filings dropped for a non-March year end, counted rather than vanishing (defect 7.5). */
    private Integer nonMarchSkipped;

    /** True = consolidated series, false = standalone, null = never determined. */
    private Boolean consolidatedBasis;

    /** Named rather than swallowed — an empty result must always be able to say why. */
    @Column(length = 500)
    private String lastError;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public static final String PENDING = "PENDING";
    public static final String COMPLETE = "COMPLETE";
    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";
    public static final String UNAVAILABLE = "UNAVAILABLE";
}
