package com.example.trading.universe.ipo;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One mainboard public issue as NSE describes it, plus what this app has measured about it
 * since (SPEC §45).
 *
 * <p>Written only by {@link IpoTrackingService}: the daily capture upserts what the three NSE
 * feeds say, and the on-demand analysis fills the post-listing block. Everything the dashboard
 * needs on a page load is on this row, so the IPO screen is DB-only (SPEC §27.4).
 *
 * <p><b>Nullable by design.</b> Every measured figure is a wrapper type. NSE writes {@code "-"}
 * for an absent field (Gotcha 61), the issue-size sentence does not always parse, and the
 * subscription block is empty until bidding opens — each of those is "not measured", never 0.
 *
 * <p>Rows are never deleted. An issue that listed three years ago simply stops being refreshed;
 * what the app said about it at the time is part of the record (SPEC §25.1).
 */
@Entity
@Table(name = "ipo_issues",
        uniqueConstraints = @UniqueConstraint(name = "uk_ipo_issue_symbol", columnNames = "symbol"),
        indexes = {
                @Index(name = "idx_ipo_issue_listing", columnList = "listingDate"),
                @Index(name = "idx_ipo_issue_end", columnList = "issueEndDate")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IpoIssueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Qualified, {@code NSE:LCCPROJECT}, so it joins to every other table the way holdings do. */
    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(length = 160)
    private String companyName;

    /** Always {@code EQ} — SME (EMERGE) issues are dropped at capture, structurally (SPEC §30.1). */
    @Column(length = 8)
    private String series;

    private LocalDate issueStartDate;
    private LocalDate issueEndDate;
    /** Null until NSE publishes it, typically three trading days after close. */
    private LocalDate listingDate;

    private Double priceBandLow;
    private Double priceBandHigh;
    /** Final issue price, known after allotment. Null before. */
    private Double issuePrice;

    /** Total shares on offer as the list feed states them. */
    private Long issueSizeShares;

    /** NSE's own sentence describing the issue — the source of the fresh/OFS split. */
    @Column(columnDefinition = "TEXT")
    private String issueSizeText;

    /** Rupees crore raised by the company itself. Null when the sentence did not parse. */
    private Double freshIssueCr;
    /** Rupees crore of existing holders selling. Null when the sentence did not parse. */
    private Double offerForSaleCr;
    /** Fresh / (fresh + OFS), percent. Null when either half is unparsed. */
    private Double freshSharePct;

    private Integer lotSize;
    private Double faceValue;
    @Column(length = 32)
    private String issueType;

    private Boolean employeeQuota;
    private Long employeeReservedShares;
    private Double employeeDiscountRs;
    private Boolean shareholderQuota;
    private Long shareholderReservedShares;

    @Column(columnDefinition = "TEXT")
    private String leadManagers;
    @Column(length = 160)
    private String registrar;
    @Column(length = 255)
    private String rhpUrl;
    @Column(length = 255)
    private String ratiosUrl;
    @Column(length = 255)
    private String anchorUrl;

    // ---------------------------------------------------------------- subscription

    private Long qibSharesOffered;
    private Long niiSharesOffered;
    private Long retailSharesOffered;
    private Double qibTimes;
    private Double niiTimes;
    private Double bigNiiTimes;
    private Double smallNiiTimes;
    private Double retailTimes;
    private Double employeeTimes;
    private Double shareholderTimes;
    private Double totalTimes;
    /** When the figures above were read. Null when the block has never been captured. */
    private LocalDateTime subscriptionAsOf;
    /** True once the figures were read after the last bidding day — before that they are a mid-issue snapshot. */
    private Boolean subscriptionFinal;

    // ---------------------------------------------------------------- post-listing

    private Double listingDayOpen;
    private Double listingDayHigh;
    private Double listingDayClose;
    private Double latestPrice;
    private LocalDate latestPriceDate;

    private LocalDateTime lastAnalysedAt;
    private Integer candlesAvailable;
    private Boolean aboveListingHigh;
    /** Null when there is too little history to judge a base, never false. */
    private Boolean baseFormed;
    @Column(length = 32)
    private String earningsVerdict;
    @Column(length = 32)
    private String financialQualityVerdict;
    private Double promoterHoldingPct;
    private Double promoterPledgePct;
    /** Composite from a compute-to-decide screening (Gotcha 50); only computed past the hype window. */
    private Integer compositeScore;
    @Column(columnDefinition = "TEXT")
    private String analysisNote;

    // ---------------------------------------------------------------- bookkeeping

    private LocalDateTime firstSeenAt;
    /** Stamped on every capture that saw the row in a feed — the freshness key for the whole table. */
    private LocalDateTime capturedAt;
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
        if (firstSeenAt == null) firstSeenAt = updatedAt;
    }

    /** {@code LCCPROJECT} from {@code NSE:LCCPROJECT}. */
    public String tradingSymbol() {
        if (symbol == null) return null;
        int i = symbol.indexOf(':');
        return i < 0 ? symbol : symbol.substring(i + 1);
    }
}
