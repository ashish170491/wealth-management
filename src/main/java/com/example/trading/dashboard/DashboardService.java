package com.example.trading.dashboard;

import com.example.trading.holdings.HoldingsDecayService;
import com.example.trading.integrity.DataHealth;
import com.example.trading.persistence.ScreeningCoverageEntity;
import com.example.trading.intelligence.recommendation.RecommendationAccuracyService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsHistoryEntity;
import com.example.trading.persistence.HoldingsHistoryRepository;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.multibagger.CompoundingLensService;
import com.example.trading.multibagger.CompoundingQuality;
import com.example.trading.multibagger.ScreenerTimingVerdict;
import com.example.trading.multibagger.SuggestedEntry;
import com.example.trading.watchlist.BuyTimingVerdict;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import com.example.trading.persistence.RecommendationOutcomeRepository;
import com.example.trading.portfolio.AllocationDto;
import com.example.trading.portfolio.AllocationService;
import com.example.trading.portfolio.risk.DiversificationDto;
import com.example.trading.portfolio.risk.DiversificationService;
import com.example.trading.scheduler.MarketHoursService;
import com.example.trading.watchlist.WatchlistItemView;
import com.example.trading.watchlist.WatchlistTrackingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Read-only composition layer behind the dashboard UI (SPEC section 27).
 *
 * <p><b>This class contains no analysis.</b> Every number it returns is computed by an
 * existing module service; this only fans out, merges and shapes for the wire. If you are
 * about to write arithmetic on a domain value here, it belongs in the owning module
 * instead. SPEC section 20 rule 7 makes that a review-rejectable change.
 *
 * <p><b>Read-only contract:</b> no method here may write to the database, call the broker,
 * call an external API, or send email. Every dependency below is either a JPA read or a
 * pure in-memory computation over rows already in Postgres.
 *
 * <p>Sections degrade independently: a failure in (say) the accuracy engine must not blank
 * the portfolio KPIs, because SPEC section 18 requires each source to fail on its own.
 * That is what {@link #quietly} is for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {


    /**
     * Below this many scored picks we refuse to show calibration numbers at all.
     * A hit rate computed from 3 picks looks exactly as authoritative as one from 300,
     * which is the trap SPEC section 21 rule 7 exists to prevent.
     */
    private static final int MIN_SAMPLE_FOR_DISPLAY = 10;

    /** The horizon used for the landing-page accuracy headline. */
    private static final int HEADLINE_HORIZON_DAYS = 90;

    /**
     * When the Windows scheduled task TradingApp-Stop kills the app. Not read from config
     * because it lives in setup-scheduled-tasks.ps1, outside the JVM - the UI only needs it
     * to warn "the dashboard is about to go offline" (SPEC section 27.7). Keep in sync if
     * that task trigger changes.
     */
    private static final String SCHEDULED_SHUTDOWN_IST = "15:45";

    private static final DateTimeFormatter ISO_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final HoldingsRepository holdingsRepository;
    private final HoldingsHistoryRepository holdingsHistoryRepository;
    private final MultibaggerScoreRepository multibaggerScoreRepository;
    private final com.example.trading.universe.ipo.IpoIssueRepository ipoIssueRepository;
    private final RecommendationOutcomeRepository recommendationOutcomeRepository;
    private final com.example.trading.portfolio.core.CoreHoldingSnapshotRepository coreHoldingSnapshotRepository;
    private final com.example.trading.insider.InsiderDisclosureRepository insiderDisclosureRepository;
    private final com.example.trading.watchlist.WatchlistRepository watchlistRepository;
    private final com.example.trading.watchlist.WatchlistSnapshotRepository watchlistSnapshotRepository;
    private final HoldingsDecayService holdingsDecayService;
    private final DiversificationService diversificationService;
    private final AllocationService allocationService;
    private final RecommendationAccuracyService accuracyService;
    private final MarketHoursService marketHoursService;

    /**
     * Spring's configured mapper, not a fresh one: it carries the JSR-310 module and the same
     * date/number settings every other endpoint serialises with. A private `new ObjectMapper()`
     * threw on the entity's LocalDate, and had it not thrown it would have quietly emitted dates
     * in a different shape from the rest of the API.
     */
    private final ObjectMapper objectMapper;

    /** Read-only, DB-only. Used solely to keep the screener's entry verdict in step
     *  with the watchlist's for stocks the investor already tracks. */
    private final WatchlistTrackingService watchlistTrackingService;
    private final com.example.trading.fundamentals.AnnualFundamentalsRepository annualFundamentalsRepository;

    /**
     * Data-health screen only (SPEC 44). The repositories are DB reads and the rules live in
     * DataHealth; the Environment is here because a job's schedule is decided by configuration,
     * not by the literal in its annotation (B-087).
     */
    private final org.springframework.core.env.Environment environment;
    private final CompoundingLensService compoundingLensService;
    private final com.example.trading.macro.MacroExposureService macroExposureService;
    private final com.example.trading.macro.MacroEventRepository macroEventRepository;
    private final com.example.trading.intelligence.MarketImpactNewsRepository marketImpactNewsRepository;
    private final com.example.trading.analyst.AnalystTargetRepository analystTargetRepository;
    /**
     * Analyst coverage for the screening rows (SPEC 49.15). DB-only: the ledger the 13:20 pass
     * already wrote, never a live fetch, so the screener and discovery stay page-load safe.
     */
    private final com.example.trading.analyst.AnalystTargetViewService analystTargetViewService;
    /** SPEC 50: the freshness stamp for quarterly result capture. Read-only, like every field here. */
    private final com.example.trading.earnings.QuarterlyResultRepository quarterlyResultRepository;
    private final com.example.trading.learning.ScreeningCoverageService screeningCoverageService;
    private final com.example.trading.fundamentals.FundamentalsBackfillService fundamentalsBackfillService;
    private final com.example.trading.fundamentals.FundamentalsBackfillStatusRepository
            fundamentalsBackfillStatusRepository;

    // ------------------------------------------------------------------ health

    public DashboardDto.HealthResponse health() {
        List<HoldingsEntity> active = quietly("holdings", List::of, holdingsRepository::findActive);

        return new DashboardDto.HealthResponse(
                ZonedDateTime.now(marketHoursService.getMarketZone()).format(ISO_SECONDS),
                marketHoursService.isMarketOpen(),
                SCHEDULED_SHUTDOWN_IST,
                active.size(),
                freshness(active));
    }

    /**
     * Newest-row timestamps per table, so each screen can stamp "prices as of ..." and
     * "scores as of ..." independently. Values are ISO strings (or null when a table is
     * empty) rather than typed dates, because the mix of LocalDate and LocalDateTime here
     * would otherwise force the client to know which is which per key.
     */
    private Map<String, String> freshness(List<HoldingsEntity> activeHoldings) {
        Map<String, String> out = new LinkedHashMap<>();

        out.put("holdingsSynced", asString(latest(activeHoldings, HoldingsEntity::getLastSyncedAt)));
        out.put("holdingsAnalyzed", asString(latest(activeHoldings, HoldingsEntity::getLastAnalyzedAt)));
        out.put("holdingsHistory", asString(
                quietly("holdingsHistory", () -> null, holdingsHistoryRepository::findLatestRecordDate)));
        out.put("multibaggerScores", asString(quietly("multibaggerScores", () -> null, () -> {
            List<LocalDate> dates = multibaggerScoreRepository.findScreeningDates();
            return dates.isEmpty() ? null : dates.get(0); // query is ORDER BY screeningDate DESC
        })));
        out.put("recommendationOutcomes", asString(quietly("recommendationOutcomes", () -> null,
                recommendationOutcomeRepository::findLatestMeasuredDate)));
        out.put("holdingClassification", asString(quietly("holdingClassification", () -> null, () -> {
            List<LocalDate> dates = coreHoldingSnapshotRepository.findClassificationDates();
            return dates.isEmpty() ? null : dates.get(0);   // query is ORDER BY classifiedOn DESC
        })));
        // Watchlist (SPEC 37): last technical analysis over active rows, and the last daily snapshot.
        out.put("watchlistAnalyzed", asString(quietly("watchlistAnalyzed", () -> null, () ->
                watchlistRepository.findActiveOrderByAddedOn().stream()
                        .map(com.example.trading.watchlist.WatchlistEntity::getLastAnalyzedAt)
                        .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null))));
        out.put("watchlistSnapshot", asString(quietly("watchlistSnapshot", () -> null,
                watchlistSnapshotRepository::findLatestSnapshotDate)));
        // IPO pipeline (SPEC 45): stamped on every row the 12:15 capture saw, so it moves daily even
        // when no issue is open - a quiet feed and a dead feed must not look alike.
        out.put("ipoIssues", asString(quietly("ipoIssues", () -> null,
                ipoIssueRepository::findLatestCapturedAt)));
        // Macro (SPEC 48). Two keys because they answer different questions: the headline scan is
        // a 15-minute cron and CAN be late, while the event ledger is written only when the
        // investor presses the button and therefore cannot be - see DataHealth.ON_DEMAND.
        out.put("marketImpactNews", asString(quietly("marketImpactNews", () -> null,
                marketImpactNewsRepository::findLatestCreatedAt)));
        out.put("macroEvents", asString(quietly("macroEvents", () -> null,
                macroEventRepository::findLatestExtractedAt)));
        // Analyst target ledger (SPEC 49). Stamped when the daily pass last MEASURED something
        // rather than when it last recorded a target: brokerages do not publish every day, so a
        // capture stamp would go amber on an ordinary quiet week and train the eye past the
        // colour. The measurement pass runs every weekday whatever the news did.
        out.put("analystTargets", asString(quietly("analystTargets", () -> null,
                analystTargetRepository::findLatestMeasuredAt)));
        // Quarterly results (SPEC 50). Stamped when the capture last ran, not when a company last
        // published: results arrive in a cluster and then stop for two months, so a publication
        // stamp would read amber every inter-season week for no fault at all.
        out.put("quarterlyResults", asString(quietly("quarterlyResults", () -> null,
                quarterlyResultRepository::findLatestCapturedAt)));

        return out;
    }

    private static LocalDateTime latest(List<HoldingsEntity> holdings,
                                        Function<HoldingsEntity, LocalDateTime> field) {
        return holdings.stream().map(field).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    private static String asString(Object temporal) {
        return temporal == null ? null : temporal.toString();
    }

    // ------------------------------------------------------------- data health

    /**
     * Every automated data check the app can run on itself (SPEC 44).
     *
     * <p>Composition only, as everywhere else in this package: the freshness map comes from
     * {@link #freshness}, the coverage rows from the screening run that wrote them, the depth
     * histogram from the backfill service, and every verdict from the pure rule table in
     * {@link DataHealth}. No number is computed twice.
     *
     * <p>DB-only and safe on a page load, but heavier than the other endpoints here - the
     * depth histogram resolves the whole screening universe. It backs one screen the reader
     * opens deliberately, not a strip on every page.
     */
    public DashboardDto.DataHealthResponse dataHealth() {
        ZonedDateTime now = ZonedDateTime.now(marketHoursService.getMarketZone());
        List<DataHealth.Finding> findings = new ArrayList<>();
        List<String> notChecked = new ArrayList<>();

        // 1. Is each table as new as its own cron says it should be?
        Map<String, String> stamps = quietly("dataHealth.freshness", Map::of,
                () -> freshness(holdingsRepository.findActive()));
        for (DataHealth.ScheduleSpec declared : DataHealth.SCHEDULE.values()) {
            DataHealth.ScheduleSpec spec = resolveCron(declared);
            findings.add(DataHealth.freshness(spec, toLocalDate(stamps.get(spec.key())), now));
        }
        // Tables written only when the investor asks (SPEC 48.9). Kept apart from the schedule
        // loop because "late" is not a thing that can happen to them, and reporting one as late
        // would be a false alarm on the one channel whose whole job is to be believed.
        for (DataHealth.OnDemandSpec spec : DataHealth.ON_DEMAND.values()) {
            findings.add(DataHealth.onDemandFreshness(spec, toLocalDate(stamps.get(spec.key())), now));
        }

        // 2. Was each signal measured, and did the answer vary? This is the check that has
        //    actually caught bugs - all three of bug #9, B-060 and B-074 look normal on screen.
        List<ScreeningCoverageEntity> coverage =
                quietly("dataHealth.coverage", List::of, screeningCoverageService::latest);
        if (coverage.isEmpty()) {
            notChecked.add("Signal coverage and spread - no screening run has recorded a coverage "
                    + "vector yet, so nothing could be checked for the constant-value bug that "
                    + "has hit this app three times.");
        }
        for (ScreeningCoverageEntity c : coverage) {
            findings.add(DataHealth.coverage(c.getSignalName(),
                    zero(c.getMeasured()), zero(c.getAttempted()), zero(c.getNotApplicable()),
                    c.getCoveragePercent(), c.getStdDev(), c.getCollapsed(), c.getScreeningDate()));
        }

        // 2b. Is the insider feed still delivering at all? Coverage alone cannot answer this:
        //     a dead feed and a scoring bug both read 0%, and for four months the wrong one of
        //     the two was assumed (B-089).
        LocalDate newestPit = quietly("dataHealth.pitFeed", () -> null,
                insiderDisclosureRepository::findNewestPitTransactionDate);
        findings.add(DataHealth.pitFeedFreshness(newestPit, now.toLocalDate()));

        // 2c. Is the hand-kept theme map still current? It has no feed, no job and no writer, so
        //     it ages silently - and it ages in the flattering direction, because coverage is
        //     counted against its own list (SPEC 51.9). Classpath read, no repository.
        findings.add(DataHealth.themeMapVintage(
                com.example.trading.universe.theme.UniverseThemes.reviewedOn(), now.toLocalDate()));

        // 3. Do the multi-year lenses have accounts to read, and is the queue still moving?
        var depth = quietly("dataHealth.historyDepth", () -> null,
                fundamentalsBackfillService::coverage);
        if (depth == null) {
            notChecked.add("How many years of accounts the universe has - the depth histogram "
                    + "could not be built, so the multi-year sections could not be explained.");
        } else {
            findings.add(DataHealth.historyDepth(
                    depth.getUniverseSize(), depth.getAtLeast4Years(), depth.getMedianYears()));
            LocalDateTime attempt = quietly("dataHealth.backfillAttempt", () -> null,
                    fundamentalsBackfillStatusRepository::findLastAttemptAt);
            findings.add(DataHealth.backfillProgress(depth.getPendingSymbols(),
                    attempt == null ? null : attempt.toLocalDate(), now));
        }

        // The limits, stated next to the all-clear so it can never be read as a guarantee.
        notChecked.add("Whether a stored figure matches the company's own filing. Nothing here "
                + "reads NSE - only opening one stock page beside one annual report does that, "
                + "and it is worth doing once a quarter on a holding you care about.");
        notChecked.add("Whether a scoring rule is right. These checks ask whether a number was "
                + "measured and whether it varies across stocks, never whether it is the correct "
                + "number.");
        notChecked.add("Whether the theme map is COMPLETE. Its review date is checked above, but "
                + "nothing here can tell you a business the map has never named - no feed "
                + "publishes that list, which is why the map is hand-kept. The Candidates section "
                + "on the Themes screen is a partial answer and says how partial.");
        notChecked.add("The emails, the AI sections and anything outside a screening run.");

        return new DashboardDto.DataHealthResponse(
                now.format(ISO_SECONDS),
                now.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL,
                        java.util.Locale.ENGLISH) + " " + now.toLocalDate(),
                summarise(findings),
                findings,
                notChecked,
                "Every check is listed, including the ones that passed. An empty list of "
                + "problems would be indistinguishable from a list of checks that never ran.");
    }

    private static DashboardDto.DataHealthSummary summarise(List<DataHealth.Finding> findings) {
        int problems = 0, watch = 0, known = 0, ok = 0;
        for (DataHealth.Finding f : findings) {
            switch (f.severity()) {
                case PROBLEM -> problems++;
                case WATCH -> watch++;
                case KNOWN -> known++;
                case OK -> ok++;
            }
        }
        return new DashboardDto.DataHealthSummary(findings.size(), problems, watch, known, ok);
    }

    /**
     * The freshness map carries ISO strings because its values are a mix of LocalDate and
     * LocalDateTime. Both start with the date, so the first ten characters are the day either
     * way - and a value we cannot parse becomes null, never today.
     */
    private static LocalDate toLocalDate(String iso) {
        if (iso == null || iso.length() < 10) return null;
        try {
            return LocalDate.parse(iso.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static int zero(Integer v) {
        return v == null ? 0 : v;
    }

    /**
     * Replace a declared schedule with the cron the job is actually configured with (B-087).
     *
     * <p>Several schedulers read their cron from a property with an inline fallback, e.g.
     * {@code @Scheduled(cron = "${holdings.scheduler.analysis-cron:0 30 10 * * MON-FRI}")}. The
     * literal is the fallback; application.yml sets 15:15. Reading the annotation gave the wrong
     * time and the screen then reported three healthy tables as a session behind.
     */
    private DataHealth.ScheduleSpec resolveCron(DataHealth.ScheduleSpec spec) {
        if (spec.cronProperty() == null) return spec;
        String cron = quietly("dataHealth.cron." + spec.key(), () -> null,
                () -> environment.getProperty(spec.cronProperty()));
        return DataHealth.resolve(spec, cron);
    }

    // ----------------------------------------------------------------- summary

    public DashboardDto.SummaryResponse summary() {
        List<HoldingsEntity> active = quietly("holdings", List::of, holdingsRepository::findActive);

        DiversificationDto.RiskResponse risk =
                quietly("risk", () -> null, diversificationService::computeRisk);
        AllocationDto.DriftResponse drift =
                quietly("drift", () -> null, allocationService::computeDrift);
        List<HoldingsDecayService.DecayAlert> decay =
                quietly("decay", List::of, holdingsDecayService::detectDecayForAllHoldings);

        return new DashboardDto.SummaryResponse(
                portfolioKpis(active),
                riskHeadline(risk),
                accuracyHeadline(),
                attention(active, decay, drift, risk),
                freshness(active));
    }

    private DashboardDto.PortfolioKpis portfolioKpis(List<HoldingsEntity> active) {
        double invested = active.stream().mapToDouble(HoldingsEntity::getInvestedValue).sum();
        double value = active.stream().mapToDouble(HoldingsEntity::getCurrentValue).sum();
        double pnl = active.stream().mapToDouble(HoldingsEntity::getPnl).sum();

        // getDayChangeValue(), NOT getDayChange(): the stored column is per share, so summing it
        // across a portfolio produces a number in no unit at all. It read -Rs 52.23 against a real
        // +Rs 4,607.90 on 2026-09-17 - wrong sign, 89x the magnitude (B-120). A holding with no
        // previous close returns null and is left OUT of the sum rather than contributing zero.
        double dayChange = active.stream()
                .map(HoldingsEntity::getDayChangeValue)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();

        long winners = active.stream().filter(h -> h.getPnl() > 0).count();
        long losers = active.stream().filter(h -> h.getPnl() < 0).count();

        // Null rather than 0 when there is nothing invested: a 0.0% return on an empty
        // portfolio is a statement we have no basis to make (SPEC section 21 rule 7).
        Double pnlPercent = invested > 0 ? (pnl / invested) * 100.0 : null;
        double previousValue = value - dayChange;
        Double dayChangePercent = previousValue > 0 ? (dayChange / previousValue) * 100.0 : null;

        return new DashboardDto.PortfolioKpis(invested, value, pnl, pnlPercent,
                dayChange, dayChangePercent, active.size(), winners, losers);
    }

    private DashboardDto.RiskHeadline riskHeadline(DiversificationDto.RiskResponse risk) {
        if (risk == null) {
            return null;
        }
        DiversificationDto.ConcentrationCheck sector = risk.sectorConcentration();
        DiversificationDto.ConcentrationCheck stock = risk.stockConcentration();
        return new DashboardDto.RiskHeadline(
                risk.hhi() == null ? null : risk.hhi().value(),
                risk.hhi() == null ? null : risk.hhi().classification(),
                sector == null ? null : sector.topBucket(),
                sector == null ? null : sector.topWeight(),
                stock == null ? null : stock.topBucket(),
                stock == null ? null : stock.topWeight(),
                risk.alerts() == null ? 0 : risk.alerts().size());
    }

    /**
     * Best-populated accuracy cell at the headline horizon. Picks the source with the most
     * scored picks rather than a fixed one, because which engine has usable history changes
     * over time - and reports enoughData=false instead of hiding, so the UI can say "not
     * enough history yet" rather than silently omitting the panel.
     */
    private DashboardDto.AccuracyHeadline accuracyHeadline() {
        List<RecommendationAccuracyService.SourceHorizonStats> all =
                quietly("accuracy", List::of, accuracyService::summarize);

        return all.stream()
                .filter(s -> s.getHorizonDays() == HEADLINE_HORIZON_DAYS)
                .max(Comparator.comparingInt(RecommendationAccuracyService.SourceHorizonStats::getSampleSize))
                .map(s -> new DashboardDto.AccuracyHeadline(
                        s.getSource(),
                        s.getHorizonDays(),
                        s.getSampleSize(),
                        s.getSampleSize() >= MIN_SAMPLE_FOR_DISPLAY,
                        s.getHitRatePercent(),
                        s.getMeanReturnPercent(),
                        s.getMeanExcessReturnPercent(),
                        s.getInformationCoefficient()))
                .orElse(null);
    }

    /**
     * Merges the four "something is off" sources into one triage list, worst first, so the
     * landing page answers "is anything wrong?" without the user visiting four screens.
     */
    // Package-private and static so the composition rules can be pinned directly. It reads only
    // its arguments, and what it does NOT raise is as load-bearing as what it does (B-121).
    static List<DashboardDto.AttentionItem> attention(
            List<HoldingsEntity> active,
            List<HoldingsDecayService.DecayAlert> decay,
            AllocationDto.DriftResponse drift,
            DiversificationDto.RiskResponse risk) {

        List<DashboardDto.AttentionItem> items = new ArrayList<>();

        for (HoldingsEntity h : active) {
            String rec = h.getRecommendation();
            if (!"SELL".equals(rec) && !"STRONG_SELL".equals(rec)) {
                continue;
            }
            items.add(new DashboardDto.AttentionItem(
                    "EXIT_SIGNAL",
                    "STRONG_SELL".equals(rec) ? "URGENT" : "WARNING",
                    h.getSymbol(),
                    "Exit signal: " + rec,
                    h.getAnalysisNotes(),
                    h.getPnlPercent()));
        }

        for (HoldingsDecayService.DecayAlert d : decay) {
            // INTACT means the thesis still holds; NO_DATA/STALE are gaps in our own
            // coverage, not findings about the stock - neither belongs in a triage list.
            HoldingsDecayService.Verdict v = d.getVerdict();
            if (v == HoldingsDecayService.Verdict.INTACT
                    || v == HoldingsDecayService.Verdict.NO_DATA
                    || v == HoldingsDecayService.Verdict.STALE) {
                continue;
            }
            items.add(new DashboardDto.AttentionItem(
                    "THESIS_DECAY",
                    v == HoldingsDecayService.Verdict.BROKEN ? "URGENT" : "WARNING",
                    d.getSymbol(),
                    "Thesis drift: " + v,
                    d.getReason(),
                    d.getPnlPercent()));
        }

        if (drift != null && drift.buckets() != null) {
            long over = drift.buckets().stream()
                    .filter(b -> AllocationService.ALERT_OVER_TOLERANCE.equals(b.alertLevel()))
                    .count();

            if (!drift.targetsStated()) {
                // A default is not a statement (Gotcha 68). These targets were seeded, never
                // chosen, so every bucket breaches and TEN warnings landed here every day - half
                // the attention list, against weights the investor has never seen. Alerts that
                // fire daily and can never clear are what train a reader to skip the section
                // that matters (Gotcha 132), and this is the app's main action surface.
                //
                // The information is not discarded: one row, lowest severity, saying exactly
                // what is unknown and what would make it meaningful. The drift table itself is
                // untouched and still renders in full on My Portfolio.
                if (over > 0) {
                    items.add(new DashboardDto.AttentionItem(
                            "ALLOCATION_TARGETS_UNSET",
                            "INFO",
                            null,
                            "Your allocation targets have never been set",
                            String.format("%d of your %d allocation buckets sit outside tolerance, but against "
                                    + "the targets seeded when the app first started - not ones you chose. Until "
                                    + "you set your own, this comparison says nothing about your portfolio. "
                                    + "Set them on My Portfolio.",
                                    over, drift.buckets().size()),
                            null));
                }
            } else {
                for (AllocationDto.DriftBucket b : drift.buckets()) {
                    if (!AllocationService.ALERT_OVER_TOLERANCE.equals(b.alertLevel())) {
                        continue;
                    }
                    items.add(new DashboardDto.AttentionItem(
                            "ALLOCATION_DRIFT",
                            "WARNING",
                            null,
                            "Allocation drift: " + b.bucketKey(),
                            String.format("%s is %.1f%% of your portfolio against a %.1f%% target (%+.1f pp).",
                                    b.bucketKey(), b.actualWeight(), b.targetWeight(), b.driftPp()),
                            null));
                }
            }
        }

        if (risk != null && risk.alerts() != null) {
            for (DiversificationDto.Alert a : risk.alerts()) {
                items.add(new DashboardDto.AttentionItem(
                        "CONCENTRATION_RISK",
                        a.severity(),
                        null,
                        a.category(),
                        a.message(),
                        null));
            }
        }

        items.sort(Comparator.comparingInt(i -> severityRank(i.severity())));
        return items;
    }

    private static int severityRank(String severity) {
        if (severity == null) {
            return 3;
        }
        return switch (severity.toUpperCase()) {
            case "URGENT", "CRITICAL", "HIGH" -> 0;
            case "WARNING", "MEDIUM" -> 1;
            default -> 2;
        };
    }

    // ---------------------------------------------------------------- screener

    /**
     * The latest screening run that actually has rows.
     *
     * <p>Deliberately not "today's run": the multibagger screening fires at 14:00, and the
     * two existing endpoints are both unusable for a UI before then - one serves a cache
     * emptied by the daily restart, the other defaults to today. Walking back through
     * known screening dates means the screener screen always shows real scores, stamped
     * with the date they came from so a stale run is visible rather than implied.
     */
    public DashboardDto.ScreenerResponse screener() {
        List<LocalDate> dates = quietly("screeningDates", List::of,
                multibaggerScoreRepository::findScreeningDates); // ORDER BY screeningDate DESC

        // Bounded walk-back: if the last few runs are all empty something is wrong upstream,
        // and returning "no data" is more honest than scanning the whole table.
        for (LocalDate date : dates.stream().limit(10).toList()) {
            List<MultibaggerScoreEntity> rows = quietly("screening:" + date, List::of,
                    () -> multibaggerScoreRepository.findByScreeningDateOrderByCompositeScoreDesc(date));
            if (!rows.isEmpty()) {
                Map<String, WatchlistItemView> tracked = trackedVerdicts();
                Map<String, Integer> years = yearsOfAccounts();
                ScoreChangeContext change = scoreChangeContext(dates, date, rows);
                // One bulk macro call for the whole run (SPEC 48.4), not one per row.
                Map<String, com.example.trading.macro.MacroExposureService.Reading> macro =
                        quietly("screening:macro", Map::of, () -> macroExposureService.forSymbols(
                                rows.stream().map(MultibaggerScoreEntity::getSymbol).toList()));
                // The same, for who else is quoting a target on these stocks (SPEC 49.15). One
                // query for the run: per row it would be ~1,100 lookups, because every symbol
                // resolves through up to four exchange spellings (Gotcha 84). Measured on the
                // live universe, 213 of 274 screened stocks carry a live target, so this column
                // discriminates rather than reading empty (B-113's rule).
                Map<String, com.example.trading.analyst.AnalystTargetViewService.Coverage> analyst =
                        quietly("screening:analyst", Map::of, () -> analystTargetViewService.forSymbols(
                                rows.stream().map(MultibaggerScoreEntity::getSymbol).toList()));
                return new DashboardDto.ScreenerResponse(date, rows.size(),
                        rows.stream()
                                .map(r -> (Object) withBuyTiming(r, tracked.get(r.getSymbol()), years, change,
                                        macro.get(r.getSymbol()), analyst.get(r.getSymbol())))
                                .toList());
            }
        }
        return new DashboardDto.ScreenerResponse(null, 0, List.of());
    }

    /**
     * The screening row, plus the "is it still a good time to buy?" verdict (SPEC 12.11).
     *
     * <p>Returned as a map rather than a wrapper record on purpose: the screener and discovery
     * tables read the entity's fields directly by name, and nesting the entity would rename every
     * one of them. This keeps `r.compositeScore` working and adds two keys beside it.
     *
     * <p>Computed here rather than stored because it is a pure function of the row - persisting it
     * would create a second copy that can disagree with the row it describes after a re-screen.
     * The whole calculation is arithmetic on fields already loaded: no Kite call, no NSE call, so
     * the endpoint stays DB-only and safe on a page load (Gotcha 17, 39).
     */
    /**
     * Years of annual accounts on file per symbol, for the compounding lens (SPEC 41).
     *
     * <p>One grouped query for the whole universe. Empty on any failure: the lens then reports
     * the depth as unknown rather than as zero years, which would read as "no accounts at all"
     * for every stock (Gotcha 21).
     */
    private Map<String, Integer> yearsOfAccounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            for (Object[] row : annualFundamentalsRepository.countYearsBySymbol()) {
                if (row.length >= 2 && row[0] != null && row[1] instanceof Number n) {
                    out.put(String.valueOf(row[0]), n.intValue());
                }
            }
        } catch (Exception ex) {
            log.warn("Compounding lens: annual-history depth unavailable, so every stock will "
                    + "show its track-record length as unknown rather than as measured: {}", ex.getMessage());
        }
        return out;
    }

    /**
     * What the screener needs to say how a score has moved: the run ~30 days before this one, and
     * the universe's own median move between the two (B-064). Nothing here is a verdict.
     *
     * @param priorScores   symbol -> composite on the prior run; empty when there is no usable run
     * @param priorDate     the prior run's date, or null
     * @param universeShift median (now − then) over symbols on both runs; null below 30 pairs
     */
    record ScoreChangeContext(Map<String, Integer> priorScores, LocalDate priorDate, Integer universeShift) {
        static ScoreChangeContext none() {
            return new ScoreChangeContext(Map.of(), null, null);
        }
    }

    /** How far from the 30-day target a prior run may sit and still be used. Same as the decay service. */
    private static final int SCORE_CHANGE_WINDOW_DAYS = 30;
    private static final int SCORE_CHANGE_TOLERANCE_DAYS = 10;

    /**
     * Picks the screening run nearest to 30 days before {@code date} (within ±10 days, and strictly
     * earlier) and loads it once for the whole page - one extra query, DB-only.
     *
     * <p>The universe shift is the same rule the holdings decay verdict uses
     * ({@link HoldingsDecayService#medianShift}): between 20 and 27 Aug 2026 the median composite
     * fell 74 -> 65.5 and every stock read as decaying on its own history. A raw delta is still
     * reported; the relative one is what the reader should act on, and it is null - not zero -
     * when fewer than 30 symbols pair up.
     */
    private ScoreChangeContext scoreChangeContext(List<LocalDate> dates, LocalDate date,
                                                  List<MultibaggerScoreEntity> current) {
        if (date == null) return ScoreChangeContext.none();
        LocalDate target = date.minusDays(SCORE_CHANGE_WINDOW_DAYS);
        LocalDate best = null;
        long bestGap = Long.MAX_VALUE;
        for (LocalDate d : dates) {
            if (!d.isBefore(date)) continue;
            long gap = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(d, target));
            if (gap <= SCORE_CHANGE_TOLERANCE_DAYS && gap < bestGap) {
                best = d;
                bestGap = gap;
            }
        }
        if (best == null) return ScoreChangeContext.none();
        final LocalDate priorDate = best;
        List<MultibaggerScoreEntity> prior = quietly("screening:" + priorDate, List::of,
                () -> multibaggerScoreRepository.findByScreeningDateOrderByCompositeScoreDesc(priorDate));
        if (prior.isEmpty()) return ScoreChangeContext.none();

        Map<String, Integer> then = new LinkedHashMap<>();
        for (MultibaggerScoreEntity e : prior) {
            if (e.getSymbol() != null) then.put(e.getSymbol(), e.getCompositeScore());
        }
        Map<String, Integer> now = new LinkedHashMap<>();
        for (MultibaggerScoreEntity e : current) {
            if (e.getSymbol() != null) now.put(e.getSymbol(), e.getCompositeScore());
        }
        return new ScoreChangeContext(then, priorDate, HoldingsDecayService.medianShift(then, now));
    }

    /**
     * The row's sector in the one vocabulary every screen shares (B-096, B-098). Null - rendered
     * "not measured" - when neither the row nor the sector table classifies the stock; never a
     * bucket called "Other".
     */
    private static String sectorOf(MultibaggerScoreEntity e) {
        String s = com.example.trading.portfolio.SectorMapping.resolve(e.getSymbol(), e.getIndustry());
        return com.example.trading.portfolio.SectorMapping.isUnknown(s) ? null : s;
    }

    private static void putScoreChange(Map<String, Object> m, MultibaggerScoreEntity e, ScoreChangeContext ctx) {
        Integer then = ctx == null || ctx.priorScores() == null ? null : ctx.priorScores().get(e.getSymbol());
        Integer delta = then == null ? null : e.getCompositeScore() - then;
        Integer shift = ctx == null ? null : ctx.universeShift();
        m.put("scoreDelta30d", delta);
        m.put("scoreDelta30dFrom", then == null ? null : ctx.priorDate());
        m.put("universeShift30d", shift);
        m.put("scoreDelta30dRelative", delta != null && shift != null ? delta - shift : null);
    }

    /**
     * The compounding lens for one stock (SPEC 41), for the stock page.
     *
     * <p>Resolves through {@link com.example.trading.holdings.SymbolVariants} because screening
     * history is keyed on the NSE symbol while 22 of 33 holdings are BSE-prefixed, and the
     * composite describes the company rather than the listing venue (Gotcha 84). The symbol that
     * answered is returned so a reading can be traced rather than assumed.
     *
     * <p>DB-only: one indexed lookup plus one grouped count. Safe on a page load.
     *
     * @return null when the stock has never been screened - the caller renders "never screened",
     *         never a zero or a failing verdict
     */
    public Map<String, Object> compounding(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;

        CompoundingLensService.Reading reading =
                quietly("compounding:" + symbol, () -> null, () -> compoundingLensService.forSymbol(symbol));
        if (reading == null || reading.result() == null) return null;

        Map<String, Object> m = new LinkedHashMap<>();
        putCompounding(m, reading.result());
        m.put("symbol", reading.symbolAnswered());
        m.put("resolvedFrom", reading.resolvedAcrossExchange() ? symbol : null);
        m.put("screeningDate", reading.screeningDate());
        return m;
    }

    /**
     * Attaches the compounding-quality lens (SPEC 41) to a screening row.
     *
     * <p>Computed on read rather than stored, for the same reason as the buy-timing verdict: it is
     * a pure function of fields already loaded, and a persisted copy could disagree with the row it
     * describes after a re-screen. No Kite call and no NSE call, so the endpoint stays DB-only and
     * safe on a page load (Gotcha 17, 39).
     *
     * <p>It contributes <b>nothing</b> to {@code compositeScore}. It is a lens, not a bonus
     * (Gotcha 30, SPEC 20 rule 9).
     *
     * <p>Shaping only - the row lookup, the symbol resolution and the depth count all live in
     * {@link CompoundingLensService}, which the portfolio uses too. Two lookups would be two
     * chances for one stock to read Compounder here and "never screened" there (Gotcha 85).
     */
    private void putCompounding(Map<String, Object> m, CompoundingQuality.Result r) {
        m.put("compounding", r.verdict().name());
        m.put("compoundingReason", r.reason());
        m.put("compoundingPassed", r.passed());
        m.put("compoundingApplicable", r.applicable());
        m.put("compoundingYearsOfAccounts", r.yearsOfAccounts());
        m.put("compoundingCapexContext", r.capexContext());
        m.put("compoundingGates", r.gates().stream().map(g -> Map.of(
                "key", g.key(),
                "question", g.question(),
                "status", g.status().name(),
                "detail", g.detail())).toList());
    }

    /**
     * The macro reading, in the same five field names every other surface uses (SPEC 48.4).
     *
     * <p>A null reading writes nothing, so the cell renders "not measured" - a gap in the exposure
     * map, never a claim that no event affects this business. The event count is left absent rather
     * than 0 for the same reason (Gotcha 21).
     */
    private void putMacro(Map<String, Object> m, com.example.trading.macro.MacroExposureService.Reading r) {
        if (r == null) {
            return;
        }
        var result = r.result();
        m.put("macroExposure", result.verdict().name());
        m.put("macroExposureStrength", result.strength() == null ? null : result.strength().name());
        m.put("macroExposureReasons", result.reasons().stream()
                .map(com.example.trading.macro.MacroExposureRead.Reason::text).toList());
        m.put("macroExposureEvents", result.reasons().isEmpty() ? null : result.reasons().size());
        m.put("macroExposureFrom", r.symbolAnswered());
    }

    /**
     * Who else is quoting a price target on this stock (SPEC 49.15).
     *
     * <p>The same fifteen keys {@code HoldingsViewDecorator.applyAnalyst} writes onto a holdings
     * row, so the screener, discovery, the watchlist and the portfolio all feed one renderer and
     * cannot count brokerages differently (Gotcha 85).
     *
     * <p><b>A null coverage writes nothing, deliberately.</b> That is what keeps three states
     * apart on the wire. Absent keys mean the lookup did not run and the cell draws the unmeasured
     * marker; a {@code Coverage} whose {@code houses()} is 0 means the ledger was searched and
     * nothing is running, which is a real measurement and reads as "None on file". Collapsing
     * those two lets a failed query render as "no brokerage covers this stock" - the one claim
     * this ledger can never support (SPEC 49.7, Gotcha 44).
     *
     * <p>Contributes zero points to any score, here as everywhere else in SPEC 49.
     */
    private void putAnalyst(Map<String, Object> m,
                            com.example.trading.analyst.AnalystTargetViewService.Coverage c) {
        if (c == null) {
            return;
        }
        // One writer for the fifteen keys, shared with the Themes page (SPEC 51.8): a rename
        // that misses one call site blanks the column on exactly that screen (B-099).
        m.putAll(com.example.trading.analyst.AnalystTargetViewService.wireFields(c));
    }

    /**
     * Which government-funded themes name this business (SPEC 51.5).
     *
     * <p>The same four keys {@code HoldingsViewDecorator.applyTheme} writes onto a holdings row,
     * so the screener, discovery, the watchlist and the portfolio all feed one renderer and cannot
     * describe a theme differently (Gotcha 85).
     *
     * <p><b>No batch parameter and no I/O</b>, unlike its macro and analyst neighbours: the theme
     * map is a static in-memory table, so a per-row lookup costs a hash probe. And {@code screened}
     * is not written here at all - a row in {@code multibagger_scores} is screened by construction,
     * so the flag would be the constant {@code true} and a constant on screen is the shape of a
     * measurement nobody took (B-060).
     *
     * <p><b>An untagged business writes the empty list, not nothing.</b> That is the distinction
     * the whole feature turns on: absent keys would draw the unmeasured marker, but the map was
     * consulted and named no theme, which is a finding. Two different things, two different cells
     * (Gotcha 121).
     *
     * <p>Contributes zero points to any score.
     */
    private void putTheme(Map<String, Object> m, String symbol) {
        var tags = com.example.trading.universe.theme.UniverseThemes.tagsFor(symbol);
        m.put("themes", tags.stream().map(t -> t.theme().name()).distinct().toList());
        m.put("themeLabels", tags.stream().map(t -> t.theme().label()).distinct().toList());
        m.put("themePolicies", tags.stream().map(
                com.example.trading.universe.theme.UniverseThemes.Tag::policy).distinct().toList());
        m.put("themeRoles", tags.stream().map(
                com.example.trading.universe.theme.UniverseThemes.Tag::role).toList());
    }

    /** Watchlist rows by symbol. Empty on any failure - the screener must still render. */
    private Map<String, WatchlistItemView> trackedVerdicts() {
        Map<String, WatchlistItemView> map = new LinkedHashMap<>();
        try {
            for (WatchlistItemView v : watchlistTrackingService.buildView(false)) {
                if (v.symbol() != null && v.verdict() != null) map.put(v.symbol(), v);
            }
        } catch (Exception ex) {
            log.warn("Screener buy-timing: watchlist verdicts unavailable, falling back to the "
                    + "screening-row verdict - a tracked stock may read differently here than on "
                    + "the watchlist page: {}", ex.getMessage());
        }
        return map;
    }

    private Map<String, Object> withBuyTiming(MultibaggerScoreEntity e, WatchlistItemView tracked,
                                              Map<String, Integer> yearsOfAccounts) {
        return withBuyTiming(e, tracked, yearsOfAccounts, ScoreChangeContext.none(), null, null);
    }

    private Map<String, Object> withBuyTiming(MultibaggerScoreEntity e, WatchlistItemView tracked,
                                              Map<String, Integer> yearsOfAccounts,
                                              ScoreChangeContext change) {
        return withBuyTiming(e, tracked, yearsOfAccounts, change, null, null);
    }

    private Map<String, Object> withBuyTiming(MultibaggerScoreEntity e, WatchlistItemView tracked,
                                              Map<String, Integer> yearsOfAccounts,
                                              ScoreChangeContext change,
                                              com.example.trading.macro.MacroExposureService.Reading macro,
                                              com.example.trading.analyst.AnalystTargetViewService.Coverage analyst) {
        ScreenerTimingVerdict.Result r = ScreenerTimingVerdict.evaluate(new ScreenerTimingVerdict.Input(
                e.getCompositeScore(),
                e.getWeeklyRsi(),
                e.getPriceVs52WeekHigh(),
                e.getPriceVs52WeekLow(),
                e.getWeeklyEmaSlope(),
                e.getForensicFlags(),
                e.getFinancialQualityVerdict(),
                e.getLiquidityTier(),
                e.getDcfVerdict()));

        Map<String, Object> m = objectMapper.convertValue(e, new TypeReference<Map<String, Object>>() {
        });

        putCompounding(m, compoundingLensService.evaluate(e, yearsOfAccounts));
        putMacro(m, macro);
        putAnalyst(m, analyst);
        putTheme(m, e.getSymbol());

        // Sector in the shared vocabulary, where the price sits in its 52-week range, and how the
        // score has moved against the universe (SPEC §12.5, 2026-09-09). All derived from fields
        // already loaded plus one prior-run query made once per page: still DB-only.
        m.put("sector", sectorOf(e));
        m.put("rangePosition52w", r.rangePosition52w());
        putScoreChange(m, e, change);

        // A stock the investor already tracks gets the watchlist's answer, not this one. Both are
        // honest and they disagree because they measure different things: this row carries WEEKLY
        // RSI and the distance from the 52-week high, from the last screening; the watchlist row
        // carries live DAILY RSI-14 and the distance from EMA-50. GALAXYSURF read "good entry"
        // here and "wait for a dip" there on 2026-08-27 - 13% below its 12-month high and 11%
        // above its 50-day average, both true. Two answers to one question in one vocabulary is a
        // defect regardless of which is right, and the better-informed one wins.
        if (tracked != null) {
            m.put("buyTiming", tracked.verdict().name());
            m.put("buyTimingReason", tracked.verdictReason());
            m.put("buyTimingSource", "WATCHLIST");
            m.put("buyTimingNotMeasured", List.of());
            // The entry level follows whichever verdict is being shown, so the two columns in one
            // row can never tell the reader to wait and to buy now at the same time (SPEC 12.12).
            putEntry(m, tracked.verdict(), e);
            return m;
        }

        m.put("buyTiming", r.verdict().name());
        m.put("buyTimingReason", r.reason());
        m.put("buyTimingSource", "SCREENING");
        m.put("buyTimingNotMeasured", r.notMeasured());
        putEntry(m, r.verdict(), e);
        return m;
    }

    private static void putEntry(Map<String, Object> m, BuyTimingVerdict.Verdict verdict,
                                 MultibaggerScoreEntity e) {
        SuggestedEntry.Result entry = SuggestedEntry.compute(
                verdict, e.getCurrentPrice(), e.getSupport20d(), e.getAtr14(), e.getEma50());
        m.put("suggestedEntryPrice", entry.price());
        m.put("suggestedEntryBasis", entry.basis());
        m.put("suggestedEntryReason", entry.reason());
        m.put("suggestedEntryRungs", entry.rungs());
        m.put("suggestedEntryFallback", entry.fallback());
    }

    // ------------------------------------------------------------------ series

    /** Per-stock history for the drill-down charts. Ascending by date. */
    public List<DashboardDto.HoldingSeriesPoint> holdingSeries(String symbol, int days) {
        LocalDate from = LocalDate.now().minusDays(days);
        return holdingsHistoryRepository
                .findBySymbolAndRecordDateBetween(symbol, from, LocalDate.now())
                .stream()
                .sorted(Comparator.comparing(HoldingsHistoryEntity::getRecordDate))
                .map(DashboardService::toPoint)
                .toList();
    }

    /** Portfolio equity curve. Aggregated in SQL so 3 years of rows never reach the JVM. */
    public List<DashboardDto.PortfolioSeriesPoint> portfolioSeries(int days) {
        LocalDate from = LocalDate.now().minusDays(days);
        List<DashboardDto.PortfolioSeriesPoint> out = new ArrayList<>();

        for (Object[] row : holdingsHistoryRepository.findPortfolioSeries(from)) {
            LocalDate date = (LocalDate) row[0];
            double invested = toDouble(row[1]);
            double value = toDouble(row[2]);
            double pnl = toDouble(row[3]);
            Double pnlPct = invested > 0 ? (pnl / invested) * 100.0 : null;
            out.add(new DashboardDto.PortfolioSeriesPoint(date, invested, value, pnl, pnlPct));
        }
        return out;
    }

    /**
     * Every symbol series in one query, for the holdings-table sparklines. Returning a map
     * keyed by symbol lets the table render N sparklines from a single request instead of
     * firing N requests (SPEC section 27.3).
     */
    public Map<String, List<DashboardDto.HoldingSeriesPoint>> holdingSeriesMatrix(int days) {
        LocalDate from = LocalDate.now().minusDays(days);
        Map<String, List<DashboardDto.HoldingSeriesPoint>> out = new LinkedHashMap<>();

        for (HoldingsHistoryEntity h : holdingsHistoryRepository
                .findByRecordDateGreaterThanEqualOrderByRecordDateAsc(from)) {
            out.computeIfAbsent(h.getSymbol(), k -> new ArrayList<>()).add(toPoint(h));
        }
        return out;
    }

    private static DashboardDto.HoldingSeriesPoint toPoint(HoldingsHistoryEntity h) {
        return new DashboardDto.HoldingSeriesPoint(
                h.getRecordDate(), h.getClosePrice(), h.getPnl(), h.getPnlPercent(),
                h.getTechnicalScore(), h.getOverallScore());
    }

    // ----------------------------------------------------------------- helpers

    /**
     * JPA SUM() widens to BigDecimal on some dialects and Double on others, and is null for
     * an empty group - so never cast the projection directly.
     */
    private static double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    /**
     * Runs one section data fetch, falling back rather than failing the whole page.
     * SPEC section 18 requires sources to degrade independently; without this, one expired
     * Kite session or one slow query blanks the entire dashboard.
     */
    private <T> T quietly(String section, Supplier<T> fallback, Supplier<T> work) {
        try {
            return work.get();
        } catch (Exception e) {
            log.warn("Dashboard section '{}' failed, serving fallback: {}", section, e.getMessage());
            return fallback.get();
        }
    }
}
