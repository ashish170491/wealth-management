package com.example.trading.funds;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The record that a month was <em>asked about</em> — which is a different fact from whether any NAV
 * came back (SPEC §52.7).
 *
 * <p><b>This table is what makes the backfill survivable.</b> Without it there is no way to tell
 * <em>"we have never fetched March 2021"</em> from <em>"we fetched March 2021 and those schemes had
 * no NAV"</em>, and the two look identical in {@code mf_nav_history}: both are an absence of rows.
 * The first is a gap in this app; the second, read as fact, says a fund ceased to exist — and it is
 * exactly the input the survivorship ledger consumes, so the confusion would not stay harmless. It
 * is the same distinction {@code fundamentals_backfill_status} draws for annual accounts, where
 * "never attempted" and "this company has only ever filed three years" are indistinguishable from
 * the row count alone (Gotcha 102).
 *
 * <p>{@link #dateUsed} is <b>not</b> the calendar month end. A weekend or holiday month end is
 * walked back to the nearest business day that actually published, and recording which day answered
 * is the same discipline as {@code DecayAlert.resolvedSymbol} — a reading should be traceable rather
 * than assumed (Gotcha 84).
 */
@Entity
@Table(name = "mf_history_fetch",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mf_history_month", columnNames = {"forMonth"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MutualFundHistoryFetchEntity {

    /** What a fetch attempt concluded. Four values, because collapsing any two loses a fact. */
    public enum Status {
        /** A business day in the month published, and its NAVs are stored. */
        FETCHED,
        /**
         * Every candidate business day was asked and none carried an equity cross-section. Rare and
         * real — it is what a whole month of market closure would look like — and deliberately not
         * called an error, because nothing failed.
         */
        NOT_PUBLISHED,
        /**
         * The request itself did not succeed. Never {@link #NOT_PUBLISHED}: recording a transport
         * failure as a fact about the market is the B-054 lesson.
         */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The month this row answers for, stored as its first day so it sorts and compares naturally. */
    @Column(nullable = false)
    private LocalDate forMonth;

    /** The business day that actually answered — null when none did. */
    private LocalDate dateUsed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status;

    /** How many dates were tried before one answered. 1 on an ordinary month end. */
    private Integer datesTried;

    private Integer totalRows;

    /** The figure the acceptance test is actually made on — see {@link NavFileAcceptance}. */
    private Integer equityRows;

    private Integer navRowsWritten;

    @Column(nullable = false)
    private LocalDateTime attemptedAt;

    @Column(length = 500)
    private String note;
}
