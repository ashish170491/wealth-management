package com.example.trading.universe.ipo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns NSE's three IPO payloads into typed values (SPEC §45.2). Pure — no I/O, no clock —
 * so every quirk of the feed is pinned by {@code IpoFeedParserTest} against captured rows.
 *
 * <p>Three rules carry the weight here:
 * <ul>
 *   <li><b>A placeholder is null, never a number.</b> NSE writes {@code "-"} for an absent
 *       field and pads the final issue price with spaces ({@code "   177"}). Gotcha 61 is the
 *       record of what happens when a placeholder is read as a value.</li>
 *   <li><b>An unparsed sentence is unmeasured, not zero.</b> The fresh/OFS split is read from
 *       prose ("Fresh Issue aggregating up to Rs. 2580 million and Offer for sale of up to
 *       11,585,000 Equity shares"). When the sentence mentions a fresh issue and the amount
 *       cannot be read, the split is null — a zero fresh share would read as "promoters
 *       cashing out entirely", the strongest claim the structure read can make.</li>
 *   <li><b>A category is matched by its serial number where NSE gives one, and by name only
 *       where it does not.</b> "Non Institutional Investors" appears three times in one block
 *       (total, above 10 lakh, 2-10 lakh) and only the {@code srNo} tells them apart.</li>
 * </ul>
 */
public final class IpoFeedParser {

    private IpoFeedParser() {
    }

    private static final DateTimeFormatter DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH);

    // ------------------------------------------------------------------ list rows

    /** A row of the upcoming / current feeds. Fields null where NSE gave nothing usable. */
    public record ListRow(String tradingSymbol, String companyName, String series, String status,
                          LocalDate issueStart, LocalDate issueEnd,
                          Double bandLow, Double bandHigh, Long issueSizeShares, Double totalTimes) {
        public boolean isMainboard() {
            return "EQ".equalsIgnoreCase(series);
        }
    }

    public static ListRow parseListRow(Map<String, Object> m) {
        if (m == null) return null;
        String symbol = text(m.get("symbol"));
        if (symbol == null) return null;
        double[] band = parseBand(text(m.get("issuePrice")));
        return new ListRow(symbol,
                text(m.get("companyName")),
                text(m.get("series")),
                text(m.get("status")),
                date(m.get("issueStartDate")),
                date(m.get("issueEndDate")),
                band == null ? null : band[0],
                band == null ? null : band[1],
                longOf(m.get("issueSize")),
                number(m.get("noOfTime")));
    }

    /** A row of the past-issues feed. */
    public record PastRow(String tradingSymbol, String companyName, String securityType,
                          LocalDate issueStart, LocalDate issueEnd, LocalDate listingDate,
                          Double bandLow, Double bandHigh, Double issuePrice) {
        public boolean isMainboard() {
            return "EQ".equalsIgnoreCase(securityType);
        }
    }

    public static PastRow parsePastRow(Map<String, Object> m) {
        if (m == null) return null;
        String symbol = text(m.get("symbol"));
        if (symbol == null) return null;
        double[] band = parseBand(text(m.get("priceRange")));
        String name = text(m.get("companyName"));
        if (name == null) name = text(m.get("company"));
        return new PastRow(symbol, name, text(m.get("securityType")),
                date(m.get("ipoStartDate")), date(m.get("ipoEndDate")), date(m.get("listingDate")),
                band == null ? null : band[0], band == null ? null : band[1],
                number(m.get("issuePrice")));
    }

    // -------------------------------------------------------------------- detail

    /** What the detail page adds: structure, quotas, links and the category-wise book. */
    public record Detail(String issueSizeText, String issueType, Double faceValue, Integer lotSize,
                         Double bandLow, Double bandHigh,
                         String leadManagers, String registrar, String rhpUrl, String ratiosUrl,
                         String anchorUrl, Double employeeDiscountRs,
                         Map<String, Category> categories) {
        public Category category(String key) {
            return categories == null ? null : categories.get(key);
        }
    }

    /** One line of the bid book. {@code times} null while NSE shows an empty cell. */
    public record Category(String label, Long sharesOffered, Long sharesBid, Double times) {
    }

    public static final String QIB = "QIB";
    public static final String NII = "NII";
    public static final String BIG_NII = "BNII";
    public static final String SMALL_NII = "SNII";
    public static final String RETAIL = "RETAIL";
    public static final String EMPLOYEE = "EMPLOYEE";
    public static final String SHAREHOLDER = "SHAREHOLDER";
    public static final String TOTAL = "TOTAL";

    public static Detail parseDetail(Map<String, Object> d) {
        if (d == null || d.isEmpty()) return null;
        Map<String, String> info = new LinkedHashMap<>();
        Object issueInfo = d.get("issueInfo");
        if (issueInfo instanceof Map<?, ?> im && im.get("dataList") instanceof List<?> rows) {
            for (Object o : rows) {
                if (o instanceof Map<?, ?> r) {
                    String title = text(r.get("title"));
                    String value = text(r.get("value"));
                    if (title != null && value != null) info.put(title.trim(), unquote(value));
                }
            }
        }
        double[] band = parseBand(info.get("Price Range"));
        Map<String, Category> cats = new LinkedHashMap<>();
        Object bids = d.get("bidDetails");
        if (bids instanceof List<?> rows) {
            for (Object o : rows) {
                if (!(o instanceof Map<?, ?> r)) continue;
                String key = categoryKey(text(r.get("category")), text(r.get("srNo")));
                if (key == null || cats.containsKey(key)) continue;
                cats.put(key, new Category(text(r.get("category")), longOf(r.get("noOfSharesOffered")),
                        longOf(r.get("noOfsharesBid")), number(r.get("noOfTime"))));
            }
        }
        return new Detail(info.get("Issue Size"), info.get("Issue Type"),
                rupees(info.get("Face Value")), lot(info.get("Bid Lot")),
                band == null ? null : band[0], band == null ? null : band[1],
                info.get("Book Running Lead Managers"), info.get("Name of the Registrar"),
                url(info.get("Red Herring Prospectus")), url(info.get("Ratios / Basis of Issue Price")),
                url(info.get("Anchor Allocation Report")), discount(info.get("Discount")),
                cats);
    }

    /**
     * Serial numbers are NSE's own: 1 QIB, 2 NII, 2.1 NII above 10 lakh, 2.2 NII 2-10 lakh,
     * 3 retail. Employees and shareholders have no fixed serial (4 or 5 depending on the issue),
     * so they match on the name. Sub-rows such as "1(a) FIIs" are ignored.
     */
    static String categoryKey(String category, String srNo) {
        if (category == null) return null;
        String c = category.toLowerCase(Locale.ROOT);
        String s = srNo == null ? "" : srNo.trim();
        if (c.startsWith("total")) return TOTAL;
        if (c.contains("employee")) return EMPLOYEE;
        if (c.contains("shareholder")) return SHAREHOLDER;
        switch (s) {
            case "1": return c.contains("qualified") ? QIB : null;
            case "2": return c.startsWith("non institutional") ? NII : null;
            case "2.1": return c.contains("ten lakh") ? BIG_NII : null;
            case "2.2": return c.contains("two lakh") ? SMALL_NII : null;
            case "3": return c.contains("retail") ? RETAIL : null;
            default: return null;
        }
    }

    // --------------------------------------------------------------- issue size

    /** Rupees crore raised fresh and sold by existing holders. Either half null = could not read. */
    public record IssueSplit(Double freshCr, Double offerForSaleCr) {
        public Double freshSharePct() {
            if (freshCr == null || offerForSaleCr == null) return null;
            double total = freshCr + offerForSaleCr;
            if (total <= 0) return null;
            return 100.0 * freshCr / total;
        }
    }

    private static final String UNIT = "(million|mn|lakhs?|crores?|cr)\\b";
    /**
     * The span between a leg's name and its amount must not cross into the other leg. Without the
     * tempered lookahead, "a fresh issue and an offer for sale of up to 1,000,000 equity shares"
     * read the OFS figure as the fresh amount — found by the parser's own test, not in review.
     */
    private static final String FRESH_SPAN = "(?:(?!offer\\s+for\\s+sale)[^,;(])*?";
    private static final String OFS_SPAN = "(?:(?!fresh\\s+issue)[^,;(])*?";
    /**
     * "Rs." is optional because the unit word is not: MPIMANIPAL's sentence reads "fresh issue
     * aggregating up to 3200 million" with no currency marker at all (first live run), and a
     * number followed by million/lakhs/crores is unambiguous without it.
     */
    private static final String AMOUNT = "(?:rs\\.?\\s*)?([\\d,]+(?:\\.\\d+)?)\\s*" + UNIT;
    private static final Pattern FRESH_RS = Pattern.compile(
            "fresh\\s+issue" + FRESH_SPAN + AMOUNT, Pattern.CASE_INSENSITIVE);
    private static final Pattern OFS_RS = Pattern.compile(
            "offer\\s+for\\s+sale" + OFS_SPAN + AMOUNT, Pattern.CASE_INSENSITIVE);
    private static final Pattern OFS_SHARES = Pattern.compile(
            "offer\\s+for\\s+sale" + OFS_SPAN + "([\\d,]{5,})\\s*equity\\s+shares", Pattern.CASE_INSENSITIVE);
    private static final Pattern FRESH_SHARES = Pattern.compile(
            "fresh\\s+issue" + FRESH_SPAN + "([\\d,]{5,})\\s*equity\\s+shares", Pattern.CASE_INSENSITIVE);

    /**
     * @param upperBand rupees per share, used when a leg is stated in shares rather than rupees;
     *                  when null and a leg needs it, that leg is null
     */
    public static IssueSplit parseIssueSize(String text, Double upperBand) {
        if (text == null || text.isBlank()) return new IssueSplit(null, null);
        String t = text.replace(' ', ' ');
        String lower = t.toLowerCase(Locale.ROOT);
        boolean mentionsFresh = lower.contains("fresh");
        boolean mentionsOfs = lower.contains("offer for sale");

        Double fresh = null;
        Matcher m = FRESH_RS.matcher(t);
        if (m.find()) {
            fresh = toCrore(m.group(1), m.group(2));
        } else {
            m = FRESH_SHARES.matcher(t);
            if (m.find() && upperBand != null) fresh = parseNum(m.group(1)) * upperBand / 1e7;
        }
        if (!mentionsFresh) fresh = 0.0;

        Double ofs = null;
        m = OFS_RS.matcher(t);
        if (m.find()) {
            ofs = toCrore(m.group(1), m.group(2));
        } else {
            m = OFS_SHARES.matcher(t);
            if (m.find() && upperBand != null) ofs = parseNum(m.group(1)) * upperBand / 1e7;
        }
        if (!mentionsOfs) ofs = 0.0;

        // A sentence that names a leg whose amount could not be read is unmeasured as a whole:
        // one half of a ratio is not a ratio.
        if (fresh == null || ofs == null) return new IssueSplit(null, null);
        if (fresh == 0.0 && ofs == 0.0) return new IssueSplit(null, null);
        return new IssueSplit(fresh, ofs);
    }

    private static Double toCrore(String amount, String unit) {
        double v = parseNum(amount);
        String u = unit.toLowerCase(Locale.ROOT);
        if (u.startsWith("million") || u.equals("mn")) return v / 10.0;
        if (u.startsWith("lakh")) return v / 100.0;
        return v; // crore(s) / cr
    }

    // ------------------------------------------------------------------ helpers

    private static final Pattern NUMBER = Pattern.compile("([\\d,]+(?:\\.\\d+)?)");

    /** "Rs.139 to Rs.146" / "Rs. 139 to Rs. 146 per Equity Share" / "402" to [low, high]. */
    static double[] parseBand(String s) {
        if (s == null) return null;
        Matcher m = NUMBER.matcher(s);
        List<Double> nums = new ArrayList<>();
        while (m.find()) nums.add(parseNum(m.group(1)));
        if (nums.isEmpty()) return null;
        if (nums.size() == 1) return new double[] { nums.get(0), nums.get(0) };
        return new double[] { nums.get(0), nums.get(1) };
    }

    /** "102 Equity Shares and in multiples thereof" to 102. */
    static Integer lot(String s) {
        if (s == null) return null;
        Matcher m = NUMBER.matcher(s);
        if (!m.find()) return null;
        double v = parseNum(m.group(1));
        return v > 0 ? (int) v : null;
    }

    /** "Rs. 5 per Equity Share" to 5. */
    static Double rupees(String s) {
        if (s == null) return null;
        Matcher m = NUMBER.matcher(s);
        return m.find() ? parseNum(m.group(1)) : null;
    }

    /** "Rs. 20 per equity share to Eligible Employees" to 20; "NA" to null. */
    static Double discount(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("NA") || t.equals("-")) return null;
        return rupees(t);
    }

    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+");

    /** Strips an anchor tag or quotes; keeps a bare URL. */
    static String url(String s) {
        if (s == null) return null;
        Matcher m = URL.matcher(s);
        return m.find() ? m.group() : null;
    }

    static String unquote(String v) {
        String t = v.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        return t.trim();
    }

    /** Null for null, blank, and NSE's {@code "-"} placeholder (Gotcha 61). */
    static String text(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        if (s.isEmpty() || s.equals("-") || s.equalsIgnoreCase("null")) return null;
        return s;
    }

    static LocalDate date(Object o) {
        String s = text(o);
        if (s == null) return null;
        try {
            return LocalDate.parse(s, DATE);
        } catch (Exception e) {
            return null;
        }
    }

    static Double number(Object o) {
        String s = text(o);
        if (s == null) return null;
        try {
            return parseNum(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Long longOf(Object o) {
        Double d = number(o);
        if (d == null) return null;
        return Math.round(d);
    }

    private static double parseNum(String s) {
        return Double.parseDouble(s.replace(",", "").trim());
    }
}
