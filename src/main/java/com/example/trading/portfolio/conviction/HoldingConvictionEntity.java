package com.example.trading.portfolio.conviction;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Captures the purchase rationale for a holding: thesis, conviction, horizon,
 * invalidation triggers, and the multibagger score at entry (for drift tracking).
 * See SPEC.md §6.
 */
@Entity
@Table(name = "holding_conviction", indexes = {
        @Index(name = "idx_conviction_symbol", columnList = "symbol", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldingConvictionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String symbol;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String thesis;

    @Column(nullable = false)
    private int convictionScore; // 1–10

    private Integer holdingHorizonMonths;

    /**
     * True when {@link #holdingHorizonMonths} came from the investor, false or null when it is the
     * 24-month value {@code ConvictionService} seeds onto auto-generated records.
     *
     * <p>Without this flag the two are indistinguishable, and the core-holding gate that reads
     * "the investor's own stated intent" (SPEC §35.2 G7) was judging a system default: all 46
     * seeded records carry 24 months, which failed the 36-month bar and demoted holdings on a
     * number nobody chose. A default is not a statement, in the same way that a null is not a zero.
     */
    private Boolean horizonStated;

    /**
     * True when the investor wrote {@link #thesis}; false when {@code ConvictionService} generated
     * it from screening history or a placeholder (B-097). On the live portfolio 45 of 46 records
     * were generated, so a "thesis intact" count over all records was a count of the app agreeing
     * with itself. Only stated theses are counted; a seeded one is reported as
     * <em>no thesis written yet</em>, with the generated text kept as a starting point. A null on a
     * legacy row is derived from the text and from {@link #horizonStated} on read.
     */
    private Boolean thesisStated;

    @Column(columnDefinition = "TEXT")
    private String invalidationTriggers; // free-text, optionally multi-line

    /** Multibagger composite score at purchase — the baseline for drift. */
    private Double purchaseMultibaggerScore;

    @Column(nullable = false)
    private LocalDate purchaseDate;

    /**
     * Manual core-tier override (SPEC §35.6): {@code FORCE_CORE} / {@code FORCE_SATELLITE} / null.
     * The investor's own judgement outranks the gates — but it is recorded with a timestamp and a
     * note, and it hides nothing: a forensic flag still appears in red in the forensic section of
     * the same email that shows the stock as core.
     */
    @Column(length = 32)
    private String coreOverride;

    @Column(columnDefinition = "TEXT")
    private String coreOverrideNote;

    private LocalDateTime coreOverrideAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
