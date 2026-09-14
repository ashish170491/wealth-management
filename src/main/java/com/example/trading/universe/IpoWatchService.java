package com.example.trading.universe;

import com.example.trading.ai.NseDataService;
import com.example.trading.marketdata.MarketDataService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Recent-listing tracker (SPEC §30.4, plan feature F3).
 *
 * <p>Multibaggers emerge disproportionately from one-to-three-year-old listings, but almost
 * never during the hype window. The setup worth watching is the opposite of the IPO itself:
 * <b>at least six months after listing, price back above the listing-day high, and a base of
 * higher lows</b> — hype dead, early holders washed out, strength returning.
 *
 * <p>Everything before that is deliberately not surfaced. A stock two months post-listing
 * trading above issue price is a story, not a signal, and this system has no way to tell the
 * difference.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpoWatchService {

    private final NseDataService nseDataService;
    private final MarketDataService marketDataService;
    private final UniverseConfig config;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Minimum candles needed to judge a base. */
    private static final int MIN_HISTORY = 120;

    /**
     * Recent listings, newest first, each annotated with whether the post-IPO base setup is
     * present.
     *
     * @param onlySetups when true, return only listings that actually show the setup
     */
    public List<IpoCandidate> recentListings(boolean onlySetups) {
        List<NseDataService.EquityListing> listings = nseDataService.fetchEquityList();
        if (listings.isEmpty()) return List.of();

        LocalDate cutoff = LocalDate.now().minusMonths(config.getRecentIpoMonths());
        LocalDate matured = LocalDate.now().minusMonths(config.getMinMonthsSinceListing());

        List<NseDataService.EquityListing> recent = listings.stream()
                .filter(NseDataService.EquityListing::isMainboardEquity)
                .filter(l -> l.listingDate() != null && l.listingDate().isAfter(cutoff))
                .sorted(Comparator.comparing(NseDataService.EquityListing::listingDate).reversed())
                .toList();

        List<IpoCandidate> out = new ArrayList<>();
        for (NseDataService.EquityListing l : recent) {
            try {
                IpoCandidate c = evaluate(l, matured);
                if (c == null) continue;
                if (onlySetups && !c.isSetupPresent()) continue;
                out.add(c);
            } catch (Exception e) {
                log.debug("IPO watch failed for {}: {}", l.symbol(), e.getMessage());
            }
        }
        log.info("IPO watch: {} mainboard listings in the last {} months, {} returned{}",
                recent.size(), config.getRecentIpoMonths(), out.size(),
                onlySetups ? " (setups only)" : "");
        return out;
    }

    private IpoCandidate evaluate(NseDataService.EquityListing l, LocalDate matured) {
        long monthsListed = ChronoUnit.MONTHS.between(l.listingDate(), LocalDate.now());
        boolean mature = l.listingDate().isBefore(matured);

        List<Map<String, Object>> history = candles(l.qualifiedSymbol());
        if (history == null || history.isEmpty()) {
            // Still worth listing — we know it exists and when it listed, we just cannot
            // judge the chart. Reported as unmeasured, never as "no setup".
            return IpoCandidate.builder()
                    .symbol(l.qualifiedSymbol())
                    .companyName(l.companyName())
                    .listingDate(l.listingDate())
                    .monthsSinceListing(monthsListed)
                    .matured(mature)
                    .dataAvailable(false)
                    .reason("No usable price history from the broker for this symbol")
                    .build();
        }

        double price = num(history.get(history.size() - 1).get("close"));
        double listingHigh = num(history.get(0).get("high"));
        boolean aboveListingHigh = listingHigh > 0 && price > listingHigh;
        boolean base = UniverseExpansionService.hasHigherLows(history);
        boolean enoughHistory = history.size() >= MIN_HISTORY;

        boolean setup = mature && aboveListingHigh && base && enoughHistory;

        return IpoCandidate.builder()
                .symbol(l.qualifiedSymbol())
                .companyName(l.companyName())
                .listingDate(l.listingDate())
                .monthsSinceListing(monthsListed)
                .matured(mature)
                .dataAvailable(true)
                .currentPrice(price)
                .listingDayHigh(listingHigh > 0 ? listingHigh : null)
                .aboveListingDayHigh(aboveListingHigh)
                .baseFormed(base)
                .setupPresent(setup)
                .reason(describe(mature, aboveListingHigh, base, enoughHistory))
                .build();
    }

    private String describe(boolean mature, boolean aboveHigh, boolean base, boolean enoughHistory) {
        if (!enoughHistory) return "Too little trading history yet to judge a base";
        if (!mature) return "Still inside the first " + config.getMinMonthsSinceListing()
                + " months — hype window, deliberately not evaluated";
        if (!aboveHigh) return "Below its listing-day high — the washout has not resolved upward yet";
        if (!base) return "Above its listing-day high, but no base of higher lows yet";
        return "Post-IPO base: matured, above listing-day high, higher lows forming";
    }

    private List<Map<String, Object>> candles(String symbol) {
        try {
            String to = LocalDate.now().atTime(15, 30).format(FMT);
            String from = LocalDate.now().minusDays(1200).atTime(9, 15).format(FMT);
            return marketDataService.getRecentCandles(symbol, "day", from, to);
        } catch (Exception e) {
            return null;
        }
    }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    @Data
    @Builder
    public static class IpoCandidate {
        private String symbol;
        private String companyName;
        private LocalDate listingDate;
        private long monthsSinceListing;
        /** Past the hype window (>= minMonthsSinceListing). */
        private boolean matured;
        private boolean dataAvailable;
        private Double currentPrice;
        private Double listingDayHigh;
        private boolean aboveListingDayHigh;
        private boolean baseFormed;
        private boolean setupPresent;
        private String reason;
    }
}
