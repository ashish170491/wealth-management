package com.example.trading.macro;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One macro event, as extracted from the headlines (SPEC §48.2).
 *
 * <p><b>An event is not a headline.</b> The headline table holds one row per story; this holds one
 * row per thing that happened. Four outlets reporting a rate cut and two more following it up the
 * next day are one row here, carrying six headline references. That distinction is the reason a
 * loud news cycle cannot dominate a portfolio reading simply by being repeated.
 *
 * <p><b>Rows are never deleted by the app, and a dismissal is not a deletion.</b> Marking an event
 * as noise stops it counting against any stock and leaves it on the record, because the record is
 * what the feature's own accuracy is measured against later - and a ledger that quietly drops what
 * turned out to be wrong cannot be judged (SPEC §25.1). Only the retention job removes anything,
 * and it keeps two years, longer than the 365-day outcome horizon.
 *
 * <p>{@code exposureMapVersion} stamps the rules in force when the event was written, so a reading
 * taken last month can be traced to the map that produced it rather than re-derived from today's.
 */
@Entity
@Table(name = "macro_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_macro_event_dedup", columnNames = "dedupKey"),
        indexes = {
                @Index(name = "idx_macro_event_occurred", columnList = "occurredAt"),
                @Index(name = "idx_macro_event_factor", columnList = "factor"),
                @Index(name = "idx_macro_event_extracted", columnList = "extractedAt")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MacroEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** A {@link MacroFactor} name. Stored as text so an unknown value can be read back and skipped. */
    @Column(nullable = false, length = 48)
    private String factor;

    /** {@link MacroDirection}: did the factor rise or fall. */
    @Column(nullable = false, length = 8)
    private String direction;

    /** {@link MacroMagnitude}: how big, as the news described it. Never a percentage. */
    @Column(length = 12)
    private String magnitude;

    /** {@link MacroEventKind}: was it on the published calendar. */
    @Column(length = 12)
    private String kind;

    /** India, United States, China, Global. */
    @Column(length = 64)
    private String geography;

    /** The day the event happened, not the day it was read. */
    @Column(nullable = false)
    private LocalDate occurredAt;

    /** Comma-separated {@code market_impact_news} ids, so every claim can be traced to its sources. */
    @Column(columnDefinition = "TEXT")
    private String headlineIds;

    /** Comma-separated source URLs, kept alongside the ids so a pruned headline still leaves a link. */
    @Column(columnDefinition = "TEXT")
    private String sourceUrls;

    /** One line describing the event, usually the clearest headline that carried it. */
    @Column(columnDefinition = "TEXT")
    private String summary;

    private LocalDateTime extractedAt;

    /**
     * Which reader produced this: {@code KEYWORD}, a model id such as {@code openai:gpt-4o-mini},
     * or {@code KEYWORD(fallback:...)} when a model was configured and could not be used. Always
     * shown to the investor - a reading is only as good as the thing that read it.
     */
    @Column(length = 64)
    private String extractor;

    /**
     * The model's own confidence, 0 to 1. <b>Null from the keyword reader, always</b>: a keyword
     * match has no calibrated probability behind it, and a plausible figure here would be
     * indistinguishable from a measured one (SPEC §21 rule 7).
     */
    private Double confidence;

    /** {@link MacroEventDedup#key}: factor, direction and day. */
    @Column(nullable = false, length = 96)
    private String dedupKey;

    @Builder.Default
    private boolean dismissed = false;

    private LocalDateTime dismissedAt;

    /** The exposure-map version in force when this row was written. */
    @Column(length = 32)
    private String exposureMapVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = updatedAt;
        }
    }

    // ------------------------------------------------------------------ typed views

    public MacroFactor factorOrNull() {
        return MacroFactor.parse(factor).orElse(null);
    }

    public MacroDirection directionOrNull() {
        return MacroDirection.parse(direction).orElse(null);
    }

    public MacroMagnitude magnitudeOrNull() {
        return MacroMagnitude.parse(magnitude).orElse(null);
    }

    public MacroEventKind kindOrNull() {
        return MacroEventKind.parse(kind).orElse(null);
    }

    public List<Long> headlineIdList() {
        List<Long> out = new ArrayList<>();
        if (headlineIds == null || headlineIds.isBlank()) {
            return out;
        }
        for (String part : headlineIds.split(",")) {
            try {
                out.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // A malformed id is a lost reference, not a reason to lose the event.
            }
        }
        return out;
    }

    public List<String> sourceUrlList() {
        if (sourceUrls == null || sourceUrls.isBlank()) {
            return List.of();
        }
        return Arrays.stream(sourceUrls.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** The shape {@link MacroExposureRead} consumes. Null factor or direction means an unreadable row. */
    public MacroExposureRead.Event toReadEvent() {
        return new MacroExposureRead.Event(id == null ? 0L : id, factorOrNull(), directionOrNull(),
                magnitudeOrNull(), occurredAt, summary, dismissed);
    }
}
