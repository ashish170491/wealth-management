package com.example.trading.multibagger;

import com.example.trading.ai.NseDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Under-Discovery Score (SPEC §12.10, plan feature F2).
 *
 * <p>"Early at low value" concretely means <b>small + under-owned + under-followed + quietly
 * accumulating</b>. Every ingredient is already fetched during a screening pass; the composite
 * simply never asks this question. It rewards institutional holding that is <i>rising</i>, but
 * not institutional holding that is <i>low</i> — and a low base is the precondition for the
 * re-rating that turns a good business into a multibagger.
 *
 * <h2>Why this is a lens, not a bonus</h2>
 * It never enters the composite. Its two largest components (absolute institutional holding,
 * market-cap tier) are already scored by the Institutional Interest dimension and the
 * small-cap bonus; adding them again would double-count the same facts and quietly re-weight
 * the validated 8-dimension blend. The score is a <em>sort key</em> for a report section,
 * which is exactly the treatment the measured evidence supports today.
 *
 * <h2>Null discipline</h2>
 * A stock that fails the quality gate, or whose shareholding data is unavailable, scores
 * {@code null} — never 0. Under-discovered junk is still junk, and "we could not measure
 * this" must never render as "we measured this and it was bad" (B-019 lineage, Gotcha 21).
 */
@Slf4j
@Service
public class UnderDiscoveryService {

    /** Below this composite, under-discovery is not computed — cheapness is not a thesis. */
    static final int QUALITY_GATE_COMPOSITE = 55;

    /**
     * Combined FII+DII holding above which the stock is no longer under-owned, so
     * institutions adding to it is ordinary flow rather than early discovery (B-043).
     * Matches the ceiling of the lowest scoring tier above — the same 10% line.
     */
    static final double LOW_BASE_CEILING = 10.0;

    /** Maximum points each component can contribute. Used to renormalise when one is absent. */
    private static final int PTS_LOW_INSTITUTIONAL = 25;
    private static final int PTS_RISING_INSTITUTIONAL = 20;
    private static final int PTS_MARKET_CAP = 20;
    private static final int PTS_DELIVERY = 15;
    private static final int PTS_VOLUME = 10;
    private static final int PTS_COVERAGE = 10;

    /**
     * Compute the under-discovery score for one stock.
     *
     * @param composite            the stock's composite score (drives the quality gate)
     * @param financialQualityVerdict HIGH_QUALITY / DECENT / AVERAGE / WEAK / HIGH_RISK, may be null
     * @param shareholding         NSE shareholding history; null means the two ownership
     *                             components cannot be measured and are renormalised away
     * @param marketCapCategory    SMALL_CAP / MID_CAP / LARGE_CAP / UNKNOWN
     * @param marketCapCrores      market cap, used only to separate micro-cap from small-cap
     * @param deliveryVerdict      STRONG_HANDS / ... from wealth signals, may be null
     * @param avgVolumeRatio       recent volume vs longer average; &gt;1 means expanding
     * @param newsArticleCount7d   articles in the last 7 days, or <b>null</b> during bulk
     *                             screening where fetching news per stock is too expensive
     * @param reasons              populated with human-readable drivers (may be null)
     * @return 0-100, or null when not computed
     */
    public Integer compute(int composite,
                           String financialQualityVerdict,
                           NseDataService.ShareholdingHistory shareholding,
                           String marketCapCategory,
                           Double marketCapCrores,
                           String deliveryVerdict,
                           Double avgVolumeRatio,
                           Integer newsArticleCount7d,
                           List<String> reasons) {

        if (composite < QUALITY_GATE_COMPOSITE) return null;
        if ("WEAK".equals(financialQualityVerdict) || "HIGH_RISK".equals(financialQualityVerdict)) return null;

        List<String> drivers = reasons != null ? reasons : new ArrayList<>();
        int earned = 0;
        int available = 0;

        // --- 1. Low ABSOLUTE institutional holding: the room for a re-rating to happen in ---
        // Both legs must be present (B-043). Treating a null FII as 0% would report a stock
        // with unknown foreign ownership and 1% DII as "barely institutionally owned" — a
        // measurement gap rendered as the strongest finding this lens can make (Gotcha 21).
        Double fii = latestFii(shareholding);
        Double dii = latestDii(shareholding);
        Double inst = (fii != null && dii != null) ? fii + dii : null;
        if (inst != null) {
            available += PTS_LOW_INSTITUTIONAL;
            if (inst < 2.0) {
                earned += 25;
                drivers.add(String.format("Barely institutionally owned (%.1f%% FII+DII) — room to be discovered", inst));
            } else if (inst < 5.0) {
                earned += 15;
                drivers.add(String.format("Low institutional ownership (%.1f%% FII+DII)", inst));
            } else if (inst < 10.0) {
                earned += 8;
                drivers.add(String.format("Modest institutional ownership (%.1f%% FII+DII)", inst));
            }

            // --- 2. ...and it is RISING *from that low base* (someone is arriving) ---
            // The gate is the whole point (B-043). Institutions adding to a 40%-owned index
            // heavyweight is ordinary flow, not discovery; awarding it here rewarded exactly
            // the well-followed stocks the lens exists to look past. Above the threshold the
            // component is measured and scores zero — that is a finding, not a gap.
            available += PTS_RISING_INSTITUTIONAL;
            if (inst < LOW_BASE_CEILING) {
                double fiiUp = shareholding.getFiiChange() != null ? shareholding.getFiiChange() : 0;
                double diiUp = shareholding.getDiiChange() != null ? shareholding.getDiiChange() : 0;
                int rising = 0;
                if (fiiUp > 0) rising += 10;
                if (diiUp > 0) rising += 10;
                if (fiiUp > 0 && diiUp > 0) rising += 5;   // both sides accumulating
                earned += Math.min(PTS_RISING_INSTITUTIONAL, rising);
                if (rising > 0) {
                    drivers.add(String.format("Institutions accumulating from a low base (FII %+.2f pp, DII %+.2f pp)", fiiUp, diiUp));
                }
            }
        }

        // --- 3. Size: a re-rating moves a small base much further ---
        if (marketCapCategory != null && !"UNKNOWN".equals(marketCapCategory)) {
            available += PTS_MARKET_CAP;
            if (marketCapCrores != null && marketCapCrores > 0 && marketCapCrores <= 1000) {
                earned += 20;
                drivers.add("Micro-cap (< Rs 1,000 cr) — smallest base, largest potential re-rating");
            } else if ("SMALL_CAP".equals(marketCapCategory)) {
                earned += 10;
                drivers.add("Small-cap");
            } else if ("MID_CAP".equals(marketCapCategory)) {
                earned += 3;
            }
        }

        // --- 4. Delivery %: genuine accumulation rather than intraday churn ---
        if (deliveryVerdict != null) {
            available += PTS_DELIVERY;
            if ("STRONG_HANDS".equals(deliveryVerdict)) {
                earned += 15;
                drivers.add("High delivery % — buyers are taking real ownership, not trading");
            }
        }

        // --- 5. Volume expanding: the quiet accumulation becoming visible ---
        if (avgVolumeRatio != null && avgVolumeRatio > 0) {
            available += PTS_VOLUME;
            if (avgVolumeRatio > 1.2) {
                earned += 10;
                drivers.add(String.format("Volume expanding (%.1fx its own average)", avgVolumeRatio));
            } else if (avgVolumeRatio > 1.0) {
                earned += 5;
            }
        }

        // --- 6. Under-followed: nobody is writing about it yet ---
        // Null during bulk screening: 361 Google-News RSS fetches per run is not a
        // reasonable cost, so the component is renormalised away rather than guessed.
        if (newsArticleCount7d != null) {
            available += PTS_COVERAGE;
            if (newsArticleCount7d <= 2) {
                earned += 10;
                drivers.add("Almost no media coverage in the last 7 days");
            } else if (newsArticleCount7d <= 5) {
                earned += 5;
            }
        }

        if (available == 0) return null;   // nothing at all could be measured

        // Renormalise over the components we could actually measure, exactly as the
        // composite does for its nullable dimensions. A stock is never penalised for
        // NSE's publishing gaps.
        int score = (int) Math.round(100.0 * earned / available);
        return Math.max(0, Math.min(100, score));
    }

    private Double latestFii(NseDataService.ShareholdingHistory sh) {
        if (sh == null || sh.getQuarters() == null || sh.getQuarters().isEmpty()) return null;
        return sh.getQuarters().get(0).getFiiHolding();
    }

    private Double latestDii(NseDataService.ShareholdingHistory sh) {
        if (sh == null || sh.getQuarters() == null || sh.getQuarters().isEmpty()) return null;
        return sh.getQuarters().get(0).getDiiHolding();
    }
}
