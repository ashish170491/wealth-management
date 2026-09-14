package com.example.trading.insider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns stored insider disclosures into a rolling per-stock verdict (SPEC §28, F1).
 *
 * <p>Net open-market buying by promoters/directors/KMP over a trailing window, normalised by
 * market cap so a Rs 5 cr purchase in a Rs 500 cr company outranks a Rs 50 cr purchase in a
 * Rs 2 lakh cr one. Size relative to the company is the signal; absolute rupees are not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsiderPulseService {

    private final InsiderDisclosureRepository repository;

    static final int WINDOW_DAYS = 90;

    /** Net buying above this share of market cap is a strong signal. */
    static final double STRONG_PCT = 0.25;
    static final double MODERATE_PCT = 0.05;
    /** Distinct <b>people</b> trading in the same direction before a count-based verdict is strong. */
    static final int STRONG_EVENT_COUNT = 3;

    /**
     * Floor below which a cluster of trades is not conviction, whatever the count (B-041).
     *
     * <p>Rs 25 lakh across the whole 90-day window. Three Rs 1,000 purchases by one director
     * used to return STRONG_ACCUMULATION — the strongest verdict the system has — because the
     * event-count escape hatch fired even when the value was known and trivially small. A
     * promoter buying Rs 3,000 of their own company has told us nothing; the filing exists
     * because SEBI PIT Reg 7(2) has no de-minimis threshold, not because it is a signal.
     */
    static final double MIN_MATERIAL_VALUE_RUPEES = 25_00_000d;

    public InsiderPulse computePulse(String symbol, Double marketCapCrores) {
        List<InsiderDisclosureEntity> rows =
                repository.findSince(symbol, LocalDate.now().minusDays(WINDOW_DAYS));
        return classify(symbol, rows, marketCapCrores);
    }

    /**
     * Pure classification over already-loaded rows — separated from the repository call so
     * the thresholds can be tested without a database.
     */
    InsiderPulse classify(String symbol, List<InsiderDisclosureEntity> rows, Double marketCapCrores) {
        List<String> notes = new ArrayList<>();

        if (rows == null || rows.isEmpty()) {
            // No filings at all: not measurable. Explicitly NOT "NEUTRAL".
            return InsiderPulse.builder()
                    .symbol(symbol)
                    .verdict(null)
                    .windowDays(WINDOW_DAYS)
                    .notes(List.of("No insider disclosures on file for the last " + WINDOW_DAYS + " days"))
                    .build();
        }

        double netValue = 0;
        double buyValue = 0;
        double sellValue = 0;
        int buys = 0;
        int sells = 0;
        int excluded = 0;
        boolean anyValue = false;
        // Distinct people, not distinct filings: one director splitting a purchase across
        // three days files three rows, and three rows by one person is one person's opinion.
        Set<String> buyers = new HashSet<>();
        Set<String> sellers = new HashSet<>();

        for (InsiderDisclosureEntity e : rows) {
            if (!InsiderDisclosureService.isSignalRow(e)) {
                excluded++;
                continue;
            }
            double v = e.getValue() != null ? e.getValue() : 0;
            if (v > 0) anyValue = true;
            String who = personKey(e);
            if ("BUY".equals(e.getTransactionType())) {
                buys++;
                buyValue += v;
                netValue += v;
                buyers.add(who);
            } else {
                sells++;
                sellValue += v;
                netValue -= v;
                sellers.add(who);
            }
        }

        if (buys == 0 && sells == 0) {
            notes.add(excluded + " disclosure(s) on file, all non-market (pledge / ESOP / gift / "
                    + "inter-se transfer) — recorded but never scored");
            return InsiderPulse.builder()
                    .symbol(symbol)
                    .verdict(null)
                    .excludedNonMarketRows(excluded)
                    .windowDays(WINDOW_DAYS)
                    .notes(notes)
                    .build();
        }

        Double pct = null;
        if (marketCapCrores != null && marketCapCrores > 0) {
            double marketCapRupees = marketCapCrores * 1_00_00_000d;
            pct = 100.0 * netValue / marketCapRupees;
        }

        String verdict = verdictFor(pct, netValue, buyValue, sellValue,
                buys, sells, buyers.size(), sellers.size(), anyValue);

        if (excluded > 0) {
            notes.add(excluded + " non-market disclosure(s) excluded from scoring "
                    + "(pledge / ESOP / gift / inter-se transfer)");
        }
        if (pct == null) {
            notes.add("Market cap unknown — verdict from event counts only, not size");
        }
        if (!anyValue) {
            notes.add("Filings omitted transaction values — verdict from event counts only");
        }

        return InsiderPulse.builder()
                .symbol(symbol)
                .verdict(verdict)
                .netBuyValue(netValue)
                .netBuyPercentOfMarketCap(pct)
                .buyEvents(buys)
                .sellEvents(sells)
                .excludedNonMarketRows(excluded)
                .windowDays(WINDOW_DAYS)
                .notes(notes)
                .build();
    }

    /**
     * Verdict from size where size is known, from a corroborated count where it is not.
     *
     * <p>The count path is deliberately harder to satisfy than it was (B-041): it now needs
     * {@link #STRONG_EVENT_COUNT} distinct <i>people</i> rather than filings, and — whenever
     * any value at all was disclosed — an aggregate clearing
     * {@link #MIN_MATERIAL_VALUE_RUPEES}. Where nothing was disclosed there is no floor to
     * apply, so a cluster of separate insiders remains the only evidence available and is
     * still allowed to be strong.
     */
    private String verdictFor(Double pct, double netValue, double buyValue, double sellValue,
                              int buys, int sells, int distinctBuyers, int distinctSellers,
                              boolean anyValue) {

        boolean buyCluster = distinctBuyers >= STRONG_EVENT_COUNT && sells == 0
                && (!anyValue || buyValue >= MIN_MATERIAL_VALUE_RUPEES);
        boolean sellCluster = distinctSellers >= STRONG_EVENT_COUNT && buys == 0
                && (!anyValue || sellValue >= MIN_MATERIAL_VALUE_RUPEES);

        // Count path: used when we cannot size the trades against the company (no market cap,
        // or the filings omitted values). Counting conviction is weaker than measuring it.
        if (pct == null || !anyValue) {
            if (buyCluster) return "STRONG_ACCUMULATION";
            if (buys > sells) return "ACCUMULATION";
            if (sellCluster) return "STRONG_DISTRIBUTION";
            if (sells > buys) return "DISTRIBUTION";
            return "NEUTRAL";
        }

        if (pct >= STRONG_PCT || (buyCluster && netValue > 0)) return "STRONG_ACCUMULATION";
        if (pct >= MODERATE_PCT) return "ACCUMULATION";
        if (pct <= -STRONG_PCT || (sellCluster && netValue < 0)) return "STRONG_DISTRIBUTION";
        if (pct <= -MODERATE_PCT) return "DISTRIBUTION";
        return "NEUTRAL";
    }

    /** Person identity for distinctness, tolerant of NSE's inconsistent spacing and casing. */
    private static String personKey(InsiderDisclosureEntity e) {
        String n = e.getPersonName();
        if (n == null || n.isBlank()) return "#" + e.getId();   // unnamed rows never merge
        return n.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }
}
