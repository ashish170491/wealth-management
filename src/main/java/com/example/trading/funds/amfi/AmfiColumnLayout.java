package com.example.trading.funds.amfi;

import java.util.Locale;

/**
 * Where each field sits in an AMFI row, read from the file's own column header (B-130).
 *
 * <p><b>Why this exists.</b> AMFI serves the same data in two column orders, with the same number
 * of fields, and nothing in a row distinguishes them:
 *
 * <pre>
 * daily NAVAll.txt   Scheme Code;ISIN Growth;ISIN Reinvest;Scheme Name;Plan;Option;NAV;Date
 * history endpoint   Scheme Code;NAV Name;Plan;Option;ISIN Growth;ISIN Reinvest;NAV;Date
 * </pre>
 *
 * <p>Both are eight semicolon-separated fields; the first and the last two agree. A parser written
 * against one and pointed at the other therefore <b>keeps producing rows</b>, with a correct scheme
 * code, a correct NAV and a correct date - and with the scheme name, both ISINs, the plan and the
 * option all silently taken from the wrong columns. Measured when the month-end backfill first ran:
 * six months fetched, zero errors, ~1,900 equity schemes counted per month, and <b>four NAV points
 * written</b> where hundreds were expected. Nothing threw. The tell was the number being too small,
 * which is the B-113 rule exactly - run it over real rows and look at the figure before believing
 * the status code.
 *
 * <p>So positions are never assumed. They are read from the {@code Scheme Code;...} header line
 * that both files carry, by column <em>name</em>, which also survives AMFI reordering again or
 * adding a column in the middle.
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public record AmfiColumnLayout(
        int code,
        int isinGrowth,
        int isinReinvest,
        int name,
        int plan,
        int option,
        int nav,
        int date,
        String source
) {

    /** True when every field this parser needs was located in the header. */
    public boolean isUsable() {
        return code >= 0 && nav >= 0 && date >= 0 && name >= 0;
    }

    /**
     * The daily file's order, used only when a data row arrives before any column header.
     *
     * <p>It is an assumption, and it says so in {@link #source} so a caller can report it rather
     * than present it as a fact - the same reason {@code available_from_estimated} sits beside an
     * estimated filing date (Gotcha 100).
     */
    public static AmfiColumnLayout assumedDaily() {
        return new AmfiColumnLayout(0, 1, 2, 3, 4, 5, 6, 7, "assumed (no column header seen)");
    }

    /**
     * Read the layout from a header line.
     *
     * <p>Matching is on content rather than on an exact string, because the two feeds spell the
     * same column differently - {@code "ISIN Div Payout/ ISIN Growth"} with a space in the daily
     * file and {@code "ISIN Div Payout/ISIN Growth"} without one in the history file, and
     * {@code "Scheme Name"} against {@code "NAV Name"}.
     */
    public static AmfiColumnLayout fromHeader(String headerLine) {
        String[] cols = headerLine.split(";", -1);
        int code = -1;
        int isinGrowth = -1;
        int isinReinvest = -1;
        int name = -1;
        int plan = -1;
        int option = -1;
        int nav = -1;
        int date = -1;

        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (c.startsWith("SCHEME CODE")) {
                code = i;
            } else if (c.contains("ISIN") && c.contains("REINVEST")) {
                isinReinvest = i;
            } else if (c.contains("ISIN") && c.contains("GROWTH")) {
                // Must be tested after REINVEST: "ISIN Div Payout/ISIN Growth" is the payout column
                // in name and the growth ISIN in fact, and only the reinvestment column is
                // unambiguous. Ordering the tests the other way puts both ISINs in one field.
                isinGrowth = i;
            } else if (c.equals("SCHEME NAME") || c.equals("NAV NAME")) {
                name = i;
            } else if (c.equals("PLAN")) {
                plan = i;
            } else if (c.equals("OPTION")) {
                option = i;
            } else if (c.contains("NET ASSET VALUE")) {
                nav = i;
            } else if (c.equals("DATE")) {
                date = i;
            }
        }
        return new AmfiColumnLayout(code, isinGrowth, isinReinvest, name, plan, option, nav, date,
                headerLine.trim());
    }
}
