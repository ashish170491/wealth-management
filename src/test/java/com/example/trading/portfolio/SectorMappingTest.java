package com.example.trading.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-096: three vocabularies for one sector, matched case-sensitively, made every target read
 * 0% and 42% of the book read "Other". These pin the resolution.
 */
class SectorMappingTest {

    @Test @DisplayName("the screener's title-case labels and the profile's upper-case keys meet")
    void screenerAndProfileAgree() {
        assertThat(SectorMapping.normalize("Metals")).isEqualTo(SectorMapping.normalize("METALS"));
        assertThat(SectorMapping.normalize("Banking")).isEqualTo(SectorMapping.normalize("BANKING"));
        assertThat(SectorMapping.normalize("Pharma")).isEqualTo("PHARMA");
        assertThat(SectorMapping.normalize("IT")).isEqualTo("IT");
    }

    @Test @DisplayName("the valuation fallback's own spellings resolve too")
    void valuationFallbackSpellings() {
        assertThat(SectorMapping.normalize("BANKS")).isEqualTo("BANKING");
        assertThat(SectorMapping.normalize("REFINERIES")).isEqualTo("ENERGY");
        assertThat(SectorMapping.normalize("OIL & GAS")).isEqualTo("ENERGY");
        assertThat(SectorMapping.normalize("Power")).isEqualTo("ENERGY");
    }

    @Test @DisplayName("NSE long names still map exactly, as before")
    void nseLongNames() {
        assertThat(SectorMapping.normalize("Aluminium")).isEqualTo("METALS");
        assertThat(SectorMapping.normalize("Private Sector Bank")).isEqualTo("BANKING");
        assertThat(SectorMapping.normalize("Cement & Cement Products")).isEqualTo("INFRASTRUCTURE");
    }

    @Test @DisplayName("a placeholder is unclassified, never a sector called Other")
    void placeholdersAreUnknown() {
        for (String p : new String[]{"GENERAL", "Other", "OTHER", "-", "", "  ", null, "N/A"}) {
            assertThat(SectorMapping.normalize(p)).as("placeholder %s", p).isEqualTo(SectorMapping.UNKNOWN);
            assertThat(SectorMapping.isUnknown(SectorMapping.normalize(p))).isTrue();
        }
    }

    @Test @DisplayName("an unknown sector is kept and upper-cased, so a matching target can still find it")
    void unknownSectorSurvives() {
        assertThat(SectorMapping.normalize("Ship Repair")).isEqualTo("SHIP_REPAIR");
        assertThat(SectorMapping.normalize("SHIP REPAIR")).isEqualTo("SHIP_REPAIR");
    }

    @Test @DisplayName("resolve falls back to the screener's table only when the industry is a placeholder")
    void resolveFallsBackToScreener() {
        // RELIANCE is "Energy" in the screener's table; a recorded industry outranks it.
        assertThat(SectorMapping.resolve("NSE:RELIANCE", "Refineries & Marketing")).isEqualTo("ENERGY");
        assertThat(SectorMapping.resolve("BSE:RELIANCE", "GENERAL")).isEqualTo("ENERGY");
        assertThat(SectorMapping.resolve("NSE:HDFCBANK", "GENERAL")).isEqualTo("BANKING");
        // A symbol nobody has heard of with no industry is unclassified, not "Other".
        assertThat(SectorMapping.resolve("NSE:ZZZNOTASTOCK", "GENERAL")).isEqualTo(SectorMapping.UNKNOWN);
    }
}
