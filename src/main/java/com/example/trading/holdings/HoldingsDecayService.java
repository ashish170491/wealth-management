package com.example.trading.holdings;

import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects <strong>thesis drift on owned holdings</strong> — the 30/60-day
 * drop in a holding's Multibagger composite score. Sibling of the purchase-
 * drift tracker in SPEC §6 (which compares today vs. purchase date); this
 * compares today vs. a recent window so drift surfaces even for long-dated
 * holdings whose purchase score is no longer the right baseline.
 *
 * Data source: {@link MultibaggerScoreRepository#findTrend} — one score row
 * per screening date from the daily 14:00 IST screening loop. Holdings
 * outside the screening universe return {@link Verdict#NO_DATA} rather than
 * a fabricated reading.
 *
 * <h2>Decay is relative to the universe (B-064)</h2>
 * The composite is not a fixed scale: a scoring change (B-018/B-019, the Aug-2026 signal
 * additions) or a broad market fall moves <em>every</em> stock's score on the same day. Measured
 * 2026-08-20 -> 08-27 the universe median fell 74 -> 65.5, and a stock whose rank never moved read
 * as {@code DECAYING} on its own history alone. So the verdict is classified on the stock's move
 * <strong>minus the paired median move of every symbol screened on both dates</strong>
 * ({@code relativeDelta30d}). The raw delta is still reported; when fewer than
 * {@value #MIN_PAIRED_SYMBOLS} symbols are present on both dates the universe shift is
 * "not measured" and the raw delta is used, with the reason saying so.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HoldingsDecayService {

    private final HoldingsRepository holdingsRepository;
    private final MultibaggerScoreRepository multibaggerScoreRepository;

    /** Look-back window for the fast signal; tuned to ~1 screening month. */
    private static final int SHORT_WINDOW_DAYS = 30;
    /** Look-back window for the slow signal — catches drift that a single 30d window may miss. */
    private static final int LONG_WINDOW_DAYS = 60;
    /** If the newest score in history is older than this, treat as stale (likely removed from universe). */
    private static final int STALE_THRESHOLD_DAYS = 14;
    /** Below this many symbols screened on both dates the universe shift is not measured (B-064). */
    static final int MIN_PAIRED_SYMBOLS = 30;
    /** A past run's score map is immutable; today's may still grow, hence a short TTL. */
    private static final long RUN_CACHE_TTL_MS = 15 * 60 * 1000L;

    private final Map<LocalDate, CachedRun> runCache = new ConcurrentHashMap<>();
    private record CachedRun(Map<String, Integer> scores, long loadedAt) {}

    public enum Verdict { INTACT, WATCH, DECAYING, BROKEN, NO_DATA, STALE }

    /** Run decay detection across all current holdings; sorted worst-first. */
    public List<DecayAlert> detectDecayForAllHoldings() {
        return detectDecay(holdingsRepository.findActive());
    }

    public List<DecayAlert> detectDecay(List<HoldingsEntity> holdings) {
        List<DecayAlert> alerts = new ArrayList<>();
        LocalDate oldestDateNeeded = LocalDate.now().minusDays(LONG_WINDOW_DAYS + 10);
        for (HoldingsEntity h : holdings) {
            if (h.getSymbol() == null) continue;
            try {
                alerts.add(buildAlert(h, oldestDateNeeded));
            } catch (Exception e) {
                log.debug("Decay detection failed for {}: {}", h.getSymbol(), e.getMessage());
            }
        }
        // Worst first — BROKEN > DECAYING > WATCH > INTACT/STALE/NO_DATA
        alerts.sort((a, b) -> Integer.compare(severity(b.getVerdict()), severity(a.getVerdict())));
        return alerts;
    }

    /**
     * Decay reading for any symbol, owned or not — the watchlist (SPEC §37) reads it for stocks
     * the investor is only considering. DB-only; {@code pnlPercent} may be null.
     */
    public DecayAlert detectDecayForSymbol(String symbol, Double pnlPercent) {
        return buildAlert(symbol, pnlPercent, LocalDate.now().minusDays(LONG_WINDOW_DAYS + 10));
    }

    private DecayAlert buildAlert(HoldingsEntity h, LocalDate fromDate) {
        return buildAlert(h.getSymbol(), h.getPnlPercent(), fromDate);
    }

    private DecayAlert buildAlert(String symbol, Double pnlPercent, LocalDate fromDate) {
        DecayAlert alert = new DecayAlert();
        alert.setSymbol(symbol);
        alert.setPnlPercent(pnlPercent);

        // Screening runs on NSE symbols while most holdings are BSE-prefixed (B-061), so the
        // history is looked up under every exchange variant. Which one answered is recorded on
        // the alert rather than hidden, so a reading can always be traced to its source row.
        List<MultibaggerScoreEntity> history = List.of();
        for (String candidate : SymbolVariants.candidates(symbol)) {
            history = multibaggerScoreRepository.findTrend(candidate, fromDate);
            if (!history.isEmpty()) {
                alert.setResolvedSymbol(candidate);
                break;
            }
        }
        if (history.isEmpty()) {
            alert.setVerdict(Verdict.NO_DATA);
            alert.setReason("Stock not in the screening universe — no composite-score trend available");
            return alert;
        }

        // history is ascending by date (findTrend contract). Newest is last.
        MultibaggerScoreEntity latest = history.get(history.size() - 1);
        alert.setCurrentScore(latest.getCompositeScore());
        alert.setCurrentGrade(latest.getGrade());
        alert.setCurrentDate(latest.getScreeningDate());

        // Stale detection — if the "latest" entry is too old, the stock has likely
        // been dropped from the universe and we should not read decay into a frozen number.
        LocalDate cutoff = LocalDate.now().minusDays(STALE_THRESHOLD_DAYS);
        if (latest.getScreeningDate() != null && latest.getScreeningDate().isBefore(cutoff)) {
            alert.setVerdict(Verdict.STALE);
            alert.setReason(String.format(
                    "Most recent screening score is %d days old — stock may have dropped from the universe",
                    (int) java.time.temporal.ChronoUnit.DAYS.between(latest.getScreeningDate(), LocalDate.now())));
            return alert;
        }

        // Nearest-match lookups against the desired windows
        MultibaggerScoreEntity short30 = findClosest(history, LocalDate.now().minusDays(SHORT_WINDOW_DAYS));
        MultibaggerScoreEntity long60 = findClosest(history, LocalDate.now().minusDays(LONG_WINDOW_DAYS));

        Integer delta30 = short30 != null ? latest.getCompositeScore() - short30.getCompositeScore() : null;
        Integer delta60 = long60 != null ? latest.getCompositeScore() - long60.getCompositeScore() : null;
        alert.setDelta30d(delta30);
        alert.setDelta60d(delta60);
        if (short30 != null) alert.setScoreAt30d(short30.getCompositeScore());
        if (long60 != null) alert.setScoreAt60d(long60.getCompositeScore());

        // B-064: subtract what the whole universe did over the same two runs.
        Integer universe30 = short30 != null ? universeShift(short30.getScreeningDate(), latest.getScreeningDate()) : null;
        Integer universe60 = long60 != null ? universeShift(long60.getScreeningDate(), latest.getScreeningDate()) : null;
        alert.setUniverseDelta30d(universe30);
        alert.setUniverseDelta60d(universe60);
        Integer rel30 = delta30 != null && universe30 != null ? delta30 - universe30 : null;
        Integer rel60 = delta60 != null && universe60 != null ? delta60 - universe60 : null;
        alert.setRelativeDelta30d(rel30);
        alert.setRelativeDelta60d(rel60);
        alert.setUniverseShiftMeasured(universe30 != null || universe60 != null);

        // Grade steps are read on the shift-adjusted 30d-ago score: a universe-wide slide from A+
        // to A is not a grade drop for the stock (grades are bands of the same shifted scale).
        int gradeSteps = 0;
        if (short30 != null) {
            String gradeThen = universe30 != null
                    ? gradeOf(short30.getCompositeScore() + universe30)
                    : short30.getGrade();
            gradeSteps = gradeLevel(gradeThen) - gradeLevel(latest.getGrade());
            alert.setGradeAt30d(short30.getGrade());
        }
        alert.setGradeStepsDropped(gradeSteps);

        // Classify on the relative move where it is measured, otherwise on the raw move.
        Integer c30 = rel30 != null ? rel30 : delta30;
        Integer c60 = rel60 != null ? rel60 : delta60;
        alert.setVerdict(classify(c30, c60, gradeSteps));
        alert.setReason(buildReason(alert));
        return alert;
    }

    /**
     * Median of (score on {@code to} - score on {@code from}) over every symbol screened on
     * <em>both</em> dates - the universe's own move, which a single stock must be read against.
     * Null when fewer than {@link #MIN_PAIRED_SYMBOLS} symbols pair up (a partial run, B-026 style,
     * or a date with no rows): an unmeasured shift is never treated as zero.
     */
    Integer universeShift(LocalDate from, LocalDate to) {
        if (from == null || to == null) return null;
        if (from.equals(to)) return 0;
        return medianShift(runScores(from), runScores(to));
    }

    /**
     * The B-064 rule as a pure function, so the screener's score-change column (SPEC §12.5) reads
     * a stock's move against the same universe shift the holdings verdict does — one rule, two
     * surfaces, no chance of a stock decaying on one screen and holding up on the other.
     *
     * @return the median of (to − from) over symbols present in both maps, or null below
     *         {@value #MIN_PAIRED_SYMBOLS} paired symbols — an unmeasured shift is never zero
     */
    public static Integer medianShift(Map<String, Integer> from, Map<String, Integer> to) {
        if (from == null || to == null) return null;
        List<Integer> deltas = new ArrayList<>();
        for (Map.Entry<String, Integer> e : from.entrySet()) {
            Integer later = to.get(e.getKey());
            if (later != null && e.getValue() != null) deltas.add(later - e.getValue());
        }
        if (deltas.size() < MIN_PAIRED_SYMBOLS) return null;
        deltas.sort(Integer::compareTo);
        int n = deltas.size();
        return n % 2 == 1 ? deltas.get(n / 2) : (int) Math.round((deltas.get(n / 2 - 1) + deltas.get(n / 2)) / 2.0);
    }

    private Map<String, Integer> runScores(LocalDate date) {
        CachedRun cached = runCache.get(date);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt() < RUN_CACHE_TTL_MS) return cached.scores();
        Map<String, Integer> scores = new HashMap<>();
        for (MultibaggerScoreEntity e : multibaggerScoreRepository.findByScreeningDateOrderByCompositeScoreDesc(date)) {
            if (e.getSymbol() != null) scores.put(e.getSymbol(), e.getCompositeScore());
        }
        runCache.put(date, new CachedRun(scores, now));
        return scores;
    }

    /** Grade bands of the screener (SPEC section 12.5) applied to a shift-adjusted score. */
    static String gradeOf(int score) {
        if (score >= 85) return "A+";
        if (score >= 75) return "A";
        if (score >= 65) return "B+";
        if (score >= 55) return "B";
        if (score >= 45) return "C+";
        if (score >= 35) return "C";
        return "D";
    }

    /** Find the score entry whose date is closest to {@code target}. Returns null if history is empty. */
    private MultibaggerScoreEntity findClosest(List<MultibaggerScoreEntity> history, LocalDate target) {
        MultibaggerScoreEntity best = null;
        long bestDays = Long.MAX_VALUE;
        for (MultibaggerScoreEntity e : history) {
            if (e.getScreeningDate() == null) continue;
            long diff = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(e.getScreeningDate(), target));
            if (diff < bestDays) {
                bestDays = diff;
                best = e;
            }
        }
        // Only accept if within 10 days — otherwise the window is too sparse
        return (bestDays <= 10) ? best : null;
    }

    private Verdict classify(Integer delta30, Integer delta60, int gradeSteps) {
        if (delta30 == null && delta60 == null) return Verdict.NO_DATA;
        int d30 = delta30 != null ? delta30 : 0;
        int d60 = delta60 != null ? delta60 : 0;
        if (d30 <= -20 || d60 <= -25) return Verdict.BROKEN;
        if (d30 <= -10 || gradeSteps >= 2) return Verdict.DECAYING;
        if (d30 <= -5 || gradeSteps >= 1) return Verdict.WATCH;
        return Verdict.INTACT;
    }

    private String buildReason(DecayAlert a) {
        if (a.getVerdict() == Verdict.INTACT) {
            if (a.getDelta30d() != null && a.getDelta30d() <= -5 && a.getUniverseDelta30d() != null) {
                // Raw fell, but so did everyone - say why the verdict is still INTACT.
                return String.format("Score %+d over 30d, but the whole universe moved %+d - relative to peers %+d, no thesis change",
                        a.getDelta30d(), a.getUniverseDelta30d(), a.getRelativeDelta30d());
            }
            return "Score stable - no meaningful drop over the last 30/60 days";
        }
        // NO_DATA with current score present -> history exists but comparison window was too sparse
        if (a.getVerdict() == Verdict.NO_DATA && a.getCurrentScore() != null) {
            return "Insufficient history - stock recently added to screening universe, can't measure 30/60-day drift yet";
        }
        StringBuilder sb = new StringBuilder();
        boolean rel30 = a.getRelativeDelta30d() != null;
        Integer d30 = rel30 ? a.getRelativeDelta30d() : a.getDelta30d();
        if (d30 != null && d30 <= -5) {
            sb.append(rel30
                    ? String.format("Score %+d vs peers over 30d (raw %+d, universe %+d; now %d)",
                            d30, a.getDelta30d(), a.getUniverseDelta30d(), a.getCurrentScore())
                    : String.format("Score %+d over 30d (now %d; universe shift not measured)", d30, a.getCurrentScore()));
        }
        boolean rel60 = a.getRelativeDelta60d() != null;
        Integer d60 = rel60 ? a.getRelativeDelta60d() : a.getDelta60d();
        if (d60 != null && d60 <= -10) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(String.format("%+d%s over 60d", d60, rel60 ? " vs peers" : ""));
        }
        if (a.getGradeStepsDropped() >= 1) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(String.format("grade %s -> %s",
                    a.getGradeAt30d() != null ? a.getGradeAt30d() : "?",
                    a.getCurrentGrade() != null ? a.getCurrentGrade() : "?"));
        }
        return sb.length() > 0 ? sb.toString() : "No decay signals";
    }

    /** Grade ordinal where A+ = 7 (best) and D = 1 (worst). Null/unknown = 4 (neutral). */
    private int gradeLevel(String grade) {
        if (grade == null) return 4;
        return switch (grade) {
            case "A+" -> 7;
            case "A" -> 6;
            case "B+" -> 5;
            case "B" -> 4;
            case "C+" -> 3;
            case "C" -> 2;
            case "D" -> 1;
            default -> 4;
        };
    }

    /** Severity for sorting — higher value = more urgent. */
    private int severity(Verdict v) {
        if (v == null) return 0;
        return switch (v) {
            case BROKEN -> 5;
            case DECAYING -> 4;
            case WATCH -> 3;
            case STALE -> 2;
            case NO_DATA -> 1;
            case INTACT -> 0;
        };
    }

    @Data
    public static class DecayAlert {
        private String symbol;
        private Verdict verdict;
        private String reason;

        // Current snapshot
        private Integer currentScore;
        private String currentGrade;
        private LocalDate currentDate;

        // 30-day window
        private Integer scoreAt30d;
        private String gradeAt30d;
        private Integer delta30d;

        // 60-day window
        private Integer scoreAt60d;
        private Integer delta60d;

        // B-064: what the universe did over the same windows, and the stock's move net of it.
        // Null = not measured (fewer than MIN_PAIRED_SYMBOLS on both dates) - never zero.
        private Integer universeDelta30d;
        private Integer universeDelta60d;
        private Integer relativeDelta30d;
        private Integer relativeDelta60d;
        private boolean universeShiftMeasured;

        // Grade movement
        private int gradeStepsDropped;

        // Portfolio context
        private Double pnlPercent;

        /**
         * The symbol whose screening history was actually read (B-061): a BSE-held position is
         * scored off its NSE screening rows. Null when no history was found under any variant.
         */
        private String resolvedSymbol;
    }
}
