package com.example.trading.portfolio.tax;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A single purchase lot — the atomic unit for capital-gains computation.
 * Partial sales reduce {@link #remainingQuantity}; the lot stays {@code OPEN} until fully sold.
 * See SPEC.md §9.
 */
@Entity
@Table(name = "tax_lot", indexes = {
        @Index(name = "idx_taxlot_symbol", columnList = "symbol"),
        @Index(name = "idx_taxlot_symbol_status", columnList = "symbol,status"),
        @Index(name = "idx_taxlot_buy_date", columnList = "buyDate"),
        @Index(name = "idx_taxlot_trade_id", columnList = "tradeId"),
        @Index(name = "idx_taxlot_isin_status", columnList = "isin,status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxLotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(nullable = false)
    private int originalQuantity;

    @Column(nullable = false)
    private int remainingQuantity;

    @Column(nullable = false)
    private double buyPrice;

    @Column(nullable = false)
    private LocalDate buyDate;

    /** Brokerage + STT + GST + stamp + other charges allocated to this lot (₹). */
    @Column(nullable = false)
    private double buyCharges;

    @Column(nullable = false, length = 16)
    private String status; // OPEN | CLOSED

    @Column(length = 32)
    private String source; // MANUAL | BROKER_SYNC | IMPORT | ZERODHA_CSV | KITE_AUTO_CAPTURE

    /**
     * Broker-issued trade identifier — used to make CSV imports and live auto-capture
     * idempotent. Nullable because legacy lots and manual entries don't have one.
     */
    @Column(length = 64)
    private String tradeId;

    /**
     * ISIN (International Securities Identification Number) — exchange-independent stock
     * identity. Set from the Zerodha tradebook CSV's {@code isin} column. Used by
     * {@link TaxLotService#classifyForExit} so a holding shown by Kite under one exchange
     * (e.g. {@code BSE:WAAREEENER}) still finds lots bought under the other (e.g.
     * {@code NSE:WAAREEENER}). Nullable for legacy lots and live-captured lots (Kite
     * {@code /trades} doesn't return ISIN).
     */
    @Column(length = 32)
    private String isin;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "OPEN";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
