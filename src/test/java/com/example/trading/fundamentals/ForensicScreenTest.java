package com.example.trading.fundamentals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the forensic red-flag layer (SPEC §32.4, F5).
 *
 * <p>This layer is the fraud shield that widening the universe (SPEC §30) makes necessary,
 * so the properties defended are mostly about honesty: no flags must never be reported as
 * "clean" when nothing could be checked, an INFO flag must never move a score, and the
 * auditor flag must escalate rather than merely deduct.
 */
class ForensicScreenTest {

    private static AnnualFundamentalsEntity yr(int year, Double sales, Double netProfit,
                                               Double cfo, Double receivables, Double shares) {
        return AnnualFundamentalsEntity.builder()
                .symbol("NSE:ACME").fiscalYear(year).source("IMPORT")
                .sales(sales).netProfit(netProfit).operatingCashFlow(cfo)
                .receivables(receivables).shareCount(shares)
                .build();
    }

    /** Four clean years: no dilution, cash-backed profit, receivables tracking sales. */
    private static List<AnnualFundamentalsEntity> cleanHistory() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        h.add(yr(2022, 1000.0, 100.0, 110.0, 200.0, 50.0));
        h.add(yr(2023, 1100.0, 110.0, 115.0, 220.0, 50.0));
        h.add(yr(2024, 1200.0, 120.0, 125.0, 240.0, 50.0));
        h.add(yr(2025, 1300.0, 130.0, 135.0, 260.0, 50.0));
        return h;
    }

    @Test
    @DisplayName("A clean history raises no flags")
    void cleanHistoryIsClean() {
        var r = ForensicScreenService.evaluate("NSE:ACME", cleanHistory(), List.of());

        assertThat(r.isClean()).isTrue();
        assertThat(r.getTotalPenalty()).isZero();
        assertThat(r.isForcesHighRisk()).isFalse();
        assertThat(r.toStorageString()).isNull();
    }

    @Test
    @DisplayName("No history means nothing was checked — which is not the same as clean")
    void emptyHistoryReportsWhatItCouldNotCheck() {
        var r = ForensicScreenService.evaluate("NSE:ACME", List.of(), List.of());

        assertThat(r.getFlags()).isEmpty();
        // The whole point: a caller must be able to tell "checked, found nothing" from
        // "could not check". Without notMeasured these two are indistinguishable.
        assertThat(r.getNotMeasured()).isNotEmpty();
        assertThat(r.getYearsAvailable()).isZero();
    }

    @Test
    @DisplayName("Share count compounding above 5% a year is flagged as dilution")
    void detectsSerialDilution() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>(cleanHistory());
        // 50 -> 65 over three years is ~9.1%/yr.
        h.get(0).setShareCount(50.0);
        h.get(1).setShareCount(55.0);
        h.get(2).setShareCount(60.0);
        h.get(3).setShareCount(65.0);
        var r = ForensicScreenService.evaluate("NSE:ACME", h, List.of());

        assertThat(r.getFlags()).anySatisfy(f -> assertThat(f.getCode()).isEqualTo("DILUTION"));
        assertThat(r.getTotalPenalty()).isGreaterThan(0);
        // The message has to say what was measured, not just name the flag.
        var flag = r.getFlags().stream().filter(f -> "DILUTION".equals(f.getCode())).findFirst().orElseThrow();
        assertThat(flag.getMessage()).contains("%");
        assertThat(flag.getMessage().toLowerCase()).contains("shares");
    }

    @Test
    @DisplayName("B-048: a flat share count is not explained away as a bonus issue")
    void flatShareCountGetsNoBonusExplanation() {
        // The bonus/split test used to run before the growth threshold, so a company whose
        // share count never moved was reported as "this looks like a bonus issue rather
        // than dilution" — an explanation offered for something that did not happen, in the
        // beginner-facing wording the user reads.
        var r = ForensicScreenService.evaluate("NSE:ACME", cleanHistory(), List.of());

        assertThat(r.getNotMeasured()).noneMatch(m -> m.toLowerCase().contains("bonus issue"));
        assertThat(r.getFlags()).noneSatisfy(f -> assertThat(f.getCode()).isEqualTo("DILUTION"));
    }

    @Test
    @DisplayName("B-046: share counts on different unit bases are refused, not read as a share action")
    void mixedUnitShareCountsAreNotMeasured() {
        // The CSV import may carry an absolute count and the XBRL pipeline writes crore. A
        // series mixing the two steps by 10 million, which is not a corporate event at all;
        // reporting it as either dilution or a buyback would invent one out of a units bug.
        List<AnnualFundamentalsEntity> h = new ArrayList<>(cleanHistory());
        h.get(0).setShareCount(50.0);
        h.get(1).setShareCount(50.0);
        h.get(2).setShareCount(50.0);
        h.get(3).setShareCount(500_000_000.0);
        var r = ForensicScreenService.evaluate("NSE:ACME", h, List.of());

        assertThat(r.getFlags()).noneSatisfy(f -> assertThat(f.getCode()).isEqualTo("DILUTION"));
        assertThat(r.getNotMeasured()).anyMatch(m -> m.toLowerCase().contains("common basis"));
    }

    @Test
    @DisplayName("A bonus issue is not dilution — no new money came in")
    void bonusIssueIsNotDilution() {
        // Reliance did 1:1 in 2024. The share count doubles, every holder's proportion is
        // unchanged, and the price halves to match. Flagging this would fire the dilution
        // warning on some of the best companies in the market, and a forensic section that
        // cries wolf gets ignored — taking the real flags down with it.
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        //          year  sales  netProfit  cfo   receivables shares
        h.add(yr(2022, 1000.0, 100.0, 110.0, 200.0, 50.0));
        h.add(yr(2023, 1100.0, 110.0, 115.0, 220.0, 50.0));
        h.add(yr(2024, 1200.0, 120.0, 125.0, 240.0, 50.0));
        h.add(yr(2025, 1300.0, 130.0, 135.0, 260.0, 100.0));   // 1:1 bonus
        // Equity grew only by retained profit — no external capital arrived.
        h.get(0).setEquity(1000.0);
        h.get(1).setEquity(1110.0);
        h.get(2).setEquity(1230.0);
        h.get(3).setEquity(1360.0);   // +360 over the period vs 360 of profit

        var r = ForensicScreenService.evaluate("NSE:ACME", h, List.of());

        assertThat(r.getFlags()).noneSatisfy(f -> assertThat(f.getCode()).isEqualTo("DILUTION"));
        // Reported, not silently dropped: an INFO flag (scores zero, stops nothing) says the
        // action was found and adjusted for, so a reader can tell an adjusted series from a
        // clean one. It is NOT filed under notMeasured — the check ran and found no dilution.
        assertThat(r.getFlags()).anySatisfy(f -> {
            assertThat(f.getCode()).isEqualTo("CORPORATE_ACTION");
            assertThat(f.getSeverity()).isEqualTo("INFO");
            assertThat(f.getScorePenalty()).isZero();
            assertThat(f.getMessage()).contains("not");
        });
    }

    /**
     * B-066, the live case. BEL's share count went 243.66 -> 730.98 (exactly 3x, a 1:2 bonus)
     * while one of its four equity years was null. {@code series()} returns nothing when any year
     * is missing, so the equity discriminator answered "cannot tell" — and the caller read that as
     * "not a bonus" and fired its most serious flag, sending the stock to AVOID on every screen.
     * The ratio test needs no equity at all, which is the whole point.
     */
    @Test
    @DisplayName("A bonus issue is still recognised when the equity data needed to prove it is missing")
    void bonusRecognisedWithoutEquityData() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        h.add(yr(2022, 1000.0, 100.0, 110.0, 200.0, 243.66));
        h.add(yr(2023, 1100.0, 110.0, 115.0, 220.0, 730.98));   // 1:2 bonus, exactly 3x
        h.add(yr(2024, 1200.0, 120.0, 125.0, 240.0, 730.98));
        h.add(yr(2025, 1300.0, 130.0, 135.0, 260.0, 730.98));
        // Equity absent for the first year, exactly as NSE's archive leaves it.
        h.get(1).setEquity(13879.38);
        h.get(2).setEquity(16344.39);
        h.get(3).setEquity(24007.14);

        var r = ForensicScreenService.evaluate("NSE:BEL", h, List.of());

        assertThat(r.getFlags()).noneSatisfy(f -> assertThat(f.getCode()).isEqualTo("DILUTION"));
    }

    /** A bonus on top of real issuance must still be flagged — on the issuance alone. */
    @Test
    @DisplayName("A bonus does not launder genuine dilution happening alongside it")
    void bonusDoesNotHideRealDilution() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        h.add(yr(2022, 1000.0, 100.0, 110.0, 200.0, 100.0));
        h.add(yr(2023, 1100.0, 110.0, 115.0, 220.0, 200.0));    // 1:1 bonus
        h.add(yr(2024, 1200.0, 120.0, 125.0, 240.0, 230.0));    // then real issuance
        h.add(yr(2025, 1300.0, 130.0, 135.0, 260.0, 260.0));
        // Equity jumped well beyond retained profit — cash came in.
        h.get(0).setEquity(1000.0);
        h.get(1).setEquity(1500.0);
        h.get(2).setEquity(2100.0);
        h.get(3).setEquity(2800.0);

        var r = ForensicScreenService.evaluate("NSE:ACME", h, List.of());

        assertThat(r.getFlags()).anySatisfy(f -> assertThat(f.getCode()).isEqualTo("DILUTION"));
    }

    @Test
    @DisplayName("A buyback is never rewritten as a corporate action")
    void buybackIsNotAdjusted() {
        var a = ForensicScreenService.adjustForCorporateActions(List.of(100.0, 90.0, 80.0, 70.0));
        assertThat(a.adjusted()).isFalse();
        assertThat(a.counts()).containsExactly(100.0, 90.0, 80.0, 70.0);
    }

    @Test
    @DisplayName("An arbitrary raise is not mistaken for a split: only exact simple ratios adjust")
    void onlyExactRatiosAdjust() {
        // 1.37x is money raised; 2.0x is a corporate action.
        assertThat(ForensicScreenService.adjustForCorporateActions(List.of(100.0, 137.0)).adjusted()).isFalse();
        assertThat(ForensicScreenService.adjustForCorporateActions(List.of(100.0, 200.0)).adjusted()).isTrue();
        assertThat(ForensicScreenService.adjustForCorporateActions(List.of(100.0, 150.0)).adjusted()).isTrue();
    }

    /**
     * The tolerance has to be tight enough that the grid of simple ratios cannot swallow a real
     * capital raise. These are BANKINDIA's actual stored counts: a government infusion and a QIP,
     * whose steps land 0.18% from 5/4 and 0.15% from 10/9. A 0.5% tolerance cleared both as bonus
     * issues and reported the bank as never having diluted anyone (B-066).
     */
    @Test
    @DisplayName("A capital raise landing near a simple ratio is still a raise, not a bonus")
    void nearMissRatiosAreNotCorporateActions() {
        var a = ForensicScreenService.adjustForCorporateActions(
                List.of(327.766, 410.431, 455.341, 455.341));
        assertThat(a.adjusted()).isFalse();
    }

    /** NMDC's real 1:2 bonus: 3x to within rounding of the stored crore figure. */
    @Test
    @DisplayName("Rounding in the stored share count does not hide a genuine bonus")
    void roundingDoesNotHideABonus() {
        var a = ForensicScreenService.adjustForCorporateActions(
                List.of(293.07, 293.07, 293.07, 879.18));   // 3x to 0.003%
        assertThat(a.adjusted()).isTrue();
        assertThat(a.counts().get(3)).isCloseTo(293.07, within(0.5));
    }

    @Test
    @DisplayName("A real capital raise IS dilution — equity jumped beyond retained profit")
    void capitalRaiseIsStillFlagged() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        h.add(yr(2022, 1000.0, 100.0, 110.0, 200.0, 50.0));
        h.add(yr(2023, 1100.0, 110.0, 115.0, 220.0, 58.0));
        h.add(yr(2024, 1200.0, 120.0, 125.0, 240.0, 66.0));
        h.add(yr(2025, 1300.0, 130.0, 135.0, 260.0, 75.0));   // ~14.5%/yr
        // Equity grew far beyond the 360 of profit — new money was sold in.
        h.get(0).setEquity(1000.0);
        h.get(1).setEquity(1500.0);
        h.get(2).setEquity(2000.0);
        h.get(3).setEquity(2600.0);

        var r = ForensicScreenService.evaluate("NSE:ACME", h, List.of());

        assertThat(r.getFlags()).anySatisfy(f -> assertThat(f.getCode()).isEqualTo("DILUTION"));
    }

    @Test
    @DisplayName("Without equity data the discriminator abstains and the flag still runs")
    void missingEquityDoesNotSuppressTheFlag() {
        // Erring toward silence is right for a false accusation, but not for a missing
        // input: if we cannot tell bonus from raise, the flag should still be raised and
        // the reader can check which it was.
        List<AnnualFundamentalsEntity> h = new ArrayList<>(cleanHistory());
        h.get(0).setShareCount(50.0);
        h.get(1).setShareCount(55.0);
        h.get(2).setShareCount(60.0);
        h.get(3).setShareCount(65.0);
        assertThat(ForensicScreenService.isBonusOrSplit(h)).isFalse();

        var r = ForensicScreenService.evaluate("NSE:ACME", h, List.of());
        assertThat(r.getFlags()).anySatisfy(f -> assertThat(f.getCode()).isEqualTo("DILUTION"));
    }

    @Test
    @DisplayName("Receivables outrunning sales is flagged as possible paper revenue")
    void detectsReceivablesDivergence() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>(cleanHistory());
        // Sales +~10%/yr, receivables +~50%/yr.
        h.get(1).setReceivables(300.0);
        h.get(2).setReceivables(450.0);
        h.get(3).setReceivables(675.0);
        var r = ForensicScreenService.evaluate("NSE:ACME", h, List.of());

        assertThat(r.getFlags()).anySatisfy(f -> assertThat(f.getCode()).isEqualTo("RECEIVABLES"));
    }

    @Test
    @DisplayName("Receivables are not flagged when sales are flat, where the ratio is meaningless")
    void receivablesNotFlaggedOnFlatSales() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        h.add(yr(2023, 1000.0, 100.0, 110.0, 200.0, 50.0));
        h.add(yr(2024, 1000.0, 100.0, 110.0, 260.0, 50.0));
        h.add(yr(2025, 1000.0, 100.0, 110.0, 320.0, 50.0));
        var r = ForensicScreenService.evaluate("NSE:ACME", h, List.of());

        assertThat(r.getFlags()).noneSatisfy(f -> assertThat(f.getCode()).isEqualTo("RECEIVABLES"));
    }

    @Test
    @DisplayName("Three years of profit that never became cash is flagged")
    void detectsWeakMultiYearCashConversion() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>(cleanHistory());
        h.get(1).setOperatingCashFlow(40.0);
        h.get(2).setOperatingCashFlow(45.0);
        h.get(3).setOperatingCashFlow(50.0);   // 135 CFO on 360 profit = 0.375x
        var r = ForensicScreenService.evaluate("NSE:ACME", h, List.of());

        assertThat(r.getFlags()).anySatisfy(f -> assertThat(f.getCode()).isEqualTo("CASH_CONVERSION"));
    }

    @Test
    @DisplayName("Cumulative losses make cash conversion unmeasurable, not a flag")
    void lossMakingCashConversionIsUnmeasured() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>(cleanHistory());
        h.get(1).setNetProfit(-100.0);
        h.get(2).setNetProfit(-100.0);
        h.get(3).setNetProfit(-100.0);
        var r = ForensicScreenService.evaluate("NSE:ACME", h, List.of());

        assertThat(r.getFlags()).noneSatisfy(f -> assertThat(f.getCode()).isEqualTo("CASH_CONVERSION"));
        assertThat(r.getNotMeasured()).anySatisfy(m -> assertThat(m).contains("cash conversion"));
    }

    // ---- Auditor: the escalating flag

    @Test
    @DisplayName("An auditor resignation forces HIGH_RISK, not just a deduction")
    void auditorFlagEscalates() {
        var r = ForensicScreenService.evaluate("NSE:ACME", cleanHistory(),
                List.of("Intimation regarding resignation of statutory auditor M/s XYZ & Co"));

        assertThat(r.isForcesHighRisk()).isTrue();
        var flag = r.getFlags().stream().filter(f -> "AUDITOR".equals(f.getCode())).findFirst().orElseThrow();
        assertThat(flag.getSeverity()).isEqualTo("HIGH");
        assertThat(flag.getScorePenalty()).isGreaterThan(0);
        // The reason this escalates rather than deducts must be stated to the reader.
        assertThat(flag.getMessage().toLowerCase()).contains("independent check");
    }

    @Test
    @DisplayName("A qualified opinion counts as an auditor problem")
    void qualifiedOpinionCounts() {
        var r = ForensicScreenService.evaluate("NSE:ACME", cleanHistory(),
                List.of("Audit report with qualified opinion for the year ended March 2025"));

        assertThat(r.isForcesHighRisk()).isTrue();
    }

    @Test
    @DisplayName("Ordinary announcements do not trip the auditor flag")
    void ordinaryAnnouncementsAreIgnored() {
        var r = ForensicScreenService.evaluate("NSE:ACME", cleanHistory(),
                List.of("Board meeting intimation for approval of quarterly results",
                        "Appointment of statutory auditor for FY2026"));

        assertThat(r.isForcesHighRisk()).isFalse();
        assertThat(r.getFlags()).noneSatisfy(f -> assertThat(f.getCode()).isEqualTo("AUDITOR"));
    }

    @Test
    @DisplayName("B-037: a clean-opinion declaration must NOT fire the auditor flag")
    void unqualifiedDeclarationIsNotAnAuditorProblem() {
        // SEBI LODR Reg 33(3)(d) REQUIRES this filing from every listed company. Its text
        // contains "unqualified opinion", which contains the substring "qualified opinion" —
        // so a plain contains() fired the most serious flag in the system, worth -8 and a
        // forced HIGH_RISK cap, on the routine announcement that exists to say all is well.
        for (String subject : List.of(
                "Declaration of unqualified opinion on audited financial results",
                "Declaration of Un-Qualified Opinion under Regulation 33(3)(d)",
                "Declaration in respect of audit report with unmodified opinion",
                "Declaration of Un-Modified Opinion for the year ended 31 March 2026",
                "Audit report submitted without qualification")) {
            var r = ForensicScreenService.evaluate("NSE:ACME", cleanHistory(), List.of(subject));
            assertThat(r.isForcesHighRisk())
                    .withFailMessage("clean-opinion declaration wrongly flagged: %s", subject)
                    .isFalse();
            assertThat(r.getFlags()).noneSatisfy(f -> assertThat(f.getCode()).isEqualTo("AUDITOR"));
        }
    }

    @Test
    @DisplayName("B-037: a genuine qualified opinion still fires")
    void genuineQualificationStillFires() {
        // The fix must not blunt the real signal it guards.
        for (String subject : List.of(
                "Audit report with qualified opinion for the year ended March 2026",
                "Statutory auditor has issued an adverse opinion",
                "Disclaimer of opinion issued by the statutory auditor")) {
            var r = ForensicScreenService.evaluate("NSE:ACME", cleanHistory(), List.of(subject));
            assertThat(r.isForcesHighRisk())
                    .withFailMessage("genuine auditor problem missed: %s", subject)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("B-037: hyphen forms normalise to the same match")
    void hyphenFormsNormalise() {
        assertThat(ForensicScreenService.isCleanOpinionDeclaration(
                ForensicScreenService.normaliseAnnouncement("Declaration of Un-Modified Opinion"))).isTrue();
        assertThat(ForensicScreenService.isCleanOpinionDeclaration(
                ForensicScreenService.normaliseAnnouncement("qualified opinion"))).isFalse();
    }

    @Test
    @DisplayName("Related-party mentions are surfaced but never scored")
    void relatedPartyIsInformationalOnly() {
        var r = ForensicScreenService.evaluate("NSE:ACME", cleanHistory(),
                List.of("Disclosure of related party transactions for the half year"));

        var flag = r.getFlags().stream().filter(f -> "RELATED_PARTY".equals(f.getCode())).findFirst().orElseThrow();
        assertThat(flag.getSeverity()).isEqualTo("INFO");
        // Scoring these before measuring them would add noise to the composite for nothing.
        assertThat(flag.getScorePenalty()).isZero();
        assertThat(r.getTotalPenalty()).isZero();
        assertThat(r.isForcesHighRisk()).isFalse();
    }

    @Test
    @DisplayName("The persisted form lists every flag that fired")
    void storageStringCarriesAllFlags() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>(cleanHistory());
        h.get(0).setShareCount(50.0);
        h.get(1).setShareCount(55.0);
        h.get(2).setShareCount(60.0);
        h.get(3).setShareCount(65.0);
        var r = ForensicScreenService.evaluate("NSE:ACME", h,
                List.of("resignation of statutory auditor"));

        assertThat(r.toStorageString()).contains("DILUTION").contains("AUDITOR");
    }

    // ------------------------------------------------- dilution severity (B-092)

    @Test
    @DisplayName("Dilution is only a red flag when net worth vouches that money was raised")
    void dilutionSeverityFollowsTheEvidence() {
        // The share count rises messily — genuine serial dilution — but equity is not on file,
        // so nothing can tell a raise from a bonus, split or merger.
        List<AnnualFundamentalsEntity> h = new ArrayList<>(cleanHistory());
        h.get(0).setShareCount(50.0);
        h.get(1).setShareCount(55.0);
        h.get(2).setShareCount(60.0);
        h.get(3).setShareCount(65.0);

        var flag = ForensicScreenService.evaluate("NSE:ACME", h, List.of()).getFlags().stream()
                .filter(f -> "DILUTION".equals(f.getCode())).findFirst().orElseThrow();

        // The finding is still reported — the reader is not left thinking the count was flat.
        // But HIGH disqualifies a stock outright (Gotcha 77) and caps the composite at 54, and
        // that consequence must not rest on an absence of evidence. This is what fired on
        // HDFCBANK's split, merger and bonus and sent it to a NO verdict on the portfolio page.
        assertThat(flag.getSeverity()).isEqualTo("MEDIUM");
        assertThat(flag.getMessage()).contains("caution rather than a red flag");
    }

    @Test
    @DisplayName("With net worth on file confirming a raise, dilution is a red flag")
    void verifiedDilutionStaysHigh() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>(cleanHistory());
        double[] shares = {50.0, 55.0, 60.0, 65.0};
        // Equity grows far faster than retained profit: cash came in from outside.
        double[] equity = {1000.0, 1400.0, 1900.0, 2500.0};
        for (int i = 0; i < 4; i++) {
            h.get(i).setShareCount(shares[i]);
            h.get(i).setEquity(equity[i]);
        }

        var flag = ForensicScreenService.evaluate("NSE:ACME", h, List.of()).getFlags().stream()
                .filter(f -> "DILUTION".equals(f.getCode())).findFirst().orElseThrow();

        assertThat(flag.getSeverity()).isEqualTo("HIGH");
    }
}
