package com.example.trading.macro;

import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.watchlist.WatchlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shapes macro readings for the screens (SPEC §48.8). <b>Database and classpath only</b> - safe on
 * page load, no broker call, no NSE call, no model, no email.
 *
 * <p>Composition only, no analysis: every verdict here comes from {@link MacroExposureService} and
 * every rule from {@link MacroExposureMap}, so a screen can never disagree with the email or with
 * another screen. That is the same contract the dashboard package states for itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroViewService {

    private final MacroExposureService exposureService;
    private final MacroEventRepository eventRepository;
    private final MacroEventExtractor extractor;
    private final HoldingsRepository holdingsRepository;
    private final WatchlistRepository watchlistRepository;
    private final MacroConfig config;

    // ------------------------------------------------------------------ portfolio

    /** Every active holding with its reading, plus how much of the portfolio could be measured. */
    public Map<String, Object> portfolioView() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<HoldingsEntity> holdings;
        try {
            holdings = holdingsRepository.findActive();
        } catch (Exception e) {
            log.warn("Macro portfolio view: holdings unavailable ({}).", e.getMessage());
            holdings = List.of();
        }

        List<String> symbols = holdings.stream().map(HoldingsEntity::getSymbol).filter(s -> s != null).toList();
        Map<String, MacroExposureService.Reading> readings = exposureService.forSymbols(symbols);
        List<MacroEventEntity> events = exposureService.recentEvents(config.getWindowDays());
        long live = events.stream().filter(e -> !e.isDismissed()).count();

        List<Map<String, Object>> rows = new ArrayList<>();
        int measured = 0;
        int notMeasured = 0;
        int headwinds = 0;
        int tailwinds = 0;
        for (HoldingsEntity h : holdings) {
            MacroExposureService.Reading r = readings.get(h.getSymbol());
            if (r == null) continue;
            rows.add(row(h.getSymbol(), r));
            if (r.result().measured()) {
                measured++;
            } else {
                notMeasured++;
            }
            if (r.verdict() == MacroExposureRead.Verdict.HEADWIND) headwinds++;
            if (r.verdict() == MacroExposureRead.Verdict.TAILWIND) tailwinds++;
        }

        out.put("holdings", rows);
        out.put("windowDays", config.getWindowDays());
        out.put("eventsInWindow", live);
        out.put("holdingsMeasured", measured);
        out.put("holdingsNotMeasured", notMeasured);
        out.put("headwinds", headwinds);
        out.put("tailwinds", tailwinds);
        // The sentence that is the feature working rather than failing.
        out.put("nothingTouchesPortfolio", live > 0 && headwinds == 0 && tailwinds == 0);
        out.put("note", note(live, headwinds, tailwinds, notMeasured));
        return out;
    }

    private static String note(long live, int headwinds, int tailwinds, int notMeasured) {
        StringBuilder sb = new StringBuilder();
        if (live == 0) {
            sb.append("No macro event has been recorded in the window. That is the ordinary state.");
        } else if (headwinds == 0 && tailwinds == 0) {
            sb.append(live).append(live == 1 ? " event was" : " events were")
                    .append(" recorded in the window, and none of them touches a stock you own.");
        } else {
            sb.append(live).append(live == 1 ? " event" : " events").append(" in the window; ")
                    .append(headwinds).append(" of your holdings face a headwind and ")
                    .append(tailwinds).append(" have a tailwind behind them.");
        }
        if (notMeasured > 0) {
            sb.append(' ').append(notMeasured).append(" holding")
                    .append(notMeasured == 1 ? " has" : "s have")
                    .append(" no rule in the exposure map yet, so nothing can be said about ")
                    .append(notMeasured == 1 ? "it" : "them")
                    .append(" - a gap in the map rather than a verdict.");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ one stock

    /** One stock's reading; empty when the app has never classified this business at all. */
    public Optional<Map<String, Object>> exposureView(String symbol) {
        return exposureService.forSymbolIfEverScreened(symbol).map(r -> row(symbol, r));
    }

    private Map<String, Object> row(String symbol, MacroExposureService.Reading r) {
        Map<String, Object> m = new LinkedHashMap<>();
        MacroExposureRead.Result result = r.result();
        m.put("symbol", symbol);
        m.put("sector", r.sector());
        m.put("macroExposure", result.verdict().name());
        m.put("macroExposureStrength", result.strength() == null ? null : result.strength().name());
        m.put("macroExposureEvents", result.reasons().isEmpty() ? null : result.reasons().size());
        m.put("macroExposureFrom", r.symbolAnswered());
        m.put("macroExposureReasons", result.reasons().stream().map(MacroExposureRead.Reason::text).toList());
        m.put("macroExposureNote", result.note());
        m.put("eventsConsidered", result.eventsConsidered());
        m.put("windowDays", r.windowDays());
        m.put("answeredBy", result.answeredBy());

        List<Map<String, Object>> detail = new ArrayList<>();
        for (MacroExposureRead.Reason reason : result.reasons()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("eventId", reason.eventId());
            d.put("factor", reason.factor().name());
            d.put("factorLabel", reason.factor().label());
            d.put("eventDirection", reason.eventDirection().name());
            d.put("effect", reason.effect().name());
            d.put("strength", reason.strength().name());
            d.put("channel", reason.channel());
            d.put("occurredAt", reason.occurredAt());
            d.put("text", reason.text());
            d.put("rationale", reason.rationale());
            d.put("from", reason.from());
            detail.add(d);
        }
        m.put("macroExposureReasonDetail", detail);
        return m;
    }

    // ------------------------------------------------------------------ events

    /** Events in the window, with which of the investor's stocks each one touches. */
    public Map<String, Object> eventsView(int days, boolean includeDismissed) {
        int window = days > 0 ? days : config.getWindowDays();
        List<MacroEventEntity> events = exposureService.recentEvents(window);

        Set<String> owned = symbols(holdingsRepository());
        Set<String> watched = watchedSymbols();
        Set<String> both = new LinkedHashSet<>(owned);
        both.addAll(watched);
        Map<String, MacroExposureService.Reading> readings = exposureService.forSymbols(both);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MacroEventEntity e : events) {
            if (e.isDismissed() && !includeDismissed) continue;
            rows.add(eventRow(e, owned, watched, readings));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", window);
        out.put("events", rows);
        out.put("lastIngestAt", latestExtractedAt());
        out.put("dismissedCount", events.stream().filter(MacroEventEntity::isDismissed).count());
        out.put("note", rows.isEmpty()
                ? "No macro event was recorded in this window. On most days that is the correct answer."
                : rows.size() + (rows.size() == 1 ? " event" : " events") + " recorded in the last "
                        + window + " days.");
        return out;
    }

    private Map<String, Object> eventRow(MacroEventEntity e, Set<String> owned, Set<String> watched,
                                         Map<String, MacroExposureService.Reading> readings) {
        Map<String, Object> m = new LinkedHashMap<>();
        MacroFactor factor = e.factorOrNull();
        m.put("id", e.getId());
        m.put("factor", factor == null ? e.getFactor() : factor.name());
        m.put("factorLabel", factor == null ? e.getFactor() : factor.label());
        m.put("risesMeans", factor == null ? null : factor.risesMeans());
        m.put("direction", e.getDirection());
        m.put("magnitude", e.getMagnitude());
        m.put("kind", e.getKind());
        m.put("geography", e.getGeography());
        m.put("occurredAt", e.getOccurredAt());
        m.put("extractedAt", e.getExtractedAt());
        m.put("extractor", e.getExtractor());
        m.put("confidence", e.getConfidence());
        m.put("summary", e.getSummary());
        m.put("dismissed", e.isDismissed());
        m.put("exposureMapVersion", e.getExposureMapVersion());

        List<Map<String, Object>> headlines = new ArrayList<>();
        List<String> urls = e.sourceUrlList();
        for (int i = 0; i < urls.size(); i++) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("url", urls.get(i));
            headlines.add(h);
        }
        m.put("headlines", headlines);
        m.put("headlineCount", Math.max(headlines.size(), e.headlineIdList().size()));

        m.put("exposedHoldings", exposedTo(factor, owned, readings));
        m.put("exposedWatchlist", exposedTo(factor, watched, readings));
        return m;
    }

    private List<String> exposedTo(MacroFactor factor, Set<String> symbols,
                                   Map<String, MacroExposureService.Reading> readings) {
        if (factor == null) return List.of();
        return exposureService.symbolsExposedTo(factor, symbols, readings);
    }

    // ------------------------------------------------------------------ calendar

    /** What is coming, with how many holdings the map says are sensitive to each kind of event. */
    public Map<String, Object> calendarView(int days) {
        int window = days > 0 ? days : config.getCalendarDays();
        List<MacroCalendar.Occurrence> upcoming = MacroCalendar.upcoming(LocalDate.now(), window);

        Set<String> owned = symbols(holdingsRepository());
        Map<String, MacroExposureService.Reading> readings = exposureService.forSymbols(owned);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MacroCalendar.Occurrence o : upcoming) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", o.date());
            m.put("daysAway", o.daysAway());
            m.put("label", o.label());
            m.put("factor", o.factor().name());
            m.put("factorLabel", o.factor().label());
            m.put("geography", o.geography());
            m.put("notes", o.notes());
            List<String> sensitive = sensitiveTo(o.factor(), owned);
            m.put("sensitiveHoldings", sensitive);
            m.put("sensitiveCount", sensitive.size());
            rows.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", window);
        out.put("entries", rows);
        out.put("note", "Dates only. The app does not know which way any of these will go, and there "
                + "is no field in which it could record a guess.");
        return out;
    }

    /**
     * Which holdings the map has a rule for on this factor - regardless of any event.
     *
     * <p>Deliberately not the exposure reading: a calendar row is about something that has not
     * happened, so asking "who would this touch" is a question about the map, not about a verdict.
     */
    private List<String> sensitiveTo(MacroFactor factor, Set<String> owned) {
        List<String> out = new ArrayList<>();
        for (String symbol : owned) {
            boolean touched = MacroExposureMap.forStock(symbol,
                            com.example.trading.portfolio.SectorMapping.resolve(symbol, null), null)
                    .stream().anyMatch(e -> e.factor() == factor);
            if (touched) out.add(symbol);
        }
        return out;
    }

    // ------------------------------------------------------------------ the map itself

    /** The rule table, so the investor can read the rules rather than trust them. */
    public Map<String, Object> mapView(MacroFactor factor) {
        List<MacroExposureMap.Entry> entries = factor == null
                ? MacroExposureMap.all() : MacroExposureMap.forFactor(factor);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MacroExposureMap.Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("factor", e.factor().name());
            m.put("factorLabel", e.factor().label());
            m.put("risesMeans", e.factor().risesMeans());
            m.put("scope", e.scope().name());
            m.put("key", e.key());
            m.put("onRise", e.onRise().name());
            m.put("strength", e.strength().name());
            m.put("channel", e.channel());
            m.put("rationale", e.rationale());
            rows.add(m);
        }

        List<Map<String, Object>> factors = new ArrayList<>();
        for (MacroFactor f : MacroFactor.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("factor", f.name());
            m.put("label", f.label());
            m.put("risesMeans", f.risesMeans());
            m.put("plainEnglish", f.plainEnglish());
            m.put("rules", MacroExposureMap.forFactor(f).size());
            factors.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", MacroExposureMap.version());
        out.put("totalRules", MacroExposureMap.size());
        out.put("factors", factors);
        out.put("rules", rows);
        return out;
    }

    // ------------------------------------------------------------------ status

    /** What an ingest would do right now, and what it last did. */
    public Map<String, Object> statusView() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("extractor", extractor.configuredExtractor());
        out.put("modelAvailable", extractor.modelAvailable());
        out.put("ingestScheduled", config.getIngest().isScheduled());
        out.put("windowDays", config.getWindowDays());
        out.put("maxHeadlinesPerIngest", config.getIngest().getMaxHeadlines());
        out.put("refuseFrom", config.getIngest().getRefuseFrom());
        out.put("lastIngestAt", latestExtractedAt());
        out.put("exposureMapVersion", MacroExposureMap.version());
        out.put("exposureMapRules", MacroExposureMap.size());
        out.put("cost", extractor.modelAvailable()
                ? "Reads up to " + config.getIngest().getMaxHeadlines() + " headlines with "
                        + extractor.configuredExtractor() + ". Roughly a rupee or two per run."
                : "Reads with the app's keyword rules. No language model is configured, so an "
                        + "ingest costs nothing but the time to fetch the feeds.");
        return out;
    }

    private java.time.LocalDateTime latestExtractedAt() {
        try {
            return eventRepository.findLatestExtractedAt();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<HoldingsEntity> holdingsRepository() {
        try {
            return holdingsRepository.findActive();
        } catch (Exception e) {
            log.debug("Macro view: holdings unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    private static Set<String> symbols(List<HoldingsEntity> holdings) {
        Set<String> out = new LinkedHashSet<>();
        for (HoldingsEntity h : holdings) {
            if (h.getSymbol() != null && !h.getSymbol().isBlank()) out.add(h.getSymbol());
        }
        return out;
    }

    private Set<String> watchedSymbols() {
        Set<String> out = new LinkedHashSet<>();
        try {
            watchlistRepository.findAll().forEach(w -> {
                if (w.getSymbol() != null && !w.getSymbol().isBlank()) out.add(w.getSymbol());
            });
        } catch (Exception e) {
            log.debug("Macro view: watchlist unavailable: {}", e.getMessage());
        }
        return out;
    }
}
