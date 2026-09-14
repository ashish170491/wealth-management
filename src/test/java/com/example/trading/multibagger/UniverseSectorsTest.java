package com.example.trading.multibagger;

import com.example.trading.portfolio.SectorMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-098: the screener's Industry column read "Other" for roughly two-thirds of the universe,
 * because sector came from an 89-entry hand map with a placeholder default. The classpath file
 * seeded from NSE's index constituent lists is the fix; these pin how it is read.
 */
class UniverseSectorsTest {

    @Test
    @DisplayName("the sector table loads and covers far more than the hand-kept map ever did")
    void tableLoads() {
        assertThat(UniverseSectors.size()).isGreaterThan(700);
    }

    @Test
    @DisplayName("a bank filed under Financial Services reads as Banking, an insurer stays Financials")
    void banksAreRefined() {
        assertThat(UniverseSectors.industryFor("KARURVYSYA")).isEqualTo("Banking");
        assertThat(UniverseSectors.industryFor("HDFCBANK")).isEqualTo("Banking");
        assertThat(UniverseSectors.industryFor("SBILIFE")).isEqualTo("Financial Services");
        assertThat(UniverseSectors.refine(new UniverseSectors.Entry("X", "Financial Services", "X Insurance Ltd.")))
                .isEqualTo("Financial Services");
    }

    @Test
    @DisplayName("the hand-kept map is the override; the exchange list fills the rest")
    void handMapOverridesThenCsvFills() {
        // RELIANCE is "Energy" in the hand map; NSE files it under Oil Gas & Consumable Fuels.
        assertThat(MultibaggerScreenerService.sectorFor("NSE:RELIANCE")).isEqualTo("Energy");
        // KPRMILL is not in the hand map — it used to read "Other".
        assertThat(MultibaggerScreenerService.sectorFor("NSE:KPRMILL")).isEqualTo("Textiles");
        assertThat(MultibaggerScreenerService.sectorFor("BSE:KPRMILL")).isEqualTo("Textiles");
    }

    @Test
    @DisplayName("an unknown symbol is null — never 'Other' — and resolves to unclassified downstream")
    void unknownIsNullNotOther() {
        assertThat(UniverseSectors.industryFor("ZZZNOTASTOCK")).isNull();
        assertThat(UniverseSectors.industryFor(null)).isNull();
        assertThat(UniverseSectors.industryFor("  ")).isNull();
        assertThat(MultibaggerScreenerService.sectorFor("NSE:ZZZNOTASTOCK")).isNull();
        assertThat(SectorMapping.resolve("NSE:ZZZNOTASTOCK", "Other")).isEqualTo(SectorMapping.UNKNOWN);
    }

    @Test
    @DisplayName("NSE's macro-sector vocabulary folds into the buckets the portfolio already uses")
    void nseVocabularyFolds() {
        assertThat(SectorMapping.normalize("Financial Services")).isEqualTo("FINANCIALS");
        assertThat(SectorMapping.normalize("Automobile and Auto Components")).isEqualTo("AUTO");
        assertThat(SectorMapping.normalize("Information Technology")).isEqualTo("IT");
        assertThat(SectorMapping.normalize("Fast Moving Consumer Goods")).isEqualTo("FMCG");
        assertThat(SectorMapping.normalize("Metals & Mining")).isEqualTo("METALS");
        assertThat(SectorMapping.normalize("Oil Gas & Consumable Fuels")).isEqualTo("ENERGY");
        assertThat(SectorMapping.normalize("Construction Materials")).isEqualTo("INFRASTRUCTURE");
        // A legacy row still carrying the placeholder resolves through the file, not to "OTHER".
        assertThat(SectorMapping.resolve("NSE:KPRMILL", "Other")).isEqualTo("TEXTILES");
        assertThat(SectorMapping.resolve("NSE:KARURVYSYA", "GENERAL")).isEqualTo("BANKING");
    }
}
