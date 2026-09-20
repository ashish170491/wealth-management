package com.example.trading.funds.amfi;

import com.example.trading.funds.SchemePlanKey;
import com.example.trading.funds.SchemeStructure;
import com.example.trading.funds.SebiCategory;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One scheme row from AMFI's NAVAll feed, with its category and plan already resolved.
 *
 * <p><b>{@code schemeCode} is the key, not {@code isinGrowth}.</b> Measured on the live file:
 * 14,375 rows carry 14,375 distinct scheme codes (zero collisions) but <b>five ISINs appear
 * twice</b> - {@code INF204KA1US6}, {@code INF204KA1UU2}, {@code INF204KB1304},
 * {@code INF204KB1XK6}, {@code INF204KB1XN0}, all dead Nippon/Reliance fixed-maturity plans. Since
 * Kite returns the ISIN as its {@code tradingsymbol} on {@code /mf/holdings}, an ISIN lookup is a
 * first-hit-wins hazard (Gotcha 107: <em>first hit that answers wins</em>), so it is a non-unique
 * indexed lookup that records which row answered - never a primary key.
 *
 * <p>{@code nav} is nullable and is <b>null, never zero</b>, when AMFI writes {@code N.A.} or
 * {@code -}: a scheme that did not publish a NAV is not a scheme worth nothing (Gotcha 21, 22).
 *
 * <p>{@code navDate} may be <b>later</b> than the file's own headline date and that is not
 * corruption - 706 rows in the live file are dated ahead, all liquid and overnight funds, which
 * strike a next-day NAV by convention. The parser must not reject them.
 */
public record AmfiSchemeRow(
        long schemeCode,
        String isinGrowth,
        String isinReinvest,
        String schemeName,
        String amcName,
        SebiCategory category,
        SchemeStructure structure,
        String categoryRaw,
        SchemePlanKey.Plan plan,
        SchemePlanKey.Option option,
        SchemePlanKey.ResolvedFrom planFrom,
        SchemePlanKey.ResolvedFrom optionFrom,
        BigDecimal nav,
        LocalDate navDate
) {

    /** True when this row belongs to the set this module screens: an open-ended, actively managed
     *  equity scheme held as a Direct-plan Growth series. */
    public boolean isScreenable() {
        return structure == SchemeStructure.OPEN_ENDED
                && category.isActivelyManagedEquity()
                && plan == SchemePlanKey.Plan.DIRECT
                && option == SchemePlanKey.Option.GROWTH;
    }

    /**
     * True when this row's month-end series is worth <b>keeping</b>, which is a wider set than the
     * one this module <b>judges</b>.
     *
     * <p>{@link #isScreenable()} answers "does this fund get a record measured against its category
     * median", and index funds are correctly outside it: a tracker is judged on how closely it
     * follows its stated index, not on excess return over a peer group. But that made the app blind
     * to them entirely - no scheme row, no NAV history - and an index fund is the yardstick every
     * actively managed fund is implicitly being bought instead of. The investor holds three of them
     * out of six holdings and the app could not see the category.
     *
     * <p>So index funds are persisted and given history while {@code screenable} stays false, which
     * is what keeps every existing number identical: peer sets are filtered on {@code screenable}
     * AND category, and {@link SebiCategory#INDEX_EQUITY} is its own category, so a tracker could
     * not reach an active category's median even if the flag changed. Same shape as
     * {@code computeActualWeights()} being left alone when the fund module landed (§52.5).
     *
     * <p><b>Exchange-traded funds are deliberately excluded.</b> An ETF's net asset value is not
     * what you would pay for it - it trades on the exchange at a price that can sit away from NAV -
     * so a NAV series would answer a question nobody asked, and its plan and option columns are
     * usually blank anyway (the units guard would refuse it downstream regardless).
     */
    public boolean isTrackable() {
        return structure == SchemeStructure.OPEN_ENDED
                && category.assetClass() == com.example.trading.funds.AssetClass.EQUITY
                && category != SebiCategory.EQUITY_ETF
                && plan == SchemePlanKey.Plan.DIRECT
                && option == SchemePlanKey.Option.GROWTH;
    }

    /** True when a NAV was actually published for this row. */
    public boolean hasNav() {
        return nav != null && navDate != null;
    }
}
