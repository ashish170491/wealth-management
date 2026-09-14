package com.example.trading.analyst;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One price target a named brokerage published, and what the share price did afterwards
 * (SPEC §49.2).
 *
 * <p>Written by {@link AnalystTargetCaptureService} (the claim) and
 * {@link AnalystTargetOutcomeService} (what happened). Everything the dashboard needs is on the
 * row, so both analyst screens are DB-only on page load (SPEC §27.4).
 *
 * <p><b>Rows are never deleted and a resolved row is never rewritten.</b> A ledger that quietly
 * drops the calls that went wrong is not a track record, it is marketing — the same rule that
 * keeps retired universe rows and the negative-IC sector-reversal history on file (SPEC §25.1,
 * Gotcha 38). A target that is later revised is marked {@code SUPERSEDED} and kept.
 *
 * <p><b>Every measured figure is a wrapper type.</b> A target whose issue-date close could not
 * be recovered has no upside percentage and no return — null, never zero, because a zero here
 * would read as "the call went nowhere" rather than "we could not price it" (Gotcha 21,
 * SPEC §21 rule 7).
 */
@Entity
@Table(name = "analyst_targets",
        uniqueConstraints = @UniqueConstraint(name = "uk_analyst_target_dedup", columnNames = "dedupKey"),
        indexes = {
                @Index(name = "idx_analyst_target_symbol", columnList = "symbol"),
                @Index(name = "idx_analyst_target_status", columnList = "status"),
                @Index(name = "idx_analyst_target_issued", columnList = "issuedOn"),
                @Index(name = "idx_analyst_target_brokerage", columnList = "brokerage")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalystTargetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Qualified NSE symbol, so it joins to screening history the way every other table does. */
    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(length = 160)
    private String companyName;

    /** Canonical house name from {@link Brokerages}. Never "unnamed" — an unattributable call is dropped. */
    @Column(nullable = false, length = 64)
    private String brokerage;

    /** BUY / HOLD / SELL / NOT_STATED. NOT_STATED is an absence, never a HOLD. */
    @Column(length = 16)
    private String rating;

    /** INITIATE / UPGRADE / DOWNGRADE / RAISE_TARGET / CUT_TARGET / MAINTAIN / NOT_STATED. */
    @Column(length = 16)
    private String action;

    @Column(nullable = false)
    private Double targetPrice;

    /** Publication date of the headline, not the date this app read it. */
    @Column(nullable = false)
    private LocalDate issuedOn;

    /**
     * The date the house put on its own note, when the source states one.
     *
     * <p>Distinct from {@code issuedOn}, which is the date the claim became publicly readable and
     * is therefore the date measurement runs from. The gap is usually a day. Measuring from the
     * note date instead would credit the house with whatever the price did while the note was
     * still private - a look-ahead that flatters the analyst, which is the one direction a track
     * record must never lean (the rule SPEC §32.6 introduced {@code available_from} for).
     *
     * <p>Null on the headline path, which has only a publication date.
     */
    private LocalDate calledOn;

    /**
     * The current price the note itself quoted, when the source carries one.
     *
     * <p>Never used to measure anything. {@code priceAtCall} stays the app's own close on
     * {@code issuedOn}, because a record the app keeps on somebody else has to be measured on the
     * app's own yardstick. This column exists so the two can be compared - a persistent gap
     * between them says the feed's dates are drifting, which is a thing worth being able to see.
     */
    private Double brokerStatedPrice;

    /** Applied convention when the headline stated none — see {@link #horizonStated}. */
    @Column(nullable = false)
    private Integer horizonDays;

    /**
     * Whether the headline actually stated a horizon.
     *
     * <p>Twelve months is the Indian sell-side convention and it is what this app assumes, but an
     * assumption must never be readable as a statement: B-057 failed 14 of 33 holdings on a
     * seeded conviction horizon nobody had chosen, and B-097 counted 45 app-generated theses as
     * the investor's own. Same column, third time, written up front.
     */
    @Column(nullable = false)
    private boolean horizonStated;

    /** {@code issuedOn + horizonDays}. Stored so the due date is a fact, not a re-derivation. */
    @Column(nullable = false)
    private LocalDate resolvesOn;

    /** Daily close on the issue date. Null when candles could not reach it. */
    private Double priceAtCall;

    /** Nifty 50 close on the issue date, for the neutral yardstick. Null when unavailable. */
    private Double niftyAtCall;

    /** Target vs the issue-date close, percent. Null when the close is unknown. */
    private Double upsidePctAtCall;

    /**
     * ABOVE / BELOW / UNKNOWN — whether the target sat above or below the price when it was made.
     *
     * <p>Load-bearing for measurement: a target below the price is reached when the low falls to
     * it, not when the high rises to it. UNKNOWN means the issue-date price is missing, and such
     * a row is never counted as reached or missed.
     */
    @Column(length = 8)
    private String direction;

    /** PENDING / REACHED / MISSED / SUPERSEDED / UNPRICED. */
    @Column(nullable = false, length = 16)
    private String status;

    /** First date the price touched the target. Null until it does — never the issue date. */
    private LocalDate reachedOn;

    /** Calendar days from issue to {@link #reachedOn}. Null while pending. */
    private Integer daysToReach;

    /** Set when the same house issues a later target on the same stock. */
    private Long supersededByTargetId;
    private LocalDate supersededOn;

    // --- what the outcome pass measured, all null until it runs -------------------------

    private LocalDateTime lastMeasuredAt;
    private Double lastPrice;

    /** Stock return from the issue-date close to the last measurement, percent. */
    private Double returnPct;

    /** Nifty 50 return over exactly the same dates, percent. */
    private Double niftyReturnPct;

    /**
     * {@link #returnPct} minus {@link #niftyReturnPct}.
     *
     * <p>The neutral yardstick, and the reason it is here at all: whether a target was reached is
     * the analyst's own scoreboard, and a target hit during a rally is beta. Excess return over
     * the index is the same measure §23 applies to this app's own picks, so the two records can
     * be read against each other.
     */
    private Double excessReturnPct;

    /**
     * Best move in the call's own direction since issue, percent.
     *
     * <p>Distinguishes "never got close" from "got most of the way and fell back", which a
     * reached/missed flag alone cannot.
     */
    private Double maxFavourablePct;

    // --- provenance --------------------------------------------------------------------

    @Column(columnDefinition = "TEXT")
    private String headline;

    @Column(length = 200)
    private String sourceName;

    @Column(length = 1000)
    private String sourceUrl;

    /** Row id in {@code market_impact_news} this was read from, when it came from the stored feed. */
    private Long headlineId;

    /** The text the subject resolver actually matched, so an attribution can be audited. */
    @Column(length = 200)
    private String matchedOn;

    /** Parser version ({@link AnalystTargetParser#VERSION}), so a re-parse can be told apart. */
    @Column(length = 16)
    private String extractorVersion;

    /** symbol | brokerage | rounded target | issue date. Unique; see SPEC §49.5 on the two layers. */
    @Column(nullable = false, length = 200)
    private String dedupKey;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void onInsert() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
