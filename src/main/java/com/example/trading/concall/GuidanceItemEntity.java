package com.example.trading.concall;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One promise management made on an earnings call (SPEC.md §34.2).
 *
 * <p><b>What this table is for.</b> Anyone can read a transcript and be impressed. The
 * question that actually separates managements is whether what they said last year turned
 * out to be true — and nobody keeps that score, because it requires writing the claim down
 * at the time and coming back four quarters later. That is all this table does: record the
 * claim when it is made, fill in the outcome when the numbers arrive, and let the ratio
 * accumulate.
 *
 * <p><b>Why the outcome is nullable and stays that way for a year.</b> A guidance item is
 * unresolved until the period it covers has actually reported. An unresolved item must
 * never be counted as either met or missed — a credibility ratio computed over promises
 * that have not come due yet measures nothing but optimism.
 *
 * <p><b>Not a scoring input yet.</b> SPEC §34.3: the delivery ratio may only become a
 * scoring input after at least four resolved quarters exist for a company, and even then
 * only the measured ratio — never the AI's opinion of the tone.
 */
@Entity
@Table(name = "guidance_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_guidance_dedup", columnNames = {"symbol", "quarter", "metric"}),
        indexes = {
                @Index(name = "idx_guidance_symbol", columnList = "symbol"),
                @Index(name = "idx_guidance_status", columnList = "status")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuidanceItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String symbol;

    /** The quarter the statement was made in, e.g. {@code Q1FY26}. */
    @Column(nullable = false, length = 16)
    private String quarter;

    /** What was guided on: revenue growth, margin, capex, capacity, debt reduction. */
    @Column(nullable = false, length = 64)
    private String metric;

    /** The claim as management stated it, verbatim enough to be checkable. */
    @Column(length = 512)
    private String guidedValue;

    /** Numeric form of the guidance where one could be extracted; null when qualitative. */
    private Double guidedNumeric;

    /** Filled in once the period reports. Null means not yet due — never "missed". */
    @Column(length = 512)
    private String actualValue;

    private Double actualNumeric;

    /** PENDING / MET / MISSED / UNVERIFIABLE */
    @Column(nullable = false, length = 16)
    private String status;

    /** When the guided period is expected to have reported, so the scheduler knows when to look. */
    private LocalDate dueDate;

    /** Announcement date of the transcript this came from — the provenance of the claim. */
    private LocalDate sourceDate;

    @Column(length = 512)
    private String sourceUrl;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (status == null) status = "PENDING";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isResolved() {
        return "MET".equals(status) || "MISSED".equals(status);
    }
}
