package com.example.trading.holdings;

import com.example.trading.multibagger.SuggestedEntry;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.watchlist.BuyTimingVerdict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Fills in a holding's canonical answers before it is handed to any screen (SPEC §6.6).
 *
 * <h2>Why this exists</h2>
 * The app kept growing surfaces that answered the same question their own way. The portfolio table
 * and the stock page each rendered {@code recommendation} raw, the portfolio computed its own entry
 * price ({@code suggestedEntry ?? support1}) while the screener and watchlist used the shared
 * ladder, and a fourth place reconciled the signal against the buy-timing verdict. On 2026-09-02
 * that produced BEL reading {@code BUY} on one screen and {@code AVOID} on the next, and
 * disagreements on 9 of 32 holdings.
 *
 * <p>Fixing each screen separately is what created the problem in the first place. So the answers
 * are attached to <strong>the holding itself, on every read path</strong>: a screen can only render
 * what it is given, and a new screen inherits the right answers without having to know they exist.
 * That is the difference between "we fixed the screens" and "the screens cannot disagree".
 *
 * <h2>Contract</h2>
 * Everything set here is {@code @Transient} — computed, never stored. {@code recommendation} on the
 * row is read and never rewritten, so ML labels, stored history and the raw signal are untouched
 * (Gotcha 69). Decoration is best-effort: a failure leaves the plain row rather than failing the
 * request, because a portfolio that will not load is far worse than one missing a derived column.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldingsViewDecorator {

    private final HoldingsBuyTimingService buyTimingService;
    private final com.example.trading.multibagger.CompoundingLensService compoundingLensService;
    /** SPEC 46.5/46.6: DB-only reads. The IPO repository is used directly, not the tracking
     *  service, so the decorator never acquires a path to NSE or Kite by accident. */
    private final com.example.trading.portfolio.tax.TaxLotService taxLotService;
    private final com.example.trading.universe.ipo.IpoIssueRepository ipoIssueRepository;
    private final com.example.trading.universe.UniverseConfig universeConfig;
    /** SPEC 48.4. DB-only, like every other dependency here - no model, no NSE, no broker. */
    private final com.example.trading.macro.MacroExposureService macroExposureService;
    /** SPEC 49.14. DB-only: the ledger the 13:20 pass already wrote, never a live fetch. */
    private final com.example.trading.analyst.AnalystTargetViewService analystTargetViewService;

    /** SPEC 50.5. DB-only: the quarters the screening already captured, never a live NSE fetch. */
    private final com.example.trading.earnings.QuarterlyResultService quarterlyResultService;

    /** Decorates every row in place and returns the same list, for use inline in a controller. */
    public List<HoldingsEntity> decorate(List<HoldingsEntity> holdings) {
        if (holdings == null || holdings.isEmpty()) {
            return holdings;
        }
        Map<String, HoldingsBuyTimingService.HoldingBuyTiming> timing;
        try {
            timing = buyTimingService.buildAll();
        } catch (Exception e) {
            // WARN, and say what the absence will look like: these columns will read "not
            // measured", which must not be mistaken for a considered negative (Gotcha 21).
            log.warn("Buy-timing lookup failed — every holding will show an unreconciled signal and "
                    + "no entry plan, which must not be read as 'no opinion': {}", e.getMessage());
            return holdings;
        }
        // One query for the whole table, not one per row (SPEC 41 bulk lens).
        Map<String, com.example.trading.multibagger.CompoundingLensService.Reading> compounding =
                Map.of();
        try {
            compounding = compoundingLensService.forSymbols(
                    holdings.stream().map(HoldingsEntity::getSymbol).toList());
        } catch (Exception e) {
            log.warn("Compounding lens failed — every holding will read 'never screened' in the "
                    + "quality column, which must not be read as a poor verdict: {}", e.getMessage());
        }

        // One bulk call for the whole table (SPEC 48.4): per row it would be roughly 130 queries
        // on a page load, because each holding resolves through up to four symbol spellings.
        Map<String, com.example.trading.macro.MacroExposureService.Reading> macro = Map.of();
        try {
            macro = macroExposureService.forSymbols(
                    holdings.stream().map(HoldingsEntity::getSymbol).toList());
        } catch (Exception e) {
            log.warn("Macro exposure lookup failed - every holding will read 'not measured' in the "
                    + "events column, which must not be read as 'no event affects it': {}", e.getMessage());
        }

        // One query for the whole table (SPEC 49.14). Per row it would be ~120 lookups on a page
        // load, because each holding resolves through up to four symbol spellings (Gotcha 84).
        Map<String, com.example.trading.analyst.AnalystTargetViewService.Coverage> analyst = Map.of();
        try {
            analyst = analystTargetViewService.forSymbols(
                    holdings.stream().map(HoldingsEntity::getSymbol).toList());
        } catch (Exception e) {
            // Say what the absence will look like. A blank analyst column reads as "nobody covers
            // this", which is the one thing a failed lookup must never be mistaken for (Gotcha 44).
            log.warn("Analyst coverage lookup failed - every holding will read 'not measured' in "
                    + "the analyst column, which must not be read as 'no broker covers it': {}",
                    e.getMessage());
        }

        // One query for the whole table (SPEC 50.5), for the same reason as the three above:
        // each holding resolves through up to four symbol spellings, so a per-row lookup is
        // ~120 queries on a page load (the B-115 shape).
        Map<String, com.example.trading.earnings.QuarterlyResultService.Reading> earnings = Map.of();
        try {
            earnings = quarterlyResultService.forSymbols(
                    holdings.stream().map(HoldingsEntity::getSymbol).toList());
        } catch (Exception e) {
            // Name what the absence will look like. A blank result column reads as "this company
            // has not reported anything worrying", which is the one thing a failed lookup must
            // never be mistaken for (Gotcha 44).
            log.warn("Quarterly result lookup failed - every holding will read 'not measured' in "
                    + "the results column, which must not be read as 'nothing to report': {}",
                    e.getMessage());
        }

        for (HoldingsEntity h : holdings) {
            try {
                decorateOne(h, timing.get(h.getSymbol()));
                applyCompounding(h, compounding.get(h.getSymbol()));
                applyHoldingPeriod(h);
                applyIpo(h);
                applyMacro(h, macro.get(h.getSymbol()));
                applyAnalyst(h, analyst.get(h.getSymbol()));
                applyEarnings(h, earnings.get(h.getSymbol()));
                applyTheme(h);
                h.setSector(com.example.trading.portfolio.SectorMapping.resolve(h.getSymbol(), h.getIndustry()));
            } catch (Exception e) {
                log.warn("Could not decorate {} — its row falls back to the raw stored signal: {}",
                        h.getSymbol(), e.getMessage());
            }
        }
        return holdings;
    }

    /** Decorates a single row (the by-symbol endpoint and the stock page). */
    public HoldingsEntity decorateOne(HoldingsEntity h) {
        if (h == null) {
            return null;
        }
        try {
            decorateOne(h, buyTimingService.buildAll().get(h.getSymbol()));
            applyCompounding(h, compoundingLensService.forSymbol(h.getSymbol()));
            applyHoldingPeriod(h);
            applyIpo(h);
            applyMacro(h, macroExposureService.forSymbol(h.getSymbol()));
            applyAnalyst(h, analystTargetViewService.forSymbolCoverage(h.getSymbol()));
            applyEarnings(h, quarterlyResultService.forSymbol(h.getSymbol()));
            applyTheme(h);
            h.setSector(com.example.trading.portfolio.SectorMapping.resolve(h.getSymbol(), h.getIndustry()));
        } catch (Exception e) {
            log.warn("Could not decorate {}: {}", h.getSymbol(), e.getMessage());
        }
        return h;
    }

    /**
     * Attach the policy-backed theme tags (SPEC 51.5).
     *
     * <p><b>Always writes, even when the answer is nothing.</b> Unlike every neighbour here, this
     * method has no null-reading escape: the theme map is a static in-memory table that cannot
     * fail to answer, so an untagged business gets an <b>empty</b> list rather than a null one.
     * That is the distinction the feature exists to hold: empty means the map was consulted and
     * names no tracked theme for this business - a finding, rendered as a plain dash - while null
     * would mean the lookup never ran and must render as "not measured" (Gotcha 121). Collapsing
     * them lets a blind spot read as an all-clear.
     *
     * <p>Contributes zero points to any score and is not an input to any verdict.
     */
    private static void applyTheme(HoldingsEntity h) {
        var tags = com.example.trading.universe.theme.UniverseThemes.tagsFor(h.getSymbol());
        h.setThemes(tags.stream().map(t -> t.theme().name()).distinct().toList());
        h.setThemeLabels(tags.stream().map(t -> t.theme().label()).distinct().toList());
        h.setThemePolicies(tags.stream().map(
                com.example.trading.universe.theme.UniverseThemes.Tag::policy).distinct().toList());
        h.setThemeRoles(tags.stream().map(
                com.example.trading.universe.theme.UniverseThemes.Tag::role).toList());
    }

    /**
     * Attach the macro reading (SPEC 48.4).
     *
     * <p>A null reading leaves every field null, which the screens render as "not measured" - a gap
     * in the exposure map, never a statement that no event affects this business. The count is left
     * null rather than 0 for the same reason: zero events would be a measurement, and there is none
     * (Gotcha 21).
     */
    private static void applyMacro(HoldingsEntity h,
                                   com.example.trading.macro.MacroExposureService.Reading reading) {
        if (reading == null) {
            return;
        }
        var result = reading.result();
        h.setMacroExposure(result.verdict().name());
        h.setMacroExposureStrength(result.strength() == null ? null : result.strength().name());
        h.setMacroExposureReasons(result.reasons().stream()
                .map(com.example.trading.macro.MacroExposureRead.Reason::text).toList());
        h.setMacroExposureEvents(result.reasons().isEmpty() ? null : result.reasons().size());
        h.setMacroExposureFrom(reading.symbolAnswered());
    }

    /**
     * Who is quoting a target on this holding (SPEC 49.14).
     *
     * <p>A null reading leaves every field null, so the column reads "not measured" rather than
     * "nobody covers it" — those are different facts and the second one is not ours to assert
     * (Gotcha 44). A reading that found nothing is different again: it sets a counted <b>zero</b>,
     * because the ledger genuinely was searched. What a zero means is still bounded by SPEC 49.7,
     * and the note the reading carries says so in words.
     *
     * <p>Contributes zero points to any score and is not an input to any verdict.
     */
    private static void applyAnalyst(HoldingsEntity h,
                                     com.example.trading.analyst.AnalystTargetViewService.Coverage c) {
        if (c == null) {
            return;
        }
        h.setAnalystHouses(c.houses());
        h.setAnalystHouseNames(c.houseNames());
        h.setAnalystOpenTargets(c.openTargets());
        h.setAnalystMedianTarget(c.medianTarget());
        h.setAnalystHighestTarget(c.highestTarget());
        h.setAnalystLowestTarget(c.lowestTarget());
        h.setAnalystUpsidePct(c.impliedUpsidePct());
        h.setAnalystPriceAsStored(c.priceAsStored());
        h.setAnalystPriceAsOf(c.priceAsOf());
        h.setAnalystHousesEver(c.housesEver());
        h.setAnalystHouseNamesEver(c.houseNamesEver());
        h.setAnalystTargetsEver(c.targetsEver());
        h.setAnalystLastCallOn(c.lastCallOn());
        h.setAnalystTargetsFrom(c.symbolAnswered());
        h.setAnalystNote(c.note());
        h.setAnalystOvertaken(c.overtakenTargets());
    }

    /**
     * Attach the latest quarterly result read (SPEC 50.5).
     *
     * <p>A null reading, or one carrying NOT_MEASURED, leaves the verdict fields as they are, so
     * the column renders the striped not-measured marker. That means <b>no filed quarter has been
     * captured for this company</b> — a gap in what the app has collected — and never that the
     * company reported nothing or reported badly. The next-result window is still attached where
     * one could be worked out, because "we have not captured a result and one is overdue" is
     * exactly the state worth seeing.
     *
     * <p>Contributes zero points to any score and is not an input to any verdict.
     */
    private static void applyEarnings(HoldingsEntity h,
                                      com.example.trading.earnings.QuarterlyResultService.Reading r) {
        if (r == null) {
            return;
        }
        var result = r.result();
        h.setResultVerdict(result.verdict().name());
        h.setResultHeadline(result.headline());
        h.setResultMeasuredSignals(result.measuredSignals());
        h.setResultTotalSignals(result.totalSignals());
        if (result.measured()) {
            h.setResultQuarter(result.fiscalLabel());
            h.setResultPublishedOn(result.availableFrom());
            h.setResultRevenueYoyPercent(result.revenueYoyPercent());
            h.setResultProfitYoyPercent(result.profitYoyPercent());
            h.setResultMarginDeltaPp(result.marginDeltaPp());
            h.setResultRevised(result.revised());
            h.setResultFrom(r.symbolAnswered());
        }
        var next = r.expectation();
        h.setNextResultStatus(next.status().name());
        h.setNextResultText(next.text());
    }

    private void decorateOne(HoldingsEntity h, HoldingsBuyTimingService.HoldingBuyTiming t) {
        String verdict = t != null ? t.verdict() : null;
        String verdictReason = t != null ? t.reason() : null;

        h.setBuyTimingVerdict(verdict);
        h.setBuyTimingReason(verdictReason);

        SignalReconciliation.Result signal =
                SignalReconciliation.reconcile(h.getRecommendation(), verdict, verdictReason);
        h.setDisplaySignal(signal.displaySignal());
        h.setSignalNote(signal.note());

        // The same ladder the screener and watchlist show, from the levels this row already holds:
        // support1 IS the 20-day low, so no new data and no Kite call is involved. Computing it
        // here rather than in the page is the whole point — the portfolio used to have its own rule
        // (`suggestedEntry ?? support1`), which is how one stock could carry two entry prices.
        SuggestedEntry.Result entry = SuggestedEntry.compute(
                toVerdict(verdict),
                h.getCurrentPrice() > 0 ? h.getCurrentPrice() : null,
                h.getSupport1(), h.getAtr14(), h.getEma50());
        h.setSuggestedEntryRungs(entry.rungs());
        h.setSuggestedEntryBasis(entry.basis());
        h.setSuggestedEntryReason(entry.reason());
        h.setSuggestedEntryFallback(entry.fallback());
    }

    /**
     * Attaches the compounding lens, or leaves every field null when the stock has no screening
     * history. Null here means "never screened" and the UI renders the striped not-measured
     * marker; it must never be flattened into a NO verdict (Gotcha 21, 44).
     */
    private static void applyCompounding(
            HoldingsEntity h,
            com.example.trading.multibagger.CompoundingLensService.Reading reading) {
        if (reading == null || reading.result() == null) {
            return;
        }
        com.example.trading.multibagger.CompoundingQuality.Result r = reading.result();
        h.setCompounding(r.verdict().name());
        h.setCompoundingReason(r.reason());
        h.setCompoundingPassed(r.passed());
        h.setCompoundingApplicable(r.applicable());
        h.setCompoundingYearsOfAccounts(r.yearsOfAccounts());
        h.setCompoundingFrom(reading.symbolAnswered());
    }

    /**
     * Days held from the tax ledger (SPEC 46.5). A failure leaves the fields null, which the UI
     * renders as "unknown" - the same reading as no lot on file, and never as 0 days.
     */
    private void applyHoldingPeriod(HoldingsEntity h) {
        try {
            com.example.trading.portfolio.tax.TaxLotService.HoldingPeriod p = taxLotService.holdingPeriod(
                    h.getSymbol(), h.getIsin(), h.getQuantity(),
                    h.getPurchaseDate() == null ? null : h.getPurchaseDate().toLocalDate());
            h.setDaysHeld(p.daysHeld());
            h.setFirstBuyDate(p.firstBuyDate());
            h.setHoldingPeriodSource(p.source());
            h.setLtcgEligibleQuantity(p.ltcgEligibleQuantity());
            h.setStcgQuantity(p.stcgQuantity());
            h.setDaysUntilNextLtcg(p.daysUntilNextLtcg());
        } catch (Exception e) {
            log.warn("Holding period unavailable for {} - reads as unknown, not as 0 days: {}",
                    h.getSymbol(), e.getMessage());
            h.setHoldingPeriodSource("UNKNOWN");
        }
    }

    /**
     * If the stock is a listing the IPO tracker knows (SPEC 45), attach its stage and the next
     * lock-in expiry still ahead (SPEC 46.6). Absence means "not a tracked IPO", never a stage.
     */
    private void applyIpo(HoldingsEntity h) {
        try {
            java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
            com.example.trading.universe.ipo.IpoIssueEntity issue = null;
            for (String c : SymbolVariants.candidates(h.getSymbol())) {
                if (!c.startsWith("NSE:")) continue;
                issue = ipoIssueRepository.findBySymbol(c).orElse(null);
                if (issue != null) break;
            }
            if (issue == null || issue.getListingDate() == null || issue.getListingDate().isAfter(today)) return;
            h.setIpoListingDate(issue.getListingDate());
            h.setIpoIssuePrice(issue.getIssuePrice());
            // SPEC 45.9 (2): when no analysis has run, the two prices the capture already holds
            // decide "above the listing-day high" - otherwise every young holding read NOT_MEASURED
            // here while the IPO page showed a stage for the same stock (found on the first live run).
            Boolean above = issue.getAboveListingHigh();
            if (above == null && issue.getLatestPrice() != null && issue.getListingDayHigh() != null
                    && issue.getListingDayHigh() > 0) {
                above = issue.getLatestPrice() > issue.getListingDayHigh();
            }
            com.example.trading.universe.ipo.IpoLockIn.StageRead stage = com.example.trading.universe.ipo.IpoLockIn.stage(
                    issue.getListingDate(), today, universeConfig.getMinMonthsSinceListing(),
                    above, issue.getBaseFormed());
            h.setIpoStage(stage.stage().name());
            h.setIpoStageReason(stage.reason());
            for (com.example.trading.universe.ipo.IpoLockIn.Milestone m
                    : com.example.trading.universe.ipo.IpoLockIn.calendar(issue.getListingDate(), today)) {
                if (!m.passed()) {
                    h.setIpoNextUnlockLabel(m.label());
                    h.setIpoNextUnlockDate(m.approxDate());
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("IPO lookup failed for {} - the row will read as not a recent listing: {}",
                    h.getSymbol(), e.getMessage());
        }
    }

    /** An unrecognised or absent verdict is unjudged, never a negative one. */
    private static BuyTimingVerdict.Verdict toVerdict(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return BuyTimingVerdict.Verdict.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
