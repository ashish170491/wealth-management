package com.example.trading.macro;

import com.example.trading.macro.MacroExposureMap.Entry;
import com.example.trading.macro.MacroExposureMap.OnRise;
import com.example.trading.macro.MacroExposureMap.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the exposure map file and its loader (SPEC §48.3).
 *
 * <p>The properties that matter: the shipped file loads and every key in it resolves to something
 * this app can actually produce; a symbol rule outranks an industry rule which outranks a sector
 * rule; the version hash is stable across loads and moves when a rule changes; and a bad row stops
 * the application naming the line, rather than matching nothing for ever in silence.
 */
class MacroExposureMapTest {

    private static final String HEADER = "factor,scope,key,onRise,strength,channel,rationale\n";

    // ------------------------------------------------------------------ the shipped file

    @Test
    @DisplayName("The shipped map loads, and covers a real spread of factors")
    void shippedMapLoads() {
        assertThat(MacroExposureMap.size()).isGreaterThan(100);
        assertThat(MacroExposureMap.version()).startsWith("mx1-");

        long factorsCovered = MacroExposureMap.all().stream().map(Entry::factor).distinct().count();
        assertThat(factorsCovered).isGreaterThanOrEqualTo(15);
    }

    @Test
    @DisplayName("Every factor the app can extract has at least one rule, or it could never report one")
    void everyFactorIsMapped() {
        for (MacroFactor factor : MacroFactor.values()) {
            assertThat(MacroExposureMap.forFactor(factor))
                    .as("factor %s has no rule, so an event of that kind could never reach any stock", factor)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("A real holding resolves through the shipped file")
    void realStockResolves() {
        List<Entry> paint = MacroExposureMap.forStock("NSE:ASIANPAINT", "CHEMICALS", null);
        assertThat(paint).isNotEmpty();
        assertThat(paint).anyMatch(e -> e.factor() == MacroFactor.CRUDE_OIL && e.onRise() == OnRise.HURT);

        assertThat(MacroExposureMap.hasMapping("NSE:TCS", "IT", "Information Technology")).isTrue();
    }

    @Test
    @DisplayName("The exchange prefix is not the identity: BSE and NSE spellings resolve alike")
    void exchangePrefixIgnored() {
        assertThat(MacroExposureMap.forStock("BSE:ASIANPAINT", "CHEMICALS", null))
                .isEqualTo(MacroExposureMap.forStock("NSE:ASIANPAINT", "CHEMICALS", null));
    }

    // ------------------------------------------------------------------ precedence

    @Test
    @DisplayName("A symbol rule outranks an industry rule, which outranks a sector rule")
    void precedenceIsSymbolThenIndustryThenSector() {
        String csv = HEADER
                + "CRUDE_OIL,SECTOR,ENERGY,HELPED,LOW,realisation,Sector level.\n"
                + "CRUDE_OIL,INDUSTRY,Oil Gas & Consumable Fuels,HURT,MEDIUM,input cost,Industry level.\n"
                + "CRUDE_OIL,SYMBOL,HINDPETRO,HURT,HIGH,marketing margin,Symbol level.\n";
        List<Entry> rules = MacroExposureMap.parse(csv).entries();

        assertThat(pick(rules, "NSE:HINDPETRO", "ENERGY", "Oil Gas & Consumable Fuels").scope())
                .isEqualTo(Scope.SYMBOL);
        assertThat(pick(rules, "NSE:GAIL", "ENERGY", "Oil Gas & Consumable Fuels").scope())
                .isEqualTo(Scope.INDUSTRY);
        assertThat(pick(rules, "NSE:NTPC", "ENERGY", "Power").scope())
                .isEqualTo(Scope.SECTOR);
    }

    @Test
    @DisplayName("At most one rule per factor reaches a stock, so nothing is counted twice")
    void oneRulePerFactor() {
        String csv = HEADER
                + "CRUDE_OIL,SECTOR,ENERGY,HELPED,LOW,realisation,Sector level.\n"
                + "CRUDE_OIL,SYMBOL,ONGC,HELPED,HIGH,realisation,Symbol level.\n";
        List<Entry> rules = MacroExposureMap.parse(csv).entries();

        List<Entry> forOngc = resolve(rules, "NSE:ONGC", "ENERGY", null);
        assertThat(forOngc).hasSize(1);
        assertThat(forOngc.get(0).scope()).isEqualTo(Scope.SYMBOL);
    }

    // ------------------------------------------------------------------ the version

    @Test
    @DisplayName("The version is stable across identical loads and moves when a rule changes")
    void versionIsStableAndSensitive() {
        String base = HEADER + "CRUDE_OIL,SECTOR,ENERGY,HELPED,LOW,realisation,Sells the barrel.\n";
        String reworded = HEADER + "CRUDE_OIL,SECTOR,ENERGY,HELPED,LOW,realisation,Sells the barrel it produces.\n";
        String reweighted = HEADER + "CRUDE_OIL,SECTOR,ENERGY,HELPED,HIGH,realisation,Sells the barrel.\n";

        assertThat(MacroExposureMap.parse(base).version()).isEqualTo(MacroExposureMap.parse(base).version());
        assertThat(MacroExposureMap.parse(reworded).version()).isNotEqualTo(MacroExposureMap.parse(base).version());
        assertThat(MacroExposureMap.parse(reweighted).version()).isNotEqualTo(MacroExposureMap.parse(base).version());
    }

    // ------------------------------------------------------------------ refusals

    @Test
    @DisplayName("An unknown factor stops the load and names the line")
    void unknownFactorRefused() {
        String csv = HEADER + "SOYBEAN_PRICES,SECTOR,FMCG,HURT,LOW,input cost,Not a factor.\n";
        assertThatThrownBy(() -> MacroExposureMap.parse(csv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining("unknown factor");
    }

    @Test
    @DisplayName("A sector this app cannot produce is refused, because it would match nothing for ever")
    void unproducibleSectorRefused() {
        String csv = HEADER + "CRUDE_OIL,SECTOR,AIRLINES,HURT,HIGH,fuel bill,There is no AIRLINES bucket.\n";
        assertThatThrownBy(() -> MacroExposureMap.parse(csv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not a sector this app can produce");
    }

    @Test
    @DisplayName("An industry spelling nothing emits is refused rather than silently ignored")
    void unknownIndustryRefused() {
        String csv = HEADER + "CRUDE_OIL,INDUSTRY,Low Cost Carriers,HURT,HIGH,fuel bill,Not an NSE industry.\n";
        assertThatThrownBy(() -> MacroExposureMap.parse(csv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not an industry spelling");
    }

    @Test
    @DisplayName("Two rules for one factor at one scope are refused - the file would disagree with itself")
    void duplicateRuleRefused() {
        String csv = HEADER
                + "CRUDE_OIL,SECTOR,ENERGY,HELPED,LOW,realisation,First.\n"
                + "CRUDE_OIL,SECTOR,ENERGY,HURT,HIGH,input cost,Second.\n";
        assertThatThrownBy(() -> MacroExposureMap.parse(csv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate rule");
    }

    @Test
    @DisplayName("A rule with no rationale is refused: an unexplained rule is an assertion")
    void blankRationaleRefused() {
        String csv = HEADER + "CRUDE_OIL,SECTOR,ENERGY,HELPED,LOW,realisation,\n";
        assertThatThrownBy(() -> MacroExposureMap.parse(csv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rationale is blank");
    }

    @Test
    @DisplayName("A rationale may contain commas - it is the last field and is not split")
    void rationaleKeepsItsCommas() {
        String csv = HEADER
                + "CRUDE_OIL,SECTOR,ENERGY,HELPED,LOW,realisation,It rises, it falls, the barrel is the price.\n";
        List<Entry> rules = MacroExposureMap.parse(csv).entries();
        assertThat(rules.get(0).rationale()).isEqualTo("It rises, it falls, the barrel is the price.");
    }

    @Test
    @DisplayName("Comment and blank lines are skipped without shifting the reported line numbers")
    void commentsSkipped() {
        String csv = "# a comment\n\n" + HEADER + "CRUDE_OIL,SECTOR,ENERGY,NUDGED,LOW,realisation,Bad direction.\n";
        assertThatThrownBy(() -> MacroExposureMap.parse(csv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line 4");
    }

    // ------------------------------------------------------------------ helpers

    private static Entry pick(List<Entry> rules, String symbol, String sector, String industry) {
        List<Entry> hits = resolve(rules, symbol, sector, industry);
        assertThat(hits).as("no rule resolved for %s", symbol).isNotEmpty();
        return hits.get(0);
    }

    /**
     * Resolution against an arbitrary rule list. Mirrors {@code forStock} for a parsed-in-memory
     * file, which is what lets precedence be pinned without editing the shipped map.
     */
    private static List<Entry> resolve(List<Entry> rules, String symbol, String sector, String industry) {
        return rules.stream()
                .filter(e -> matches(e, symbol, sector, industry))
                .collect(java.util.stream.Collectors.toMap(Entry::factor, e -> e,
                        (a, b) -> a.scope().ordinal() <= b.scope().ordinal() ? a : b,
                        java.util.LinkedHashMap::new))
                .values().stream().toList();
    }

    private static boolean matches(Entry e, String symbol, String sector, String industry) {
        String bare = symbol == null ? "" : symbol.substring(symbol.indexOf(':') + 1).toUpperCase(java.util.Locale.ROOT);
        return switch (e.scope()) {
            case SYMBOL -> e.key().equalsIgnoreCase(bare);
            case INDUSTRY -> industry != null && e.key().equalsIgnoreCase(industry);
            case SECTOR -> sector != null && e.key().equalsIgnoreCase(sector);
        };
    }
}
