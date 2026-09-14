package com.example.trading.intelligence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One headline, stored for deduplication and as the evidence behind a macro event (SPEC 48.2).
 *
 * <p><b>Half of this row is history.</b> Until 2026-09-10 every headline was classified into an
 * impact level and category and could trigger an email within fifteen minutes; that machinery is
 * gone (B-101). {@code impactLevel}, {@code impactCategory}, {@code affectedSectors},
 * {@code affectedSymbols}, {@code sentiment} and {@code alerted} are no longer written by anything.
 * The columns are kept rather than dropped because they hold real rows, and because deleting the
 * producer while keeping the record is the rule this codebase already follows (SPEC 25.1) - but
 * nothing should read them, and {@code affectedSymbols} in particular was matched from a
 * hard-coded list of 46 Nifty names that was never joined to the holdings table.
 *
 * <p>What is live: the headline itself, its source and link, when it was published, the normalised
 * title hash that deduplicates it, and {@code macroExtractedAt} - the marker that says the event
 * extractor has already read this row.
 */
@Entity
@Table(name = "market_impact_news", indexes = {
    @Index(name = "idx_impact_title_hash", columnList = "titleHash", unique = true),
    @Index(name = "idx_impact_level", columnList = "impactLevel"),
    @Index(name = "idx_impact_created", columnList = "createdAt"),
    @Index(name = "idx_impact_alerted", columnList = "impactLevel, alerted")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketImpactNewsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 2000)
    private String description;

    @Column
    private String source;              // Economic Times, Moneycontrol, LiveMint, etc.

    @Column(length = 1000)
    private String url;

    private LocalDateTime publishedAt;

    /** @deprecated no longer written; see the class note. Historical rows only. */
    @Deprecated
    @Column(length = 20)
    private String impactLevel;

    /** @deprecated no longer written; see the class note. Historical rows only. */
    @Deprecated
    @Column(length = 50)
    private String impactCategory;

    /** @deprecated no longer written; see the class note. Historical rows only. */
    @Deprecated
    @Column(length = 500)
    private String affectedSectors;

    /** @deprecated never joined to the holdings table; see the class note. Historical rows only. */
    @Deprecated
    @Column(length = 500)
    private String affectedSymbols;

    /** @deprecated no longer written; see the class note. Historical rows only. */
    @Deprecated
    @Column(length = 20)
    private String sentiment;

    @Column(nullable = false, length = 64, unique = true)
    private String titleHash;           // SHA-256 of title for deduplication

    /** @deprecated the alert emails are gone (B-101). Historical rows only. */
    @Deprecated
    @Builder.Default
    private boolean alerted = false;

    /**
     * When the macro event extractor last read this headline. Null means it has not been offered
     * yet, which is what an ingest selects on - so a headline is never re-read and re-charged for.
     */
    private LocalDateTime macroExtractedAt;

    /**
     * When the analyst target ledger read this headline (SPEC 49.5).
     *
     * <p>A second marker rather than a shared one, because the two readers ask different
     * questions of the same row and are enabled independently: a headline the macro extractor
     * has never been asked to read must still be minable for a price target, and vice versa.
     * Sharing one flag would let whichever reader ran first silently consume the other's backlog.
     */
    private LocalDateTime analystExtractedAt;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
