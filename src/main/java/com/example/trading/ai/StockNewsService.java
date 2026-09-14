package com.example.trading.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches recent stock news from Google News RSS feed.
 * Used by StockResearchService for AI-powered deep research.
 */
@Service
@Slf4j
public class StockNewsService {

    private final WebClient webClient;

    private static final Pattern TITLE_PATTERN = Pattern.compile("<title><!\\[CDATA\\[(.+?)\\]\\]></title>|<title>(.+?)</title>");
    private static final Pattern SOURCE_PATTERN = Pattern.compile("<source[^>]*>(.+?)</source>");
    private static final Pattern PUB_DATE_PATTERN = Pattern.compile("<pubDate>(.+?)</pubDate>");

    public StockNewsService() {
        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .defaultHeader(HttpHeaders.ACCEPT, "application/xml, text/xml, */*")
                .build();
    }

    /**
     * Fetch recent news headlines for a stock from Google News RSS.
     *
     * @param tradingSymbol Stock symbol without exchange prefix (e.g., RELIANCE)
     * @param companyName Optional company name for better search results
     * @return List of news items (title, source, date)
     */
    public List<NewsItem> fetchRecentNews(String tradingSymbol, String companyName) {
        List<NewsItem> news = new ArrayList<>();
        try {
            String query = buildSearchQuery(tradingSymbol, companyName);
            String url = "https://news.google.com/rss/search?q=" + query + "&hl=en-IN&gl=IN&ceid=IN:en";

            log.debug("Fetching news for {} from Google News RSS", tradingSymbol);

            String rssXml = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));

            if (rssXml != null) {
                news = parseRssFeed(rssXml);
                log.debug("Found {} news items for {}", news.size(), tradingSymbol);
            }
        } catch (Exception e) {
            log.debug("News fetch failed for {}: {}", tradingSymbol, e.getMessage());
        }
        return news;
    }

    private String buildSearchQuery(String tradingSymbol, String companyName) {
        // Build search query: "RELIANCE stock" OR "Reliance Industries"
        StringBuilder q = new StringBuilder();
        q.append(tradingSymbol).append("+stock+India");
        if (companyName != null && !companyName.isEmpty()) {
            q.append("+OR+").append(companyName.replace(" ", "+"));
        }
        // Limit to recent news
        q.append("&when:7d");
        return q.toString();
    }

    private List<NewsItem> parseRssFeed(String xml) {
        List<NewsItem> items = new ArrayList<>();

        // Split by <item> tags
        String[] parts = xml.split("<item>");
        // Skip first part (channel header), limit to 10 items
        for (int i = 1; i < Math.min(parts.length, 11); i++) {
            String item = parts[i];

            String title = extractFirst(TITLE_PATTERN, item);
            String source = extractFirst(SOURCE_PATTERN, item);
            String pubDate = extractFirst(PUB_DATE_PATTERN, item);

            if (title != null && !title.isEmpty()) {
                // Clean HTML entities
                title = title.replace("&amp;", "&").replace("&lt;", "<")
                        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
                items.add(new NewsItem(title, source, pubDate));
            }
        }
        return items;
    }

    private String extractFirst(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }

    public record NewsItem(String title, String source, String pubDate) {}

    // ================================================================
    // Brokerage action detection (SPEC §24 — analyst-signal proxy)
    // ================================================================

    /** Major Indian sell-side brokerages. Matched case-insensitively; used to gate "raises/cuts target" headlines. */
    private static final List<String> BROKERAGES = List.of(
            "Motilal Oswal", "Nomura", "CLSA", "Jefferies", "Morgan Stanley", "Goldman Sachs",
            "JP Morgan", "JPMorgan", "Citi", "Credit Suisse", "Kotak", "ICICI Securities",
            "ICICI Direct", "HDFC Securities", "Axis Securities", "Axis Capital",
            "Nirmal Bang", "Prabhudas Lilladher", "Emkay", "Elara", "Anand Rathi",
            "Sharekhan", "Geojit", "Yes Securities", "IIFL", "Bernstein", "Macquarie",
            "Bank of America", "BofA", "UBS", "Deutsche Bank", "BNP Paribas", "Antique",
            "IDBI Capital", "IDBI", "Centrum", "JM Financial", "Phillip Capital",
            "Dolat Capital", "InCred", "Choice Broking");

    private static final Pattern UPGRADE_PATTERN = Pattern.compile(
            "(?i)\\b(upgrade[sd]?|raise[sd]?\\s+target|hike[sd]?\\s+target|initiate[sd]?\\s+(?:with\\s+)?(?:buy|overweight|outperform)|reiterate[sd]?\\s+buy|price\\s+target\\s+(?:raised|hiked))\\b");
    private static final Pattern DOWNGRADE_PATTERN = Pattern.compile(
            "(?i)\\b(downgrade[sd]?|cut[s]?\\s+target|reduce[sd]?\\s+target|slash(?:e[sd])?\\s+target|lower[sd]?\\s+target|price\\s+target\\s+(?:cut|reduced|slashed))\\b");
    private static final Pattern TARGET_AMOUNT_PATTERN = Pattern.compile(
            "(?i)(?:target|tp)(?:\\s+(?:of|at|raised\\s+to|cut\\s+to|to))?\\s*(?:Rs\\.?|₹|INR)\\s*([0-9]{2,5}(?:[.,][0-9]{1,3})?)");

    /**
     * Scan recent news for sell-side brokerage action on a stock. Strict filter
     * to cut false positives: a headline is only counted as an action if
     * (a) it contains an upgrade/downgrade verb pattern, AND
     * (b) it either names a brokerage from {@link #BROKERAGES} OR carries an explicit "target Rs X" amount.
     *
     * This is a keyword-match proxy for paid analyst-consensus feeds, and is
     * expected to miss ~30–40% of real actions and produce occasional false
     * positives. Callers should weight it low (≤ 5 points) and never use it as
     * a sole trigger.
     */
    public BrokerageActionData detectBrokerageActions(String tradingSymbol, String companyName) {
        List<NewsItem> news = fetchRecentNews(tradingSymbol, companyName);
        BrokerageActionData out = new BrokerageActionData();
        out.setSymbol(tradingSymbol);
        out.setActions(new ArrayList<>());
        if (news.isEmpty()) {
            out.setVerdict("NO_COVERAGE");
            return out;
        }

        int upgrades = 0;
        int downgrades = 0;
        for (NewsItem item : news) {
            String title = item.title();
            if (title == null || title.isBlank()) continue;

            boolean hasUpgrade = UPGRADE_PATTERN.matcher(title).find();
            boolean hasDowngrade = DOWNGRADE_PATTERN.matcher(title).find();
            if (!hasUpgrade && !hasDowngrade) continue;

            String brokerage = findBrokerage(title);
            Double targetAmt = findTargetAmount(title);

            // Strict filter: require either a named brokerage OR an explicit target amount
            if (brokerage == null && targetAmt == null) continue;

            // Skip ambiguous "upgrade AND downgrade" headlines (cross-brokerage news)
            if (hasUpgrade && hasDowngrade) continue;

            String action = hasUpgrade ? "UPGRADE" : "DOWNGRADE";
            BrokerageAction a = new BrokerageAction();
            a.setAction(action);
            a.setBrokerage(brokerage != null ? brokerage : "unnamed");
            a.setTargetPriceInr(targetAmt);
            a.setHeadline(title);
            a.setSource(item.source());
            a.setPubDate(item.pubDate());
            out.getActions().add(a);

            if ("UPGRADE".equals(action)) upgrades++;
            else downgrades++;
        }

        out.setUpgradeCount(upgrades);
        out.setDowngradeCount(downgrades);
        out.setNetScore(upgrades - downgrades);
        out.setVerdict(classifyBrokerageNet(upgrades, downgrades));
        return out;
    }

    private String findBrokerage(String title) {
        String lower = title.toLowerCase();
        for (String b : BROKERAGES) {
            if (lower.contains(b.toLowerCase())) return b;
        }
        return null;
    }

    private Double findTargetAmount(String title) {
        Matcher m = TARGET_AMOUNT_PATTERN.matcher(title);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1).replace(",", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private String classifyBrokerageNet(int upgrades, int downgrades) {
        int net = upgrades - downgrades;
        if (upgrades + downgrades == 0) return "NO_COVERAGE";
        if (net >= 2) return "POSITIVE_FLOW";
        if (net >= 1) return "MIXED_POSITIVE";
        if (net <= -2) return "NEGATIVE_FLOW";
        if (net <= -1) return "MIXED_NEGATIVE";
        return "NEUTRAL";
    }

    @lombok.Data
    public static class BrokerageActionData {
        private String symbol;
        private List<BrokerageAction> actions;
        private int upgradeCount;
        private int downgradeCount;
        /** net = upgrades − downgrades. Used as the raw signal; bucketed into {@link #verdict}. */
        private int netScore;
        /** POSITIVE_FLOW / MIXED_POSITIVE / NEUTRAL / MIXED_NEGATIVE / NEGATIVE_FLOW / NO_COVERAGE. */
        private String verdict;
    }

    @lombok.Data
    public static class BrokerageAction {
        private String action;           // UPGRADE / DOWNGRADE
        private String brokerage;        // matched brokerage name, or "unnamed"
        private Double targetPriceInr;   // extracted from headline, may be null
        private String headline;
        private String source;
        private String pubDate;
    }
}
