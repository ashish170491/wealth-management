package com.example.trading.macro;

import com.example.trading.notification.EmailTemplateService;
import com.example.trading.persistence.HoldingsEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one place macro exposure is turned into HTML (SPEC §48.10).
 *
 * <p>Both emails and both of their dashboard previews render through this, so the morning briefing
 * and the afternoon action items can never describe the same event differently. That is the same
 * discipline the buy-timing table enforces for its question (Gotcha 85).
 *
 * <p><b>The most important output here is the sentence that says nothing applies.</b> When events
 * were recorded and none of them touches a holding, the section says so in as many words. A digest
 * that only ever appears when it has something alarming to report trains the reader to treat its
 * presence as a warning; one that reports "nothing here concerns you" is the version an investor
 * can actually rely on. When there are no events at all the section returns empty, because an empty
 * fortnight is not news.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MacroReportRenderer {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final EmailTemplateService templateService;

    /**
     * "What recent events mean for what you own" - for the 15:18 action-items email.
     *
     * @return empty string when no event was recorded in the window at all
     */
    public String portfolioSection(List<HoldingsEntity> holdings,
                                   Map<String, MacroExposureService.Reading> readings,
                                   int liveEventCount, int windowDays) {
        if (liveEventCount <= 0) {
            return "";
        }

        List<HoldingsEntity> headwinds = new ArrayList<>();
        List<HoldingsEntity> tailwinds = new ArrayList<>();
        List<HoldingsEntity> mixed = new ArrayList<>();
        int notMeasured = 0;

        for (HoldingsEntity h : holdings) {
            MacroExposureService.Reading r = readings.get(h.getSymbol());
            if (r == null) {
                notMeasured++;
                continue;
            }
            switch (r.verdict()) {
                case HEADWIND -> headwinds.add(h);
                case TAILWIND -> tailwinds.add(h);
                case MIXED -> mixed.add(h);
                case NOT_MEASURED -> notMeasured++;
                case NOT_EXPOSED -> { }
            }
        }

        StringBuilder body = new StringBuilder();
        body.append(explainer("An event outside any one company - an interest-rate decision, a tariff, "
                + "the price of oil, the rupee, the monsoon - can change how easy or hard the next few "
                + "quarters are for a whole industry. This section says which of your holdings that "
                + "applies to right now. It is background for reading the next results, not a reason "
                + "to do anything today, and it changes none of your scores."));

        if (headwinds.isEmpty() && tailwinds.isEmpty() && mixed.isEmpty()) {
            // The line that is the feature working, stated plainly.
            body.append("<p style=\"margin:12px 0;font-size:14px;\"><b>")
                    .append(liveEventCount).append(liveEventCount == 1 ? " event was" : " events were")
                    .append(" recorded in the last ").append(windowDays)
                    .append(" days, and none of them touches a stock you own.</b> That is the usual "
                            + "result, and it is worth reading as one: most of what moves the news does "
                            + "not move the businesses you have chosen.</p>");
        } else {
            body.append(table(headwinds, tailwinds, mixed, readings));
        }

        if (notMeasured > 0) {
            body.append(coverage(notMeasured, holdings.size()));
        }

        return templateService.section("~", "Events affecting your holdings", body.toString());
    }

    /**
     * "What happened overnight, and what is coming" - for the 09:30 briefing.
     *
     * @return empty string when there is neither a recent event nor a dated one ahead
     */
    public String overnightAndCalendarSection(List<MacroEventEntity> recent,
                                              List<MacroCalendar.Occurrence> upcoming,
                                              Map<String, MacroExposureService.Reading> readings) {
        if ((recent == null || recent.isEmpty()) && (upcoming == null || upcoming.isEmpty())) {
            return "";
        }

        StringBuilder body = new StringBuilder();
        body.append(explainer("The events that have moved the ground under whole industries recently, "
                + "and the dated ones coming up. The app does not know which way any future event will "
                + "go and does not guess - a date is here so nothing arrives as a surprise, not so it "
                + "can be traded."));

        if (recent != null && !recent.isEmpty()) {
            body.append("<h4 style=\"margin:14px 0 6px;font-size:14px;\">Recently</h4>");
            body.append(templateService.tableStart("When", "What moved", "Which way", "Read by"));
            for (MacroEventEntity e : recent) {
                MacroFactor f = e.factorOrNull();
                MacroDirection d = e.directionOrNull();
                body.append(templateService.tableRow(
                        e.getOccurredAt() == null ? "-" : e.getOccurredAt().format(DAY),
                        f == null ? String.valueOf(e.getFactor()) : f.label(),
                        d == null ? "-" : (d.pastTense() + magnitudeSuffix(e)),
                        e.getExtractor() == null ? "-" : readerLabel(e.getExtractor())));
            }
            body.append(templateService.tableEnd());
        }

        if (upcoming != null && !upcoming.isEmpty()) {
            body.append("<h4 style=\"margin:14px 0 6px;font-size:14px;\">Coming up</h4>");
            body.append(templateService.tableStart("When", "What", "Where", "Your holdings it could touch"));
            for (MacroCalendar.Occurrence o : upcoming) {
                long n = readings == null ? 0 : readings.keySet().stream()
                        .filter(sym -> MacroExposureMap.forStock(sym,
                                        com.example.trading.portfolio.SectorMapping.resolve(sym, null), null)
                                .stream().anyMatch(x -> x.factor() == o.factor()))
                        .count();
                body.append(templateService.tableRow(
                        o.date().format(DAY) + (o.daysAway() == 0 ? " (today)" : " (in " + o.daysAway() + "d)"),
                        o.label(),
                        o.geography() == null || o.geography().isBlank() ? "-" : o.geography(),
                        n == 0 ? "none" : String.valueOf(n)));
            }
            body.append(templateService.tableEnd());
        }

        return templateService.section("~", "Events in the wider world", body.toString());
    }

    // ------------------------------------------------------------------ pieces

    private String table(List<HoldingsEntity> headwinds, List<HoldingsEntity> tailwinds,
                         List<HoldingsEntity> mixed, Map<String, MacroExposureService.Reading> readings) {
        StringBuilder sb = new StringBuilder();
        sb.append(templateService.tableStart("Stock", "Reading", "How strong", "Why"));
        appendRows(sb, headwinds, "Headwind", readings);
        appendRows(sb, mixed, "Mixed", readings);
        appendRows(sb, tailwinds, "Tailwind", readings);
        sb.append(templateService.tableEnd());
        sb.append("<p style=\"margin:8px 0 0;color:#555;font-size:12px;\">A headwind is not a reason to "
                + "sell and a tailwind is not a reason to buy. Their use is in reading the next set of "
                + "results correctly: a good business having a hard quarter for a reason you can name is "
                + "a very different thing from one whose thesis has broken.</p>");
        return sb.toString();
    }

    private void appendRows(StringBuilder sb, List<HoldingsEntity> rows, String label,
                            Map<String, MacroExposureService.Reading> readings) {
        for (HoldingsEntity h : rows) {
            MacroExposureService.Reading r = readings.get(h.getSymbol());
            if (r == null) continue;
            MacroExposureRead.Result result = r.result();
            String why = result.reasons().isEmpty() ? "-" : result.reasons().get(0).text();
            String strength = result.strength() == null ? "-" : result.strength().label();
            sb.append(templateService.tableRow(
                    displaySymbol(h.getSymbol()),
                    templateService.badge(label, badgeClass(label)),
                    strength,
                    why));
        }
    }

    private static String badgeClass(String label) {
        return switch (label) {
            case "Headwind" -> "sell";
            case "Tailwind" -> "buy";
            default -> "hold";
        };
    }

    private String coverage(int notMeasured, int total) {
        return "<p style=\"margin:8px 0 0;color:#555;font-size:12px;\">"
                + notMeasured + " of your " + total + " holdings have no rule in the exposure map yet, "
                + "so nothing could be said about them. That is a gap in the app's own table, not a "
                + "finding about those companies.</p>";
    }

    private static String explainer(String text) {
        return "<div style=\"background:#eef3fb;border-left:4px solid #2c5fb3;padding:10px 12px;"
                + "margin:0 0 10px;font-size:13px;\"><b>What this means:</b> " + text + "</div>";
    }

    private static String magnitudeSuffix(MacroEventEntity e) {
        MacroMagnitude m = e.magnitudeOrNull();
        return m == null ? "" : " (" + m.label() + ")";
    }

    /** A model id is meaningless to the reader; what matters is whether a model read it at all. */
    private static String readerLabel(String extractor) {
        if (extractor == null) return "-";
        return extractor.startsWith("KEYWORD") ? "keyword rules" : extractor;
    }

    private static String displaySymbol(String symbol) {
        if (symbol == null) return "-";
        int colon = symbol.indexOf(':');
        return colon >= 0 ? symbol.substring(colon + 1) : symbol;
    }

    /** Today, for callers that need the window's start. */
    public static LocalDate today() {
        return LocalDate.now();
    }
}
