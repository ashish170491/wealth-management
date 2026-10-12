package com.example.trading.funds;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One AMFI pull (SPEC 52.6).
 *
 * <p><b>This table, not a NAV date, is what the freshness strip reads.</b> They are two different
 * facts and merging them is B-119 in both directions: stamp the strip with the NAV date and a
 * Monday pull reads "3 days ago" because Friday was the last trading session, so the reader
 * concludes the job is broken; stamp the figures with the pull time and Thursday's NAV is labelled
 * as today's.
 *
 * <p>The counts are stored rather than only logged so a layout change at AMFI is visible on the
 * data-health screen instead of arriving as an empty table nobody questions (B-054).
 */
@Entity
@Table(name = "mf_sync_run", indexes = @Index(name = "idx_mf_sync_pulled", columnList = "lastPulledAt"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MutualFundSyncRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** When the job ran. A timestamp, so "hours ago" is legitimate for this value only. */
    @Column(nullable = false)
    private LocalDateTime lastPulledAt;

    /** The headline NAV date in the file - the date of the values, not of the run. */
    private LocalDate navDateInFile;

    private int rowsParsed;
    private int schemesWritten;
    private int navRowsWritten;

    /** Lines that looked like data and could not be read. Non-zero means AMFI changed the layout. */
    private int unparsedLines;

    /** Category headers with no rule, by name. The app's gap, reported rather than swallowed. */
    @Column(columnDefinition = "TEXT")
    private String unclassifiedHeaders;

    /** Null when the run succeeded. A failed run is recorded, never silently skipped. */
    @Column(columnDefinition = "TEXT")
    private String failureReason;
}
