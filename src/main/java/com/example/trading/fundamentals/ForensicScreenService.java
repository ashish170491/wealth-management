package com.example.trading.fundamentals;

import com.example.trading.ai.NseDataService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Accounting and governance red flags (SPEC.md §32.4).
 *
 * <p><b>Why this ships alongside universe expansion.</b> Widening the screening universe
 * from 361 curated names to the full mainboard (SPEC §30) means the system now looks at
 * small caps nobody vets. The curated list was itself a fraud filter — an accidental one,
 * but a real one. Removing it without adding a deliberate replacement would be a net
 * downgrade in safety, whatever it did for discovery.
 *
 * <p><b>Each flag is named, never folded into a number.</b> A composite that quietly drops
 * five points tells the reader nothing; "share count has grown 9% a year for three years"
 * tells them what to go and check. Scores move too, but the words are the product.
 *
 * <p><b>Flags are suspicion, not proof.</b> Rights issues and genuine growth capital both
 * dilute. Receivables climb for boring commercial reasons. The output is a list of things
 * to read the annual report about.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForensicScreenService {

    private final FundamentalsHistoryService historyService;
    private final NseDataService nseDataService;

    /** Share-count CAGR above this over 3 years is serial dilution. */
    public static final double DILUTION_CAGR_PERCENT = 5.0;
    /** Receivables growing this many times faster than sales is a paper-revenue signal. */
    public static final double RECEIVABLES_GROWTH_MULTIPLE = 1.5;
    /** Cumulative CFO / cumulative profit below this over 3 years is a cash-quality concern. */
    public static final double MIN_THREE_YEAR_CASH_CONVERSION = 0.6;

    /**
     * Announcements scanned for auditor / related-party keywords. Roughly a quarter of
     * filings for an active company — enough that a resignation filed weeks ago is still
     * visible, which the 5-item default was not (B-038).
     */
    public static final int ANNOUNCEMENT_SCAN_LIMIT = 40;

    /**
     * Composite at or above which the screener spends a network call on the auditor scan.
     *
     * <p>The auditor flag's job is the HIGH_RISK cap at 54, so it can only change an outcome
     * for a stock that would otherwise score above that. Scanning the whole universe would
     * add ~290 NSE calls to a 12-minute run to alter nothing for two-thirds of them; scanning
     * none of it (the shipped behaviour) left SPEC §32.4's guarantee unimplemented in the one
     * place that decides what gets recommended.
     */
    public static final int AUDITOR_SCAN_MIN_COMPOSITE = 55;

    /** Announcement keywords that indicate an auditor problem — the most serious flag here. */
    private static final List<String> AUDITOR_KEYWORDS = List.of(
            "resignation of auditor", "auditor resignation", "resigned as statutory auditor",
            "qualified opinion", "adverse opinion", "disclaimer of opinion",
            "auditor has resigned", "resignation of statutory auditor");

    /**
     * Phrases that mean the audit was <b>clean</b>, which must be checked BEFORE the
     * problem keywords above (B-037).
     *
     * <p>SEBI LODR Reg 33(3)(d) <i>requires</i> every listed company to file a declaration
     * that its audit report carries an unmodified opinion. That announcement text contains
     * "unqualified opinion" — which contains the substring "qualified opinion". A plain
     * {@code contains} therefore fires the most serious flag in this system, worth −8 and a
     * forced HIGH_RISK cap, on the routine filing that exists to say nothing is wrong.
     *
     * <p>Hyphenated forms ("un-modified", "un-qualified") are common in real filings, so
     * matching happens on a hyphen-stripped copy of the text.
     */
    private static final List<String> CLEAN_OPINION_MARKERS = List.of(
            "unqualified opinion", "unmodified opinion", "unmodified audit",
            "without qualification", "no qualification", "nil qualification");

    /** Related-party keywords — surfaced for the reader, deliberately never scored. */
    private static final List<String> RELATED_PARTY_KEYWORDS = List.of(
            "related party", "inter-corporate deposit", "loan to promoter",
            "loans to related", "promoter group entity");

    @Data
    @Builder
    public static class ForensicFlag {
        /** Stable code: DILUTION / RECEIVABLES / CASH_CONVERSION / AUDITOR / RELATED_PARTY */
        private String code;
        /** HIGH / MEDIUM / INFO — INFO never changes a score. */
        private String severity;
        /** One plain-English sentence naming what was measured and what it means. */
        private String message;
        /** Points removed from the composite. Zero for INFO flags. */
        private int scorePenalty;
    }

    @Data
    @Builder
    public static class ForensicResult {
        private String symbol;
        private List<ForensicFlag> flags;
        private int totalPenalty;
        /** True when an auditor flag fired — forces financial quality to HIGH_RISK. */
        private boolean forcesHighRisk;
        private int yearsAvailable;
        /** Checks that could not run, so "no flags" is never confused with "nothing checked". */
        private List<String> notMeasured;

        public boolean isClean() {
            return flags.isEmpty();
        }

        /** Compact persisted form: {@code CODE:severity;CODE:severity}. */
        public String toStorageString() {
            if (flags.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (ForensicFlag f : flags) {
                if (sb.length() > 0) sb.append(';');
                sb.append(f.getCode()).append(':').append(f.getSeverity());
            }
            return sb.toString();
        }
    }

    /**
     * Run every check that the available data supports.
     *
     * @param includeAnnouncements whether to scan NSE corporate announcements (a network
     *                             call — pass false inside a large screening loop)
     */
    public ForensicResult screen(String symbol, boolean includeAnnouncements) {
        List<AnnualFundamentalsEntity> history = historyService.history(symbol);
        List<String> announcements = List.of();
        if (includeAnnouncements) {
            try {
                String tradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;
                // Deliberately NOT fetchCorporateAnnouncements(), which truncates to the
                // last 5 filings (B-038). For an active company that is often a fortnight,
                // so an auditor resignation ages out of view within days of being filed —
                // the one flag here that must not be missed. The record form takes a limit
                // and is cached for 30 minutes per symbol.
                announcements = nseDataService.fetchAnnouncementRecords(tradingSymbol, ANNOUNCEMENT_SCAN_LIMIT)
                        .stream()
                        .map(r -> "[" + r.date() + "] " + r.subject())
                        .toList();
            } catch (Exception e) {
                log.debug("Announcements unavailable for {}: {}", symbol, e.getMessage());
            }
        }
        return evaluate(symbol, history, announcements);
    }

    /**
     * Pure evaluator — no repository and no network, so each flag's boundary is testable.
     *
     * @param history       rows oldest-first
     * @param announcements recent corporate-announcement headlines; may be empty
     */
    public static ForensicResult evaluate(String symbol, List<AnnualFundamentalsEntity> history,
                                          List<String> announcements) {
        List<ForensicFlag> flags = new ArrayList<>();
        List<String> notMeasured = new ArrayList<>();
        int years = history == null ? 0 : history.size();

        checkDilution(history, flags, notMeasured);
        checkReceivables(history, flags, notMeasured);
        checkCashConversion(history, flags, notMeasured);
        boolean auditor = checkAuditor(announcements, flags, notMeasured);
        checkRelatedParty(announcements, flags);

        int penalty = flags.stream().mapToInt(ForensicFlag::getScorePenalty).sum();
        return ForensicResult.builder()
                .symbol(symbol)
                .flags(flags)
                .totalPenalty(penalty)
                .forcesHighRisk(auditor)
                .yearsAvailable(years)
                .notMeasured(notMeasured)
                .build();
    }

    // ---- 1. Dilution

    private static void checkDilution(List<AnnualFundamentalsEntity> h,
                                      List<ForensicFlag> flags, List<String> notMeasured) {
        List<Double> counts = series(h, 4, AnnualFundamentalsEntity::getShareCount);
        if (counts.size() < 4) {
            notMeasured.add("Dilution — needs four years of share count");
            return;
        }
        // Unit-change guard (B-046) runs first: no adjustment or growth rate may be attempted on
        // a series that is not on a common basis. The import stores whatever unit the user's
        // sheet used while the XBRL pipeline stores crore, and a series mixing the two shows a
        // 10-million-fold step that is not a share action at all.
        if (hasImplausibleStep(counts)) {
            notMeasured.add("Dilution — the stored share counts jump by an implausible factor "
                    + "between years, which means the figures are not on a common basis");
            return;
        }

        // Corporate actions are divided out before any growth rate is taken (B-066): a bonus or
        // split multiplies the count by an exact simple ratio and dilutes nobody, so leaving it
        // in the series reports a shareholder-friendly event as the screen's most serious flag.
        BonusAdjustment adjusted = adjustForCorporateActions(counts);
        Double growth = TurnaroundDetectionService.cagr(
                adjusted.counts().get(0), adjusted.counts().get(3), 3);
        if (growth == null) {
            notMeasured.add("Dilution — share count is not usable for a growth rate");
            return;
        }

        // Where the money came from is the better question, so equity stays authoritative
        // wherever it can be answered; the ratio test above is what carries the cases it cannot.
        // Reading "cannot tell" as "not a bonus" is precisely the inversion that fired a HIGH
        // flag on BEL's 1:2 bonus, on the strength of one null equity year (B-066).
        boolean equityKnown = !series(h, 4, AnnualFundamentalsEntity::getEquity).isEmpty()
                && !series(h, 4, AnnualFundamentalsEntity::getNetProfit).isEmpty();
        boolean moneyCameIn = equityKnown && !isBonusOrSplit(h);

        if (growth <= DILUTION_CAGR_PERCENT) {
            if (!adjusted.adjusted()) return;    // simply no dilution, and nothing to explain
            if (moneyCameIn) {
                // The count rose by an exact ratio, yet net worth grew by more than the profits
                // earned — so cash did arrive and calling it a bonus would be wrong. What is left
                // is too small to flag, so the honest report is that it could not be settled.
                notMeasured.add("Dilution — the share count rose by " + adjusted.describeSteps()
                        + ", which looks like a bonus or split, but net worth grew by more than the "
                        + "profits earned, which suggests new money came in. Treated as unsettled "
                        + "rather than cleared.");
                return;
            }
            // Report the action rather than staying silent: the reader would otherwise have no way
            // to tell an adjusted series from one that never moved, and could not tell whether the
            // check had run at all (Gotcha 44). INFO scores zero and stops nothing (SPEC §32.4).
            flags.add(ForensicFlag.builder()
                    .code("CORPORATE_ACTION")
                    .severity("INFO")
                    .scorePenalty(0)
                    .message(String.format(
                            "The share count rose %s, but this was a bonus issue or stock split, not "
                                    + "dilution: every holder's stake is unchanged and the price adjusts to "
                                    + "match. After allowing for it the count is flat, so nothing is being "
                                    + "watered down.", adjusted.describeSteps()))
                    .build());
            return;
        }

        // Growth survives the adjustment. If equity can vouch that no external capital arrived,
        // the rise is still not dilution.
        if (equityKnown && isBonusOrSplit(h)) {
            notMeasured.add("Dilution — share count rose but no new money came into the company, "
                    + "so this looks like a bonus issue or stock split rather than dilution");
            return;
        }

        String adjustmentNote = adjusted.adjusted()
                ? String.format(" (after setting aside a %s bonus or split, which is not dilution)",
                        adjusted.describeSteps())
                : "";
        // Severity follows the strength of the evidence, not the size of the number (B-092).
        // HIGH says "new money was raised and your stake was watered down", and it is only
        // sayable when equity vouches for it: HIGH disqualifies a stock outright (Gotcha 77) and
        // caps the composite at 54, so it must not rest on an absence. Where net worth is not on
        // file for enough years, a rising count is a caution — the reader is told what was
        // measured and what could not be settled.
        //
        // The false positive this fixes was HDFCBANK: 272 -> 548 crore shares (2019 1:2 split),
        // 558 -> 760 (the HDFC Ltd merger) and 760 -> 1539 (the 2025 1:1 bonus). Three real
        // corporate actions, none of them dilution, each landing just off an exact ratio because
        // ESOPs were issued in the same year — so the deliberately tight 0.05% tolerance
        // (Gotcha 86) could not divide them out — with equity null for 2019-2023.
        boolean verified = equityKnown;
        String caveat = verified ? ""
                : " Net worth is not on file for enough years to tell money raised from a bonus, "
                  + "split or merger, so this is flagged as a caution rather than a red flag.";
        flags.add(ForensicFlag.builder()
                .code("DILUTION")
                .severity(verified ? "HIGH" : "MEDIUM")
                .scorePenalty(verified ? 5 : 2)
                .message(String.format(
                        "The number of shares has grown %.1f%% a year for three years%s. Profits are being "
                                + "split among steadily more shareholders, so the company can grow while your "
                                + "stake shrinks.%s", growth, adjustmentNote, caveat))
                .build());
    }

    /** Share counts with bonus/split steps divided out, and what was removed. */
    record BonusAdjustment(List<Double> counts, List<Double> ratios) {
        boolean adjusted() { return !ratios.isEmpty(); }

        String describeSteps() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ratios.size(); i++) {
                if (i > 0) sb.append(" and ");
                sb.append(String.format("%.3gx", ratios.get(i)));
            }
            return sb.toString();
        }
    }

    /** Widest bonus ratio worth recognising: 9:1 gives 10x, and splits go to 10x. */
    private static final int MAX_ACTION_MULTIPLE = 10;
    /**
     * A bonus ratio is exact by construction, so the tolerance only has to absorb rounding in the
     * stored crore figure — NMDC's 293.07 -> 879.18 is 3x to 0.003%. It must be tight: at 0.5% the
     * grid of simple ratios is dense enough to swallow real capital raises, and it did. BANKINDIA
     * went 327.766 -> 410.431 -> 455.341 (a government infusion and a QIP, i.e. genuine dilution)
     * and those steps sit 0.18% and 0.15% from 5/4 and 10/9 — cleared as "bonus issues" until this
     * was tightened. 0.05% keeps the real actions (15x inside) and excludes those raises (3x
     * outside).
     */
    private static final double ACTION_RATIO_TOLERANCE = 0.0005;

    /**
     * Removes bonus-issue and stock-split steps from a share-count series (B-066).
     *
     * <p><b>Why this is needed on top of {@link #isBonusOrSplit}.</b> That test asks where the
     * money came from, which is the right question but needs four years of equity and profit.
     * {@link #series} returns nothing when any single year is missing, so one null equity year
     * turned the answer into "cannot tell" — and the caller read "cannot tell" as "not a bonus"
     * and fired a HIGH flag. Measured on the live portfolio 2026-08-28: BEL (243.66 → 730.98,
     * exactly 3x, a 1:2 bonus) and MAZDOCK (20.169 → 40.338, exactly 2x, a 1:1 bonus) were both
     * reported as serial dilution and sent to AVOID.
     *
     * <p><b>The discriminator is arithmetic, not accounting.</b> A bonus or split multiplies the
     * count by an exact simple ratio — 2, 3, 1.5, 2.5 — because it is defined as a ratio of
     * shares. Money raised by issuing stock lands on an arbitrary number (1.037, 1.21): hitting
     * 2.0000 by selling shares for cash would be a coincidence. So a step within 0.5% of a simple
     * ratio is treated as a corporate action and divided out; everything else stays in the series
     * and is measured as dilution. A company that does both — a 1:1 bonus and steady ESOP issuance
     * — therefore still gets flagged, on the ESOP portion alone, which is the honest reading.
     */
    static BonusAdjustment adjustForCorporateActions(List<Double> counts) {
        List<Double> out = new ArrayList<>(counts);
        List<Double> ratios = new ArrayList<>();
        double divisor = 1.0;
        for (int i = 1; i < counts.size(); i++) {
            Double prev = counts.get(i - 1);
            Double curr = counts.get(i);
            if (prev != null && curr != null && prev > 0) {
                Double action = simpleRatio(curr / prev);
                if (action != null) {
                    divisor *= action;
                    ratios.add(action);
                }
            }
            Double v = counts.get(i);
            out.set(i, v == null ? null : v / divisor);
        }
        return new BonusAdjustment(List.copyOf(out), List.copyOf(ratios));
    }

    /**
     * The exact simple ratio a step matches, or null when it matches none. Only ratios above 1
     * are considered: a share count that falls is a buyback, which this method must not rewrite.
     */
    private static Double simpleRatio(double ratio) {
        if (!(ratio > 1.0 + ACTION_RATIO_TOLERANCE) || ratio > MAX_ACTION_MULTIPLE + 1) return null;
        for (int denominator = 1; denominator <= 5; denominator++) {
            for (int numerator = denominator + 1; numerator <= MAX_ACTION_MULTIPLE * denominator; numerator++) {
                double candidate = (double) numerator / denominator;
                if (candidate <= 1.0) continue;
                if (Math.abs(ratio - candidate) / candidate <= ACTION_RATIO_TOLERANCE) return candidate;
            }
        }
        return null;
    }

    /**
     * Distinguishes a bonus issue or stock split from genuine dilution.
     *
     * <p><b>Why this is essential rather than a refinement.</b> Bonus issues are common in
     * India — Reliance did 1:1 in 2024 — and they double the share count while diluting
     * nobody: every holder's proportion is unchanged and the price halves to match. Without
     * this check the dilution flag fires on some of the best companies in the market, and a
     * forensic section that cries wolf gets ignored, taking the real flags down with it.
     *
     * <p><b>The discriminator is where the money came from.</b> A genuine share issue brings
     * cash in, so net worth rises by roughly the amount raised on top of retained profit. A
     * bonus issue only moves reserves into share capital, leaving net worth untouched; a
     * split does not change the balance sheet at all. So: if equity grew by no more than the
     * profits earned over the period, no external capital arrived and the extra shares were
     * not sold to anyone.
     *
     * <p><b>Known limitation.</b> A company that raises capital <i>and</i> pays large
     * dividends could suppress the flag, since dividends reduce equity growth and this
     * system does not carry dividends per year on the history row. The check errs toward
     * silence, which is the right direction: reporting "not measured" is honest, while a
     * false accusation of dilution against a company doing a bonus issue is not.
     */
    static boolean isBonusOrSplit(List<AnnualFundamentalsEntity> h) {
        List<Double> equity = series(h, 4, AnnualFundamentalsEntity::getEquity);
        List<Double> profit = series(h, 4, AnnualFundamentalsEntity::getNetProfit);
        if (equity.size() < 4 || profit.size() < 4) return false;   // cannot tell — let the flag run

        double equityGrowth = equity.get(3) - equity.get(0);
        // Profit earned across the three intervals covered by the four data points.
        double retained = profit.get(1) + profit.get(2) + profit.get(3);
        if (retained <= 0) return false;

        // 10% headroom absorbs revaluation reserves, other comprehensive income and the
        // approximation in reconstructing equity from share capital plus reserves.
        return equityGrowth <= retained * 1.10;
    }

    // ---- 2. Receivables vs sales

    /** True when consecutive share counts differ by more than 50x — a unit change, not an issue. */
    private static boolean hasImplausibleStep(List<Double> counts) {
        for (int i = 1; i < counts.size(); i++) {
            Double a = counts.get(i - 1);
            Double b = counts.get(i);
            if (a == null || b == null || a <= 0 || b <= 0) continue;
            double ratio = Math.max(a, b) / Math.min(a, b);
            if (ratio > 50) return true;
        }
        return false;
    }

    private static void checkReceivables(List<AnnualFundamentalsEntity> h,
                                         List<ForensicFlag> flags, List<String> notMeasured) {
        List<Double> rec = series(h, 3, AnnualFundamentalsEntity::getReceivables);
        List<Double> sales = series(h, 3, AnnualFundamentalsEntity::getSales);
        if (rec.size() < 3 || sales.size() < 3) {
            notMeasured.add("Receivables quality — needs three years of receivables and sales");
            return;
        }
        Double recGrowth = TurnaroundDetectionService.cagr(rec.get(0), rec.get(2), 2);
        Double salesGrowth = TurnaroundDetectionService.cagr(sales.get(0), sales.get(2), 2);
        if (recGrowth == null || salesGrowth == null) {
            notMeasured.add("Receivables quality — growth not computable from these bases");
            return;
        }
        // Only meaningful when sales are actually growing; against flat or shrinking sales
        // the ratio explodes and says nothing.
        if (salesGrowth > 1.0 && recGrowth > salesGrowth * RECEIVABLES_GROWTH_MULTIPLE) {
            flags.add(ForensicFlag.builder()
                    .code("RECEIVABLES")
                    .severity("MEDIUM")
                    .scorePenalty(3)
                    .message(String.format(
                            "Money owed by customers is growing %.0f%% a year while sales grow %.0f%%. Sales are "
                                    + "being booked faster than they are being collected, which can flatter revenue.",
                            recGrowth, salesGrowth))
                    .build());
        }
    }

    // ---- 3. Three-year cash conversion

    private static void checkCashConversion(List<AnnualFundamentalsEntity> h,
                                            List<ForensicFlag> flags, List<String> notMeasured) {
        List<Double> cfo = series(h, 3, AnnualFundamentalsEntity::getOperatingCashFlow);
        List<Double> profit = series(h, 3, AnnualFundamentalsEntity::getNetProfit);
        if (cfo.size() < 3 || profit.size() < 3) {
            notMeasured.add("Multi-year cash conversion — needs three years of cash flow and profit");
            return;
        }
        double sumCfo = cfo.stream().mapToDouble(Double::doubleValue).sum();
        double sumProfit = profit.stream().mapToDouble(Double::doubleValue).sum();
        if (sumProfit <= 0) {
            notMeasured.add("Multi-year cash conversion — cumulative profit is not positive");
            return;
        }
        double ratio = sumCfo / sumProfit;
        if (ratio < MIN_THREE_YEAR_CASH_CONVERSION) {
            flags.add(ForensicFlag.builder()
                    .code("CASH_CONVERSION")
                    .severity("HIGH")
                    .scorePenalty(5)
                    .message(String.format(
                            "Over three years the company turned only %.0f paise of each rupee of reported profit "
                                    + "into actual cash. Profit that never becomes cash is the most common sign that "
                                    + "the profit is not real.", ratio * 100))
                    .build());
        }
    }

    // ---- 4. Auditor problems (the one that forces HIGH_RISK)

    private static boolean checkAuditor(List<String> announcements,
                                        List<ForensicFlag> flags, List<String> notMeasured) {
        if (announcements == null || announcements.isEmpty()) {
            notMeasured.add("Auditor check — no corporate announcements available");
            return false;
        }
        for (String a : announcements) {
            if (a == null) continue;
            String lower = normaliseAnnouncement(a);
            // A clean-opinion declaration is checked first and skips this announcement
            // entirely (B-037). Erring toward a false negative is deliberate here: this
            // flag costs −8 AND forces the HIGH_RISK cap at 54, so a false positive
            // suppresses a genuine candidate outright, while a missed one leaves the
            // stock where the other measures already put it.
            if (isCleanOpinionDeclaration(lower)) continue;
            for (String kw : AUDITOR_KEYWORDS) {
                if (lower.contains(kw)) {
                    flags.add(ForensicFlag.builder()
                            .code("AUDITOR")
                            .severity("HIGH")
                            .scorePenalty(8)
                            .message("An auditor problem was disclosed: \"" + trim(a) + "\". The auditor is the "
                                    + "only independent check on the numbers every other measure here is built "
                                    + "from. Treat all of them as unverified until this is explained.")
                            .build());
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Lower-case an announcement and strip hyphens, so "un-modified" and "unmodified" —
     * both of which appear in real SEBI filings — match the same marker.
     */
    static String normaliseAnnouncement(String s) {
        return s.toLowerCase(Locale.ENGLISH).replace("-", "");
    }

    /** True when the text is a declaration that the audit opinion was clean (B-037). */
    static boolean isCleanOpinionDeclaration(String normalised) {
        for (String marker : CLEAN_OPINION_MARKERS) {
            if (normalised.contains(marker.replace("-", ""))) return true;
        }
        return false;
    }

    // ---- 5. Related-party mentions (informational only)

    private static void checkRelatedParty(List<String> announcements, List<ForensicFlag> flags) {
        if (announcements == null) return;
        for (String a : announcements) {
            if (a == null) continue;
            String lower = a.toLowerCase(Locale.ENGLISH);
            for (String kw : RELATED_PARTY_KEYWORDS) {
                if (lower.contains(kw)) {
                    // Deliberately zero penalty: these announcements are routine as often as
                    // they are worrying, and scoring them before measuring would add noise
                    // to the composite in exchange for nothing.
                    flags.add(ForensicFlag.builder()
                            .code("RELATED_PARTY")
                            .severity("INFO")
                            .scorePenalty(0)
                            .message("Worth a read: \"" + trim(a) + "\". Deals between the company and entities its "
                                    + "owners control are legal and often routine, but they are also how money "
                                    + "leaves a company quietly. Not scored.")
                            .build());
                    return;
                }
            }
        }
    }

    // ---- helpers

    /** Last {@code n} values oldest-first, or empty when any is missing (a gap makes a trend unmeasurable). */
    private static List<Double> series(List<AnnualFundamentalsEntity> h, int n,
                                       java.util.function.Function<AnnualFundamentalsEntity, Double> get) {
        if (h == null || h.size() < n) return List.of();
        List<Double> out = new ArrayList<>(n);
        for (AnnualFundamentalsEntity e : h.subList(h.size() - n, h.size())) {
            Double v = get.apply(e);
            if (v == null) return List.of();
            out.add(v);
        }
        return out;
    }

    private static String trim(String s) {
        String t = s.trim();
        return t.length() <= 140 ? t : t.substring(0, 137) + "...";
    }
}
