package com.example.trading.portfolio.tax;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A realized sale matched against a specific {@link TaxLotEntity}. One sale may produce
 * multiple rows if matched across several lots (FIFO/LIFO/HIFO). See SPEC.md §9.
 */
@Entity
@Table(name = "tax_lot_sale", indexes = {
        @Index(name = "idx_taxsale_symbol", columnList = "symbol"),
        @Index(name = "idx_taxsale_sale_date", columnList = "sellDate"),
        @Index(name = "idx_taxsale_lot", columnList = "lotId"),
        @Index(name = "idx_taxsale_trade_id", columnList = "tradeId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxLotSaleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long lotId;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private double sellPrice;

    @Column(nullable = false)
    private LocalDate sellDate;

    @Column(nullable = false)
    private double sellCharges;

    /** Days held for this matched quantity (sellDate − lot.buyDate). */
    @Column(nullable = false)
    private int daysHeld;

    /** Realized gain (sellPrice − buyPrice) × quantity − allocated charges. */
    @Column(nullable = false)
    private double realizedGain;

    /** LONG_TERM | SHORT_TERM — based on daysHeld vs ltcgCutoffDays at time of sale. */
    @Column(nullable = false, length = 16)
    private String gainType;

    /**
     * Broker-issued trade identifier from the originating sell trade — present when
     * the sale was created by the Zerodha CSV importer or the live auto-capture
     * scheduler. Multiple rows can share the same tradeId (one sell can split across
     * several lots), so this is NOT unique. Nullable for manually-recorded sales.
     */
    @Column(length = 64)
    private String tradeId;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
