package com.example.trading.analyst;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One research-feed stock id resolved to an exchange symbol (SPEC §49.11).
 *
 * <p>The feed identifies a company by its own internal id ({@code IG04}), not by ticker, and the
 * display name it ships alongside is truncated to about fifteen characters ("AU Small Financ"),
 * so it cannot be matched against anything. The mapping is looked up once from the publisher's
 * own price feed and kept here for ever, which turns a per-row lookup into a one-off cost.
 *
 * <p><b>A failed resolution is stored, not discarded.</b> {@code nseSymbol} null with a
 * {@code resolvedAt} stamp means "asked, and this id has no NSE listing" — an unlisted or
 * delisted company, or the wrong half of a dual id. Leaving the row out instead would make it
 * indistinguishable from "never asked", and every capture run would re-probe the same dead ids
 * for ever. That is Gotcha 102's rule — completion has to mean something checkable — applied to a
 * lookup table.
 */
@Entity
@Table(name = "broker_symbol_map",
        indexes = {
                @Index(name = "idx_broker_symbol_scid", columnList = "scid", unique = true),
                @Index(name = "idx_broker_symbol_nse", columnList = "nseSymbol")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerSymbolEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The feed's internal stock id, e.g. {@code IG04}. */
    @Column(nullable = false, length = 32)
    private String scid;

    /** The NSE tradingsymbol, unprefixed, e.g. {@code IGL}. Null means there is no NSE listing. */
    @Column(length = 64)
    private String nseSymbol;

    /** The company name as the price feed reports it — fuller than the feed's truncated label. */
    @Column(length = 200)
    private String companyName;

    /** When the lookup was made. Present even when it found nothing. */
    private LocalDateTime resolvedAt;
}
