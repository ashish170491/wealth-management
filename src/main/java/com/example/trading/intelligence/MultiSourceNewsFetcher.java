package com.example.trading.intelligence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import reactor.core.publisher.Mono;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Aggregates news from multiple RSS sources in parallel.
 * Each source has its own circuit breaker for fault tolerance.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MultiSourceNewsFetcher {

    private final WebClient.Builder webClientBuilder;

    /**
     * Raw news item parsed from any RSS source.
     */
    public record RawNewsItem(
            String title,
            String description,
            String url,
            String source,
            LocalDateTime publishedAt
    ) {}

    // RSS sources
    private static final Map<String, String> RSS_SOURCES = Map.of(
            "Economic Times", "https://economictimes.indiatimes.com/rssfeedstopstories.cms",
            "Moneycontrol", "https://www.moneycontrol.com/rss/latestnews.xml",
            "LiveMint", "https://www.livemint.com/rss/markets",
            "Google News India", "https://news.google.com/rss/search?q=India+stock+market+OR+RBI+OR+Nifty+OR+Sensex&hl=en-IN&gl=IN&ceid=IN:en"
    );

    // Multiple date formats used across RSS feeds
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ENGLISH)
    );

    // Circuit breaker state per source
    private final ConcurrentHashMap<String, AtomicInteger> failureCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDateTime> circuitOpenedAt = new ConcurrentHashMap<>();
    private static final int FAILURE_THRESHOLD = 3;
    private static final int CIRCUIT_RESET_MINUTES = 30;

    /**
     * Fetch news from all RSS sources published since a point in time. Skips sources whose circuit
     * breaker is open.
     *
     * <p>Takes an instant rather than "the last N hours" (B-105). The caller passes the timestamp
     * of the newest headline it already holds, so a gap fills itself and nothing is missed between
     * two runs. The old fixed two-hour window meant the first scan of the day - which fires at
     * 09:15 - could only see back to 07:15, and every overnight story was therefore invisible.
     */
    public List<RawNewsItem> fetchAllSources(LocalDateTime since) {
        List<RawNewsItem> allItems = new ArrayList<>();
        LocalDateTime cutoff = since == null ? LocalDateTime.now().minusHours(24) : since;

        for (Map.Entry<String, String> entry : RSS_SOURCES.entrySet()) {
            String sourceName = entry.getKey();
            String url = entry.getValue();

            if (!isSourceAvailable(sourceName)) {
                continue;
            }

            try {
                List<RawNewsItem> items = fetchSingleSource(sourceName, url, cutoff);
                allItems.addAll(items);
                recordSuccess(sourceName);
            } catch (Exception e) {
                recordFailure(sourceName);
                log.debug("Failed to fetch from {}: {}", sourceName, e.getMessage());
            }
        }

        // Sort by published time descending (most recent first)
        allItems.sort((a, b) -> {
            if (a.publishedAt() == null && b.publishedAt() == null) return 0;
            if (a.publishedAt() == null) return 1;
            if (b.publishedAt() == null) return -1;
            return b.publishedAt().compareTo(a.publishedAt());
        });

        log.info("Fetched {} news items published since {} from {} sources",
                allItems.size(), cutoff, RSS_SOURCES.size());
        return allItems;
    }

    /**
     * Fetch and parse a single RSS feed.
     */
    private List<RawNewsItem> fetchSingleSource(String sourceName, String url, LocalDateTime cutoff) {
        String response = webClientBuilder.build().get()
                .uri(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/rss+xml, application/xml, text/xml")
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, r -> Mono.empty())
                .onStatus(HttpStatusCode::is5xxServerError, r -> Mono.empty())
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.empty())
                .block();

        if (response == null || response.isEmpty()) {
            throw new RuntimeException("Empty response from " + sourceName);
        }

        List<RawNewsItem> items = parseRssFeed(response, sourceName);

        // Filter by age
        List<RawNewsItem> recent = items.stream()
                .filter(item -> item.publishedAt() == null || item.publishedAt().isAfter(cutoff))
                .toList();

        log.debug("Fetched {} items from {} ({} recent)", items.size(), sourceName, recent.size());
        return recent;
    }

    /**
     * Parse RSS XML into RawNewsItem list. Handles standard RSS 2.0 and Atom formats.
     */
    private List<RawNewsItem> parseRssFeed(String xml, String sourceName) {
        List<RawNewsItem> items = new ArrayList<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            // Try RSS 2.0 format first (<item> elements)
            NodeList rssItems = doc.getElementsByTagName("item");
            if (rssItems.getLength() > 0) {
                for (int i = 0; i < rssItems.getLength(); i++) {
                    Element item = (Element) rssItems.item(i);
                    items.add(parseRssItem(item, sourceName));
                }
                return items;
            }

            // Try Atom format (<entry> elements)
            NodeList atomEntries = doc.getElementsByTagName("entry");
            for (int i = 0; i < atomEntries.getLength(); i++) {
                Element entry = (Element) atomEntries.item(i);
                items.add(parseAtomEntry(entry, sourceName));
            }

        } catch (Exception e) {
            log.debug("Error parsing RSS from {}: {}", sourceName, e.getMessage());
        }

        return items;
    }

    private RawNewsItem parseRssItem(Element item, String sourceName) {
        String title = cleanHtml(getElementText(item, "title"));
        String description = cleanHtml(getElementText(item, "description"));
        String url = getElementText(item, "link");
        String pubDate = getElementText(item, "pubDate");

        return new RawNewsItem(title, description, url, sourceName, parseDate(pubDate));
    }

    private RawNewsItem parseAtomEntry(Element entry, String sourceName) {
        String title = cleanHtml(getElementText(entry, "title"));
        String description = cleanHtml(getElementText(entry, "summary"));
        if (description.isEmpty()) {
            description = cleanHtml(getElementText(entry, "content"));
        }
        String url = "";
        NodeList links = entry.getElementsByTagName("link");
        if (links.getLength() > 0) {
            Element linkEl = (Element) links.item(0);
            url = linkEl.getAttribute("href");
            if (url.isEmpty()) url = linkEl.getTextContent().trim();
        }
        String updated = getElementText(entry, "updated");
        if (updated.isEmpty()) updated = getElementText(entry, "published");

        return new RawNewsItem(title, description, url, sourceName, parseDate(updated));
    }

    private String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return "";
    }

    private String cleanHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        return html.replaceAll("<[^>]*>", "").replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&").replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
    }

    private LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return LocalDateTime.now();

        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                ZonedDateTime zdt = ZonedDateTime.parse(dateStr.trim(), formatter);
                return zdt.toLocalDateTime();
            } catch (Exception ignored) {}
        }

        log.trace("Could not parse date: {}", dateStr);
        return LocalDateTime.now();
    }

    // Circuit breaker per source
    private boolean isSourceAvailable(String source) {
        LocalDateTime openedAt = circuitOpenedAt.get(source);
        if (openedAt != null) {
            if (LocalDateTime.now().isAfter(openedAt.plusMinutes(CIRCUIT_RESET_MINUTES))) {
                circuitOpenedAt.remove(source);
                failureCounts.remove(source);
                log.info("Circuit breaker reset for {}", source);
                return true;
            }
            return false;
        }
        return true;
    }

    private void recordFailure(String source) {
        int failures = failureCounts.computeIfAbsent(source, k -> new AtomicInteger(0)).incrementAndGet();
        if (failures >= FAILURE_THRESHOLD) {
            circuitOpenedAt.put(source, LocalDateTime.now());
            log.warn("Circuit breaker OPENED for {} after {} failures. Retry in {} min.",
                    source, failures, CIRCUIT_RESET_MINUTES);
        }
    }

    private void recordSuccess(String source) {
        failureCounts.remove(source);
        circuitOpenedAt.remove(source);
    }
}
