package com.example.trading.funds.amfi;

import com.example.trading.funds.SchemePlanKey;
import com.example.trading.funds.SebiCategory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses AMFI's NAVAll feed (and the identically-shaped historical report) into
 * {@link AmfiSchemeRow} records.
 *
 * <p>The file is not a table - it is a table interleaved with two kinds of heading:
 *
 * <pre>
 * Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date
 *
 * Open Ended Schemes(Equity Scheme - Flexi Cap Fund)     &lt;- category header
 *
 * Axis Mutual Fund                                       &lt;- AMC header
 *
 * 120505;INF846K01EH3;-;Axis Midcap Fund;Direct Plan;Growth Option;143.87;18-Sep-2026
 * </pre>
 *
 * <p>Both headings must be carried down onto the rows beneath them, which is the whole reason the
 * category cannot simply be read off a column - and therefore the reason {@link SebiCategory}
 * exists at all.
 *
 * <p><b>The parse result reports what it could not read.</b> {@link ParseResult#unparsedLines()}
 * and {@link ParseResult#unclassifiedHeaders()} are returned rather than logged, so a layout change
 * at AMFI is loud instead of quiet. The failure this guards against is B-054's: a feed change that
 * yields an empty list, which every consumer downstream reads as <em>"there are no schemes"</em>.
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O. It takes text.
 */
public final class AmfiNavParser {

    private AmfiNavParser() {}

    private static final DateTimeFormatter AMFI_DATE =
            DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ENGLISH);

    private static final String COLUMN_HEADER_PREFIX = "Scheme Code";

    /**
     * @param rows                 every scheme row that parsed
     * @param unparsedLines        lines that looked like data and could not be read - a layout change
     * @param unclassifiedHeaders  category headers with no rule in {@link SebiCategory}, by name
     * @param categoryHeadersSeen  how many category headers were walked
     */
    public record ParseResult(
            List<AmfiSchemeRow> rows,
            int unparsedLines,
            Set<String> unclassifiedHeaders,
            int categoryHeadersSeen,
            Set<String> ambiguousHeadings
    ) {
        public int rowCount() {
            return rows.size();
        }

        /** Rows in the screenable set: open-ended, actively managed equity, Direct plan, Growth. */
        public List<AmfiSchemeRow> screenable() {
            return rows.stream().filter(AmfiSchemeRow::isScreenable).toList();
        }
    }

    public static ParseResult parse(String content) {
        List<AmfiSchemeRow> rows = new ArrayList<>();
        Set<String> unclassified = new LinkedHashSet<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        int unparsed = 0;
        int categoryHeaders = 0;

        SebiCategory.Resolved currentCategory = SebiCategory.resolve(null);
        String currentAmc = null;

        for (String raw : content.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }

            // The column header repeats mid-file in some AMFI exports. It is not a scheme row.
            if (line.startsWith(COLUMN_HEADER_PREFIX)) {
                continue;
            }

            if (line.indexOf(';') < 0) {
                // A heading. Either a category block or an AMC name.
                if (looksLikeCategoryHeader(line)) {
                    currentCategory = SebiCategory.resolve(line);
                    categoryHeaders++;
                    if (currentCategory.isUnclassified()) {
                        unclassified.add(line);
                    }
                    // A new category block resets the AMC: the AMC name is always reprinted beneath it.
                    currentAmc = null;
                } else {
                    if (looksAmbiguous(line)) {
                        ambiguous.add(line);
                    }
                    currentAmc = line;
                }
                continue;
            }

            AmfiSchemeRow row = parseRow(line, currentCategory, currentAmc);
            if (row == null) {
                unparsed++;
            } else {
                rows.add(row);
            }
        }

        return new ParseResult(List.copyOf(rows), unparsed, Set.copyOf(unclassified),
                categoryHeaders, Set.copyOf(ambiguous));
    }

    /** The structure words every AMFI category header begins with. */
    private static final List<String> STRUCTURE_PREFIXES =
            List.of("OPEN ENDED", "CLOSE ENDED", "CLOSED ENDED", "INTERVAL");

    /**
     * A category header is a semicolon-free line beginning with a structure word
     * ({@code Open Ended Schemes(...)}). Everything else without a semicolon is an AMC name.
     *
     * <p><b>This must not simply test for a parenthesis</b>, which is what the first version did and
     * what the first live run caught: {@code "IL&FS Mutual Fund (IDF)"} is an <em>AMC name that
     * contains brackets</em>, and reading it as a category header made every scheme beneath it
     * inherit {@link SebiCategory#UNCLASSIFIED} - 2,535 rows, 18% of the file, silently filed under
     * a category that does not exist. Nothing threw and the row count was still right.
     *
     * <p>The lesson is the one B-113 records: <b>run a new rule over real rows and look at the
     * spread before shipping it.</b> An invented test case would have used a tidy AMC name.
     */
    private static boolean looksLikeCategoryHeader(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        return STRUCTURE_PREFIXES.stream().anyMatch(upper::startsWith);
    }

    /**
     * A semicolon-free line that carries a category separator but does not start with a known
     * structure word. It is treated as an AMC name, and reported - so that if AMFI ever introduces
     * a fourth structure the mis-read is visible instead of silently re-categorising the rows
     * beneath it, which is exactly how the IL&amp;FS defect above stayed invisible.
     */
    private static boolean looksAmbiguous(String line) {
        return !looksLikeCategoryHeader(line) && line.contains(" - ") && line.indexOf('(') > 0;
    }

    private static AmfiSchemeRow parseRow(String line, SebiCategory.Resolved category, String amc) {
        String[] f = line.split(";", -1);
        if (f.length < 8) {
            return null;
        }
        long code;
        try {
            code = Long.parseLong(f[0].trim());
        } catch (NumberFormatException e) {
            return null;
        }

        String isinGrowth = blankToNull(f[1]);
        String isinReinvest = blankToNull(f[2]);
        String name = f[3].trim();
        String planCol = f[4];
        String optionCol = f[5];

        SchemePlanKey.Resolved pk = SchemePlanKey.resolve(planCol, optionCol, name);

        return new AmfiSchemeRow(
                code,
                isinGrowth,
                isinReinvest,
                name,
                amc,
                category.category(),
                category.structure(),
                category.rawHeader(),
                pk.plan(),
                pk.option(),
                pk.planFrom(),
                pk.optionFrom(),
                parseNav(f[6]),
                parseDate(f[7])
        );
    }

    /**
     * AMFI writes {@code N.A.} or {@code -} for a scheme that did not publish. Both are
     * <b>null, never zero</b> - a fund without a NAV is not a fund worth nothing, and a zero here
     * would flow into every return calculation downstream as a total loss (Gotcha 21, 22).
     */
    static BigDecimal parseNav(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty() || s.equals("-") || s.equalsIgnoreCase("N.A.") || s.equalsIgnoreCase("NA")) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses {@code 18-Sep-2026}. Returns null rather than today on anything unreadable - filing a
     * row of unknown date under today's date is how a decade-old document becomes current
     * (Gotcha 100, B-119).
     *
     * <p>Deliberately does <b>not</b> reject a future date: 706 rows in the live file are dated
     * ahead of the file's headline date, all liquid and overnight funds striking a next-day NAV.
     * Treating that as corruption would silently drop a whole category.
     */
    static LocalDate parseDate(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty() || s.equals("-")) {
            return null;
        }
        try {
            return LocalDate.parse(s, AMFI_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String text) {
        String s = text == null ? "" : text.trim();
        return (s.isEmpty() || s.equals("-")) ? null : s;
    }
}
