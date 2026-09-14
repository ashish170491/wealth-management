package com.example.trading.analyst;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Talks to the public broker-research feed and its price feed (SPEC §49.11).
 *
 * <p>The only class here that touches the network. Two calls exist: a paged list of
 * recommendations, and a one-off stock-id lookup whose answer is kept for ever in
 * {@link BrokerSymbolEntity}.
 *
 * <p><b>Pacing is process-wide and static, for the reason the NSE and Kite gates are</b>
 * (Gotcha 23, 101): two independently paced loops on two of the four scheduler threads each
 * respect their own budget and together break the real one. A full backfill is roughly eighty
 * list calls plus one lookup per company ever seen; the daily pass is one or two calls, because
 * it stops as soon as it reaches a row already on file.
 *
 * <p><b>The buffer is set deliberately</b> (B-054): the shared {@code WebClient.Builder} inherits
 * Spring's 256 KB default, and a hundred-row page with its nested history comfortably exceeds it.
 * The failure mode there is an exception caught somewhere upstream and an empty list that reads
 * as "the brokerages published nothing", which is the emptiness this whole feature exists to
 * distinguish from a real quiet week.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrokerResearchClient {

    private final WebClient.Builder webClientBuilder;
    private final BrokerSymbolRepository symbolRepository;
    private final AnalystTargetConfig config;

    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private static final Object PACE_LOCK = new Object();
    private static volatile long lastCallAt = 0L;

    private volatile WebClient client;

    /**
     * Hold the shared budget open for one request.
     *
     * <p>Blocking is correct here and not in the reactive chain: these are scheduler and
     * manual-endpoint paths, never a page load, and the alternative — letting a backfill issue
     * eighty requests as fast as the socket allows — is how a courteous client becomes an
     * impolite one.
     */
    private void pace() {
        long wait;
        synchronized (PACE_LOCK) {
            long now = System.currentTimeMillis();
            long earliest = lastCallAt + Math.max(0, config.getFeedPaceMs());
            wait = Math.max(0L, earliest - now);
            lastCallAt = Math.max(now, earliest);
        }
        if (wait <= 0) return;
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private WebClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = webClientBuilder
                            .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                            .defaultHeader("User-Agent", config.getFeedUserAgent())
                            .defaultHeader("Accept", "application/json, text/plain, */*")
                            .defaultHeader("Referer", config.getFeedReferer())
                            .build();
                }
            }
        }
        return client;
    }

    /**
     * The outcome of one page request.
     *
     * <p><b>An empty list is two different facts and they must not share a type.</b> "The archive
     * ends here" and "this request failed" both produce no rows, and a caller that cannot tell
     * them apart stops walking on the first hiccup and reports a complete run. That happened on
     * the very first backfill: one page 400'd, the empty list read as end-of-archive, and the pass
     * stopped at 40% of the archive while logging success. It is B-054's rule one level up - the
     * WARN was there, but the control flow could not act on it.
     */
    public record Page(List<BrokerResearchRow> rows, boolean failed) {
        static Page of(List<BrokerResearchRow> rows) {
            return new Page(rows, false);
        }

        static Page failure() {
            return new Page(List.of(), true);
        }

        public boolean endOfArchive() {
            return !failed && rows.isEmpty();
        }
    }

    /**
     * One page of recommendations, newest first, splitting on failure.
     *
     * <p>A page can fail deterministically for its <em>size</em> rather than its position: the
     * live feed rejects {@code start=3300&limit=100} with a 400 while serving both 3200 and 3400
     * at the same size, and serving 3300 itself at limit 50. One row in that slice breaks the
     * publisher's own serialisation at one particular boundary. Halving the request steps around
     * it, so a single bad row costs at most a few rows rather than the rest of the archive.
     */
    public Page fetchPageSplitting(int start, int limit) {
        Page direct = fetchPage(start, limit);
        if (!direct.failed() || limit <= MIN_SPLIT_LIMIT) return direct;

        int half = Math.max(MIN_SPLIT_LIMIT, limit / 2);
        log.info("Analyst ledger: page start={} limit={} failed; retrying as two requests of {}",
                start, limit, half);

        Page first = fetchPage(start, half);
        Page second = fetchPage(start + half, limit - half);
        if (first.failed() && second.failed()) return Page.failure();

        List<BrokerResearchRow> merged = new ArrayList<>(first.rows());
        merged.addAll(second.rows());
        if (first.failed() || second.failed()) {
            log.warn("Analyst ledger: half of page start={} still failed - {} rows recovered, "
                    + "the rest of that slice is skipped rather than ending the walk", start, merged.size());
        }
        return Page.of(merged);
    }

    /** Below this a split is not worth another round trip; the slice is abandoned instead. */
    private static final int MIN_SPLIT_LIMIT = 10;

    /**
     * One page of recommendations, newest first.
     *
     * @return the rows, or a failure marker - never an ambiguous empty list
     */
    public Page fetchPage(int start, int limit) {
        String url = config.getFeedBaseUrl()
                + "/stock-ideas?start=" + Math.max(0, start)
                + "&limit=" + Math.max(1, limit)
                + "&deviceType=W";
        pace();
        try {
            JsonNode root = client().get().uri(url)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(config.getFeedTimeoutSeconds()))
                    .block();

            if (root == null) {
                log.warn("Analyst ledger: research feed returned nothing for start={} - "
                        + "this reads downstream as 'no brokerage published anything', which is "
                        + "why it is logged rather than swallowed", start);
                return Page.failure();
            }
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) return Page.failure();

            List<BrokerResearchRow> out = new ArrayList<>();
            int skipped = 0;
            for (JsonNode node : data) {
                BrokerResearchRow row = BrokerResearchParser.parse(node);
                if (row == null) {
                    skipped++;
                    continue;
                }
                out.add(row);
            }
            if (skipped > 0) {
                log.debug("Analyst ledger: {} of {} feed rows at start={} carried no usable "
                        + "target/house/date", skipped, data.size(), start);
            }
            return Page.of(out);
        } catch (Exception e) {
            log.warn("Analyst ledger: research feed page start={} limit={} failed: {} - reported "
                    + "as a FAILURE, never as the end of the archive", start, limit, e.toString());
            return Page.failure();
        }
    }

    /**
     * The NSE tradingsymbol behind a feed stock id, unprefixed.
     *
     * <p>Cached permanently, including misses. The feed ships only its own id and a truncated
     * display label, so this lookup is the single bridge between the two vocabularies — and a
     * company's id never changes, which is what makes caching it for ever correct rather than
     * merely convenient.
     *
     * @return the symbol, or empty when this id has no NSE listing
     */
    public Optional<String> resolveNseSymbol(String scid) {
        if (scid == null || scid.isBlank()) return Optional.empty();
        String key = scid.trim();

        Optional<BrokerSymbolEntity> known = symbolRepository.findByScid(key);
        if (known.isPresent()) {
            // A cached row with a null symbol is a recorded "no NSE listing", not a cache miss.
            return Optional.ofNullable(known.get().getNseSymbol());
        }

        String symbol = null;
        String company = null;
        pace();
        try {
            JsonNode root = client().get()
                    .uri(config.getPriceFeedUrl() + "/" + key)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(config.getFeedTimeoutSeconds()))
                    .block();
            JsonNode data = root == null ? null : root.get("data");
            if (data != null && data.isObject()) {
                symbol = clean(BrokerResearchParser.text(data.get("NSEID")));
                company = BrokerResearchParser.text(data.get("company"));
            }
        } catch (Exception e) {
            // Do NOT cache a transport failure as "no listing" - that is the B-054 mistake of
            // recording a fetch failure as a fact about the world. Return empty and retry later.
            log.debug("Analyst ledger: symbol lookup for {} failed: {}", key, e.toString());
            return Optional.empty();
        }

        try {
            symbolRepository.save(BrokerSymbolEntity.builder()
                    .scid(key)
                    .nseSymbol(symbol)
                    .companyName(company)
                    .resolvedAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.debug("Analyst ledger: could not cache symbol map for {}: {}", key, e.getMessage());
        }
        return Optional.ofNullable(symbol);
    }

    /** The first of a row's ids that maps to a listed NSE symbol. */
    public Optional<String> resolveAny(List<String> scids) {
        if (scids == null) return Optional.empty();
        for (String scid : scids) {
            Optional<String> hit = resolveNseSymbol(scid);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }
}
