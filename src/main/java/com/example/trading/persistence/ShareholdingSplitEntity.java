package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Foreign and domestic institutional holding for one company and one quarter, as filed in the
 * shareholding-pattern XBRL (B-012).
 *
 * <p>The JSON listing carries only promoter and public totals, so FII and DII were a 50/50 split
 * of "public minus 5%" - identical on 297 of 297 stocks, which meant the Institutional Interest
 * dimension scored the change in public holding. The real figures are in the XBRL the app already
 * downloads for the pledge figure. A filed quarter never changes, so each one is parsed once and
 * kept here; only new quarters cost a request.
 */
@Entity
@Table(name = "shareholding_split",
        uniqueConstraints = @UniqueConstraint(name = "uk_shareholding_split",
                columnNames = {"symbol", "quarter_date"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShareholdingSplitEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(name = "quarter_date", nullable = false)
    private LocalDate quarterDate;

    /** Percent of total shares held by foreign institutions; null when the filing did not tag it. */
    private Double fiiPercent;

    /** Percent of total shares held by domestic institutions; null when the filing did not tag it. */
    private Double diiPercent;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
