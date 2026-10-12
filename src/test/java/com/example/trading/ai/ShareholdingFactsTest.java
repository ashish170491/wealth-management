package com.example.trading.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * B-012: FII and DII come from the shareholding XBRL's own institutional subtotals. They were a
 * 50/50 split of "public minus 5%", identical on 297 of 297 stocks. The facts below are verbatim
 * from TCS's June-2026 filing (SHP_1698205), where foreign institutions held 9.06% and domestic
 * 13.47% - two different numbers, which is the whole point.
 */
class ShareholdingFactsTest {

    private static String doc(String body) {
        return "<xbrli:xbrl xmlns:xbrli=\"http://www.xbrl.org/2003/instance\" "
                + "xmlns:in-bse-shp=\"http://www.bseindia.com/xbrl/shp/2025-10-31/in-bse-shp\">" + body + "</xbrli:xbrl>";
    }

    private static String pct(String ctx, String v) {
        return "<in-bse-shp:ShareholdingAsAPercentageOfTotalNumberOfShares contextRef=\"" + ctx
                + "\" decimals=\"INF\" unitRef=\"pure\">" + v + "</in-bse-shp:ShareholdingAsAPercentageOfTotalNumberOfShares>";
    }

    @Test
    @DisplayName("Foreign and domestic institutions are read from the filing's own subtotals, as percent")
    void readsInstitutionalSubtotals() {
        String xml = doc(pct("InstitutionsForeign_ContextI", "0.0906")
                + pct("InstitutionsDomestic_ContextI", "0.1347")
                + pct("MutualFundsOrUTI_ContextI", "0.0568")         // a component, not the subtotal
                + pct("PublicShareholding_ContextI", "0.2823")
                + "<in-bse-shp:WhetherAnySharesHeldByPromotersAreEncumberedUnderPledgedForPromoterAndPromoterGroup "
                + "contextRef=\"MainI\">false</in-bse-shp:WhetherAnySharesHeldByPromotersAreEncumberedUnderPledgedForPromoterAndPromoterGroup>");
        NseDataService.ShareholdingFacts f = NseDataService.parseShareholdingFacts(xml);
        assertThat(f.fiiPercent()).isCloseTo(9.06, within(1e-9));
        assertThat(f.diiPercent()).isCloseTo(13.47, within(1e-9));
        assertThat(f.fiiPercent()).isNotEqualTo(f.diiPercent());
        assertThat(f.pledgePercent()).isEqualTo(0.0);   // explicit "nothing pledged"
    }

    @Test
    @DisplayName("A filing that does not tag a subtotal leaves it null - never zero, never a guess")
    void missingSubtotalIsNull() {
        NseDataService.ShareholdingFacts f = NseDataService.parseShareholdingFacts(
                doc(pct("InstitutionsForeign_ContextI", "0.12")));
        assertThat(f.fiiPercent()).isCloseTo(12.0, within(1e-9));
        assertThat(f.diiPercent()).isNull();
        assertThat(f.pledgePercent()).isNull();
    }

    @Test
    @DisplayName("B-168: the pre-Jun-2025 generation is read too - different labels, values in PERCENT")
    void readsOldGenerationInPercent() {
        // Verbatim from TCS's Sep-2024 filing (SHP_191868_1268485): bare "...I" contexts, and the
        // figures are already percentages. This is the comparison quarter the screening uses, and
        // it read null for every stock, collapsing Institutional Interest to a constant 50.
        String xml = doc(pct("InstitutionsForeignI", "12.66")
                + pct("InstitutionsDomesticI", "10.91")
                + pct("MutualFundsOrUtiI", "4.25")
                + pct("ShareholdingPatternI", "100"));
        NseDataService.ShareholdingFacts f = NseDataService.parseShareholdingFacts(xml);
        assertThat(f.fiiPercent()).isCloseTo(12.66, within(1e-9));   // not 1,266
        assertThat(f.diiPercent()).isCloseTo(10.91, within(1e-9));
    }

    @Test
    @DisplayName("B-168: the scale comes from the filing's own total, so both generations land on one scale")
    void bothGenerationsShareAScale() {
        NseDataService.ShareholdingFacts sep2024 = NseDataService.parseShareholdingFacts(doc(
                pct("InstitutionsForeignI", "12.66") + pct("ShareholdingPatternI", "100")));
        NseDataService.ShareholdingFacts jun2026 = NseDataService.parseShareholdingFacts(doc(
                pct("InstitutionsForeign_ContextI", "0.0906") + pct("ShareholdingPattern_ContextI", "1")));
        // The change the dimension scores: FIIs cut TCS by 3.6 points over two years.
        assertThat(jun2026.fiiPercent() - sep2024.fiiPercent()).isCloseTo(-3.60, within(1e-9));
    }

    @Test
    @DisplayName("Without a total, the generation decides the scale - an old file is never multiplied by 100")
    void noTotalFallsBackToGeneration() {
        assertThat(NseDataService.parseShareholdingFacts(doc(pct("InstitutionsDomesticI", "10.91"))).diiPercent())
                .isCloseTo(10.91, within(1e-9));
        assertThat(NseDataService.parseShareholdingFacts(doc(pct("InstitutionsDomestic_ContextI", "0.1347"))).diiPercent())
                .isCloseTo(13.47, within(1e-9));
    }
}
