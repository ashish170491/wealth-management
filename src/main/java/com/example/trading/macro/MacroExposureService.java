package com.example.trading.macro;

import com.example.trading.fundamentals.AnnualFundamentalsEntity;
import com.example.trading.fundamentals.AnnualFundamentalsRepository;
import com.example.trading.holdings.SymbolVariants;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import com.example.trading.portfolio.SectorMapping;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads macro exposure for stocks (SPEC §48.4). <b>Database only</b> - no broker call, no NSE call,
 * no model, nothing that leaves the machine - so every surface can use it on page load.
 *
 * <p><b>One bulk call per screen, not one per row.</b> {@link #forSymbols} resolves the whole
 * portfolio in three queries. Doing it per holding would be roughly 130 queries on a page load,
 * because each of ~33 holdings resolves through up to four symbol spellings (Gotcha 84).
 *
 * <p><b>Exchange prefix is not identity, and an empty row must not win.</b> Screening history is
 * keyed on the NSE symbol while two-thirds of this portfolio is BSE-prefixed, so a holding is
 * looked up through {@link SymbolVariants}. The subtlety that bit the compounding lens (B-088) is
 * that "first hit wins" has to mean "first hit that can answer": some BSE-keyed rows exist carrying
 * no sector or industry at all, and an empty exact match would otherwise beat a full NSE row and
 * report NOT_MEASURED for a stock the app had classified perfectly well.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroExposureService {

    /** How far back to look for a screening row before treating a stock as never screened. */
    private static final int SCORE_LOOKBACK_DAYS = 400;

    private final MacroEventRepository eventRepository;
    private final MultibaggerScoreRepository scoreRepository;
    private final AnnualFundamentalsRepository annualRepository;
    private final MacroConfig config;

    /**
     * One stock's reading, with the identity that answered it.
     *
     * @param symbolAnswered which spelling of the symbol carried the classification, so a reading
     *                       can be traced rather than assumed (Gotcha 84)
     * @param everScreened   false means the app has never classified this business at all, which
     *                       is different from having classified it and having no rule for it
     */
    public record Reading(MacroExposureRead.Result result, String symbolAnswered, String sector,
                          boolean everScreened, LocalDate asOf, int windowDays) {

        public MacroExposureRead.Verdict verdict() {
            return result.verdict();
        }
    }

    /**
     * Fail fast at boot rather than at the first page load. A malformed rule or calendar row stops
     * the application naming the line; the alternative is a table that quietly matches nothing and
     * reports "not measured" for every stock for ever, which has happened three times in this
     * codebase under other names (Gotcha 94).
     */
    @PostConstruct
    void validateResourcesAtBoot() {
        MacroExposureMap.version();
        MacroCalendar.all();
    }

    // ------------------------------------------------------------------ reads

    /** Readings for many symbols in three queries. Symbols with no reading are absent from the map. */
    public Map<String, Reading> forSymbols(Collection<String> symbols) {
        Map<String, Reading> out = new LinkedHashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;

        List<MacroExposureRead.Event> events = activeEvents();
        Map<String, MultibaggerScoreEntity> scores = latestScores(symbols);
        Map<String, Double> leverage = latestLeverage(symbols);

        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) continue;
            out.put(symbol, read(symbol, scores, leverage, events));
        }
        return out;
    }

    /** One symbol. Same rules, same three queries. */
    public Reading forSymbol(String symbol) {
        Map<String, Reading> one = forSymbols(List.of(symbol));
        Reading r = one.get(symbol);
        return r != null ? r : notScreened(symbol);
    }

    /**
     * Present only when the app has ever classified this business.
     *
     * <p>The distinction the caller needs: absent means "never screened", so the screen says so;
     * present-but-NOT_MEASURED means "screened, and the map has no rule for it", which is a gap in
     * the map. Rendering those two alike is the failure SPEC §21 rule 7 exists to prevent.
     */
    public Optional<Reading> forSymbolIfEverScreened(String symbol) {
        Reading r = forSymbol(symbol);
        return r.everScreened() ? Optional.of(r) : Optional.empty();
    }

    /** Events inside the window, newest first, dismissed ones included so a caller can list them. */
    public List<MacroEventEntity> recentEvents(int days) {
        int window = days > 0 ? days : config.getWindowDays();
        try {
            return eventRepository.findByOccurredAtGreaterThanEqualOrderByOccurredAtDescIdDesc(
                    LocalDate.now().minusDays(window));
        } catch (Exception e) {
            log.warn("Macro events unavailable ({}), so every screen will read 'no events in the "
                    + "window' - which is indistinguishable from a genuinely quiet fortnight.",
                    e.getMessage());
            return List.of();
        }
    }

    /** The stocks in a collection an event's factor could touch at all, for the events table. */
    public List<String> symbolsExposedTo(MacroFactor factor, Collection<String> universe,
                                         Map<String, Reading> readings) {
        List<String> out = new ArrayList<>();
        if (factor == null || universe == null) return out;
        for (String symbol : universe) {
            Reading r = readings.get(symbol);
            if (r == null || !r.result().measured()) continue;
            boolean touched = r.result().reasons().stream().anyMatch(x -> x.factor() == factor);
            if (touched) out.add(symbol);
        }
        return out;
    }

    public int windowDays() {
        return config.getWindowDays();
    }

    // ------------------------------------------------------------------ internals

    private Reading read(String symbol, Map<String, MultibaggerScoreEntity> scores,
                         Map<String, Double> leverage, List<MacroExposureRead.Event> events) {

        MultibaggerScoreEntity row = null;
        String answered = null;
        boolean everScreened = false;
        // First hit that can ANSWER, not merely the first hit (B-088): a BSE row carrying no
        // classification must not beat a full NSE row and report a gap that does not exist.
        for (String candidate : SymbolVariants.candidates(symbol)) {
            MultibaggerScoreEntity found = scores.get(candidate);
            if (found == null) continue;
            everScreened = true;
            if (classified(found)) {
                row = found;
                answered = candidate;
                break;
            }
            if (answered == null) answered = candidate;
        }

        String sector = row != null
                ? SectorMapping.resolve(row.getSymbol(), row.getIndustry())
                : SectorMapping.resolve(symbol, null);
        String industry = row == null ? null : row.getIndustry();

        List<MacroExposureMap.Entry> exposures = MacroExposureMap.forStock(
                answered != null ? answered : symbol, sector, industry);

        Double debtToEquity = null;
        for (String candidate : SymbolVariants.candidates(symbol)) {
            Double d = leverage.get(candidate);
            if (d != null) {
                debtToEquity = d;
                break;
            }
        }
        if (debtToEquity == null && row != null) debtToEquity = row.getDebtToEquity();

        MacroExposureRead.Result result = MacroExposureRead.read(new MacroExposureRead.Input(
                symbol, sector, isLender(row, sector), debtToEquity, exposures, events,
                LocalDate.now(), config.getWindowDays()));

        return new Reading(result, answered == null ? symbol : answered, sector, everScreened,
                LocalDate.now(), config.getWindowDays());
    }

    private Reading notScreened(String symbol) {
        String sector = SectorMapping.resolve(symbol, null);
        MacroExposureRead.Result result = MacroExposureRead.read(new MacroExposureRead.Input(
                symbol, sector, false, null, MacroExposureMap.forStock(symbol, sector, null),
                activeEvents(), LocalDate.now(), config.getWindowDays()));
        return new Reading(result, symbol, sector, false, LocalDate.now(), config.getWindowDays());
    }

    /** A row that carries neither sector nor industry cannot answer, whatever else it holds. */
    private static boolean classified(MultibaggerScoreEntity row) {
        String industry = row.getIndustry();
        boolean hasIndustry = industry != null && !industry.isBlank();
        String sector = SectorMapping.resolve(row.getSymbol(), industry);
        return hasIndustry || !SectorMapping.isUnknown(sector);
    }

    /**
     * A bank or non-bank lender.
     *
     * <p>Used for one thing only: suppressing the leverage adjustment. A lender is funded by debt
     * by construction and runs at five to eight times equity in the ordinary course, so treating
     * that as evidence of rate sensitivity would mark every one of them highly exposed on a number
     * that says nothing about exposure. Same reasoning that makes SPEC §12.8 refuse to compute a
     * debt-to-equity verdict for financials at all - which is also where the marker comes from.
     */
    private static boolean isLender(MultibaggerScoreEntity row, String sector) {
        if ("BANKING".equals(sector) || "FINANCIALS".equals(sector)) return true;
        if (row == null) return false;
        if ("NA_FINANCIAL".equalsIgnoreCase(row.getCapexVerdict())) return true;
        String industry = row.getIndustry();
        return industry != null && industry.toLowerCase(Locale.ROOT)
                .matches(".*(bank|financ|nbfc|insur|capital market|holding).*");
    }

    private List<MacroExposureRead.Event> activeEvents() {
        List<MacroExposureRead.Event> out = new ArrayList<>();
        for (MacroEventEntity e : recentEvents(config.getWindowDays())) {
            MacroExposureRead.Event ev = e.toReadEvent();
            // A row whose factor no longer parses is a retired enum value, not an event. Skipped
            // rather than dropped from the table: the record stays, the reading ignores it.
            if (ev.factor() == null || ev.direction() == null) continue;
            out.add(ev);
        }
        return out;
    }

    private Map<String, MultibaggerScoreEntity> latestScores(Collection<String> symbols) {
        Map<String, MultibaggerScoreEntity> map = new LinkedHashMap<>();
        Set<String> spellings = new LinkedHashSet<>();
        for (String s : symbols) {
            spellings.addAll(SymbolVariants.candidates(s));
        }
        if (spellings.isEmpty()) return map;
        try {
            // ASC by date, so writing every row leaves the newest per symbol - the convention the
            // rest of the codebase already relies on.
            for (MultibaggerScoreEntity row : scoreRepository.findRecentForSymbols(
                    spellings, LocalDate.now().minusDays(SCORE_LOOKBACK_DAYS))) {
                map.put(row.getSymbol(), row);
            }
        } catch (Exception e) {
            log.warn("Macro exposure: screening rows unavailable ({}), so every stock will read "
                    + "'never screened' rather than carrying a reading.", e.getMessage());
        }
        return map;
    }

    /** Latest debt-to-equity per symbol from the annual accounts. Absent means never measured. */
    private Map<String, Double> latestLeverage(Collection<String> symbols) {
        Map<String, Double> map = new LinkedHashMap<>();
        Set<String> spellings = new LinkedHashSet<>();
        for (String s : symbols) {
            spellings.addAll(SymbolVariants.candidates(s));
        }
        if (spellings.isEmpty()) return map;
        try {
            Map<String, Integer> newestYear = new LinkedHashMap<>();
            for (AnnualFundamentalsEntity row : annualRepository.findHistoryForSymbols(spellings)) {
                Double de = row.debtToEquity();
                if (de == null || row.getFiscalYear() == null) continue;
                Integer seen = newestYear.get(row.getSymbol());
                if (seen == null || row.getFiscalYear() > seen) {
                    newestYear.put(row.getSymbol(), row.getFiscalYear());
                    map.put(row.getSymbol(), de);
                }
            }
        } catch (Exception e) {
            log.warn("Macro exposure: annual accounts unavailable ({}), so a leveraged borrower's "
                    + "rate reading will not be raised. The reading stands; only its strength is "
                    + "conservative.", e.getMessage());
        }
        return map;
    }
}
