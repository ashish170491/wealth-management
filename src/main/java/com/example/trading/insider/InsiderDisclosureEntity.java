package com.example.trading.insider;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One SEBI disclosure of an insider transaction (SPEC §28, plan feature F1).
 *
 * <p>Sources: PIT Regulation 7(2) filings (per-symbol), SAST Regulation 29 filings, and the
 * daily bulk/block deal archives. Rows are re-published by NSE across days, so ingestion
 * must be idempotent — {@code disclosureHash} carries a uniqueness constraint over the
 * natural key, the same lesson as the tax-lot {@code trade_id}.
 */
@Entity
@Table(name = "insider_disclosures",
        uniqueConstraints = @UniqueConstraint(name = "uk_insider_disclosure", columnNames = "disclosure_hash"),
        indexes = {
                @Index(name = "idx_insider_symbol_date", columnList = "symbol,transactionDate"),
                @Index(name = "idx_insider_captured", columnList = "capturedAt")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsiderDisclosureEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Exchange-qualified symbol, e.g. NSE:RELIANCE. */
    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(length = 64)
    private String tradingSymbol;

    @Column(length = 256)
    private String personName;

    /** PROMOTER / PROMOTER_GROUP / DIRECTOR / KMP / EMPLOYEE / RELATIVE / OTHER / INSTITUTION. */
    @Column(length = 32)
    private String personCategory;

    /** BUY or SELL. */
    @Column(length = 8)
    private String transactionType;

    /**
     * Normalised acquisition mode. Only {@code MARKET_PURCHASE} / {@code MARKET_SALE} carry
     * signal; PLEDGE, ESOP, INTER_SE, GIFT and OFF_MARKET are recorded but never scored.
     * Those are the classic false positives that make naive insider screens worthless —
     * a pledge creation is not a promoter buying conviction, it is a promoter borrowing.
     */
    @Column(length = 32)
    private String mode;

    private Double quantity;

    /** Rupee value as disclosed, or quantity x price when the filing omits it. Nullable. */
    private Double value;

    /** Holding after the transaction, % of equity. Nullable — often absent in filings. */
    private Double pctOfEquityAfter;

    private LocalDate transactionDate;
    private LocalDate disclosureDate;

    /** PIT / SAST / BULK / BLOCK. */
    @Column(length = 16)
    private String source;

    /**
     * NSE {@code appId} of the PIT V2.0 filing this row came from, or null for rows ingested
     * from the pre-May-2026 JSON feed and for bulk/block deals (B-089).
     *
     * <p>Unique per filing across the whole feed, so it is the ingestion key: a filing whose
     * appId is already stored is never re-downloaded. Without it every run would re-fetch
     * every filing's XBRL to discover it already had the rows.
     */
    @Column(name = "filing_app_id", length = 32)
    private String filingAppId;

    @Column(name = "disclosure_hash", nullable = false, length = 128)
    private String disclosureHash;

    private LocalDateTime capturedAt;

    @PrePersist
    void onCreate() {
        if (capturedAt == null) capturedAt = LocalDateTime.now();
    }
}
