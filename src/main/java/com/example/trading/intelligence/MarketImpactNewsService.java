package com.example.trading.intelligence;

import com.example.trading.intelligence.MultiSourceNewsFetcher.RawNewsItem;
import com.example.trading.macro.MacroKeywordExtractor;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Captures headlines from the news feeds into {@code market_impact_news} (SPEC §48.2).
 *
 * <p><b>This class stores headlines. It no longer says anything about them.</b> Until 2026-09-10 it
 * also classified each one into an impact level and emailed an alert within fifteen minutes,
 * telling the investor to "review open positions immediately", "adjust stop-losses if needed" and
 * "check the option chain for unusual OI buildup" (B-101). That is intraday advice, in an
 * application whose entire purpose is to help someone hold good businesses for five to ten years,
 * and SPEC §19 bars it. Both emails are gone. What replaced them is a digest: a section in the
 * 09:30 briefing and one in the 15:18 action items, and a page the investor opens when they choose
 * to. Reading the news is useful; being interrupted by it every fifteen minutes is the opposite of
 * what this app is for.
 *
 * <p>What survives is the cheap half - four RSS feeds, no broker calls, no model, no email - so the
 * ledger has something to read whenever the investor presses the button.
 *
 * <p>Two defects were fixed in the same pass:
 * <ul>
 *   <li><b>B-105, the window.</b> The scan fetched "the last 2 hours", hard-coded. The first scan
 *       of the day runs at 09:15, so it could see back only to 07:15 and <b>every overnight story
 *       was invisible</b> - which is most of what moves an Indian portfolio, because the US closes
 *       and Asia opens while India sleeps. It now fetches since the newest headline it already
 *       holds, capped by configuration.</li>
 *   <li><b>B-105, the duplicates.</b> Deduplication hashed the raw title, and Google News appends
 *       " - Publisher" to every one of its titles, so the same story arriving from the aggregator
 *       and from the publisher's own feed was two rows. The title is normalised before hashing.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketImpactNewsService {

    private final MultiSourceNewsFetcher newsFetcher;
    private final MarketImpactNewsRepository newsRepository;
    private final MarketImpactNewsConfig config;
    private final MarketHoursService marketHoursService;

    /**
     * Pull the feeds every fifteen minutes during market hours.
     *
     * <p>Cheap by design: four RSS requests, no Kite call, no NSE call, no model and no email, so
     * it never competes for the broker budget that has twice starved the afternoon jobs (Gotcha 23).
     * The cron reads {@code 9-15}, which fires at 09:00 - outside the window - and the runtime guard
     * is what stops it (SPEC §3.4).
     */
    @Scheduled(cron = "0 */15 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledNewsCheck() {
        if (!marketHoursService.isMarketOpen()) {
            log.debug("Outside 09:15-15:30 IST window - skipping news scan (SPEC 3.4)");
            return;
        }
        if (!config.isEnabled()) {
            log.debug("trading.market-impact-news.enabled is false - skipping news scan");
            return;
        }
        scanOnce();
    }

    /**
     * Fetch, filter and store. Returns how many headlines were new.
     *
     * <p>A headline is stored only when {@link MacroKeywordExtractor#touchesAnyFactor} recognises
     * it, so the table holds market-relevant news rather than everything the feeds carry. That
     * filter is deliberately the same one the fallback extractor uses: a headline the app would
     * never be able to turn into an event is not worth the row.
     */
    public int scanOnce() {
        try {
            LocalDateTime since = fetchSince();
            java.util.List<RawNewsItem> items = newsFetcher.fetchAllSources(since);
            if (items.isEmpty()) {
                log.info("News scan: nothing new since {}", since);
                return 0;
            }

            int stored = 0;
            int skippedDuplicate = 0;
            int skippedIrrelevant = 0;

            for (RawNewsItem item : items) {
                if (item.title() == null || item.title().isBlank()) continue;

                String text = (item.title() + " " + (item.description() == null ? "" : item.description()))
                        .toLowerCase(Locale.ROOT);
                if (!MacroKeywordExtractor.touchesAnyFactor(text)) {
                    skippedIrrelevant++;
                    continue;
                }

                String hash = hashTitle(item.title());
                if (newsRepository.existsByTitleHash(hash)) {
                    skippedDuplicate++;
                    continue;
                }

                newsRepository.save(MarketImpactNewsEntity.builder()
                        .title(truncate(item.title(), 500))
                        .description(truncate(item.description(), 2000))
                        .source(item.source())
                        .url(truncate(item.url(), 1000))
                        .publishedAt(item.publishedAt())
                        .titleHash(hash)
                        .build());
                stored++;
            }

            log.info("News scan since {}: {} fetched, {} stored, {} already seen, {} not market news",
                    since, items.size(), stored, skippedDuplicate, skippedIrrelevant);
            return stored;

        } catch (Exception e) {
            log.error("News scan failed: {}", e.toString(), e);
            return 0;
        }
    }

    /**
     * Fetch everything published since the newest headline already stored, bounded by
     * {@code max-lookback-hours} so a first run or a long gap cannot try to pull a month of feeds.
     */
    private LocalDateTime fetchSince() {
        LocalDateTime cap = LocalDateTime.now().minusHours(Math.max(1, config.getMaxLookbackHours()));
        LocalDateTime newest = null;
        try {
            newest = newsRepository.findLatestPublishedAt();
        } catch (Exception e) {
            log.warn("Could not read the newest stored headline ({}), so the scan falls back to the "
                    + "{}-hour cap. Worst case it re-reads headlines it already has and the title hash "
                    + "discards them.", e.getMessage(), config.getMaxLookbackHours());
        }
        return newest == null || newest.isBefore(cap) ? cap : newest;
    }

    /**
     * SHA-256 of a normalised title.
     *
     * <p>Normalising matters more than it looks. Google News appends {@code " - Economic Times"} to
     * every title it serves, and the same story also arrives from the publisher's own feed without
     * it, so hashing the raw title stored the same headline twice and the event extractor then saw
     * one story as two pieces of evidence. Punctuation and spacing are flattened for the same
     * reason: a curly apostrophe is not a different story.
     */
    static String hashTitle(String title) {
        String normalised = normaliseTitle(title);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalised.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(normalised.hashCode());
        }
    }

    static String normaliseTitle(String title) {
        if (title == null) return "";
        String t = title.trim();
        // Strip a trailing " - Publisher" / " | Publisher" attribution, which the aggregator adds
        // and the publisher's own feed does not.
        t = t.replaceAll("\\s+[-|\u2013\u2014]\\s+[^-|\u2013\u2014]{1,40}$", "");
        return t.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\u2018\u2019\u201c\u201d]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Truncate for the column width.
     *
     * <p>This used to strip every non-ASCII character "in case the database cannot encode them",
     * which silently deleted the rupee sign from every headline that quoted a figure (B-106). The
     * database is UTF-8 and so is the build (B-085); there was nothing to protect against.
     */
    static String truncate(String value, int maxLength) {
        if (value == null) return null;
        String v = value.trim();
        return v.length() > maxLength ? v.substring(0, maxLength - 3) + "..." : v;
    }
}
