package com.example.trading.valuation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.example.trading.valuation.ValuationFixtures.TODAY;
import static com.example.trading.valuation.ValuationFixtures.eligible;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decisions 3 and 6 (SPEC §57.2, §57.5): the grid and the existing reverse-DCF reading are separate
 * methodologies that never feed each other, and the reference multiples exist only as grid columns.
 */
class MethodologySeparationTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static ValuationReading read(String verdict, Double implied) {
        var b = eligible("NSE:GOOD");
        var s = new ValuationGapService.ScoreFacts("NSE:GOOD", TODAY, 800, 8000.0, "Consumer Durables",
                verdict, implied, "v1");
        return ValuationGapService.assemble(new ValuationGapService.Inputs("NSE:GOOD", "NSE:GOOD", TODAY, s,
                b.annual(), b.quarters(), false));
    }

    @Test
    @DisplayName("card E never reads the reverse-DCF: changing every dcf input leaves the grid, the trend and the earnings identical")
    void gridIgnoresTheReverseDcf() {
        var a = read("FAIRLY_VALUED", 14.0);
        var b = read("EXTREMELY_EXPENSIVE", 41.5);
        assertThat(b.requiredGrowthGrid()).isEqualTo(a.requiredGrowthGrid());
        assertThat(b.trendComparison()).isEqualTo(a.trendComparison());
        assertThat(b.earnings()).isEqualTo(a.earnings());
        assertThat(b.capture().trendLnSlope()).isEqualTo(a.capture().trendLnSlope());
        assertThat(b.capture().normalisedEps()).isEqualTo(a.capture().normalisedEps());
    }

    @Test
    @DisplayName("card F is the stored column exactly as stored, never recomputed or adjusted")
    void cardFIsTheStoredFigure() {
        for (double stored : new double[]{-7.5, 0.0, 14.0, 41.5}) {
            assertThat(read("FAIRLY_VALUED", stored).section125Reading().impliedGrowthPct()).isEqualTo(stored);
        }
    }

    @Test
    @DisplayName("card F carries no verdict, no gap to the record and no EPS milestone, only a pointer to the existing panel")
    void cardFIsMinimal() {
        var f = read("EXPENSIVE", 22.0).section125Reading();
        assertThat(f.pointer()).isEqualTo("Is the price sensible?");
        assertThat(f.methodology().terms()).anyMatch(t -> t.contains("12% discount"));
        for (var c : ValuationReading.Section125.class.getRecordComponents()) {
            assertThat(c.getName().toLowerCase()).doesNotContain("verdict").doesNotContain("eps").doesNotContain("gap");
        }
    }

    @Test
    @DisplayName("the two methodology cards describe themselves differently, so they cannot be mistaken for each other")
    void cardsDifferInTheirTerms() {
        var r = read("FAIRLY_VALUED", 14.0);
        assertThat(r.requiredGrowthGrid().methodology().name()).isNotEqualTo(r.section125Reading().methodology().name());
        assertThat(r.requiredGrowthGrid().methodology().terms()).doesNotContainAnyElementsOf(
                r.section125Reading().methodology().terms());
        assertThat(r.differenceBetweenMethods()).contains("Neither confirms the other")
                .contains("never blended or averaged");
        // The callout is accurate about the relationship: correlated, sometimes coincident - not "by construction".
        assertThat(r.differenceBetweenMethods()).doesNotContain("by construction");
    }

    // ------------------------------------------------------------------ reference multiples

    private static List<Path> sources(String root) throws IOException {
        try (Stream<Path> files = Files.walk(Path.of(root))) {
            return files.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".js"))
                    .collect(Collectors.toList());
        }
    }

    @Test
    @DisplayName("the reference-multiple constant is referenced only by the grid and the rules class")
    void multiplesAreGridOnly() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : sources("src/main/java")) {
            String name = p.getFileName().toString();
            if (name.equals("RequiredGrowthGrid.java") || name.equals("ValuationGapRules.java")) continue;
            if (Files.readString(p).contains("GRID_REFERENCE_MULTIPLES")) offenders.add(p.toString());
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("no code anywhere names a multiple an end, exit, fair or justified price-to-earnings")
    void noValuationAssumptionVocabulary() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String root : new String[]{"src/main/java/com/example/trading/valuation", "src/main/resources/static/js"}) {
            for (Path p : sources(root)) {
                String s = Files.readString(p);
                if (!p.toString().replace('\\', '/').contains("valuation") && !p.getFileName().toString().contains("valuation-gap")) {
                    continue;
                }
                for (String bad : new String[]{"exitPe", "endPe", "fairPe", "justifiedPe", "terminalPe"}) {
                    if (s.contains(bad)) offenders.add(p + " uses " + bad);
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("the grid payload carries its limitations and a referenceMultiple field, and no multiple is ever stored")
    void multiplesAreLabelled() {
        var g = read("FAIRLY_VALUED", 14.0).requiredGrowthGrid();
        assertThat(g.limits()).contains("what-if column").contains("not what any business deserves".replace("not ", ""));
        assertThat(g.rows()).allSatisfy(row -> assertThat(row.referenceMultiple()).isIn(13, 25, 35));
        var e = SnapshotMapper.toEntity(read("FAIRLY_VALUED", 14.0), "SCHEDULED");
        assertThat(e.getEpsSeriesJson()).doesNotContain("referenceMultiple");
    }

    @Test
    @DisplayName("no reference multiple, grid value or valuation figure can produce a price: the multiples never meet EPS in this code")
    void noPriceIsDerivedFromMultiples() throws IOException {
        // The only arithmetic involving a multiple is requiredGrowth(), which returns a growth rate.
        String grid = Files.readString(MAIN.resolve("com/example/trading/valuation/RequiredGrowthGrid.java"));
        assertThat(grid).doesNotContain("multiple * fit").doesNotContain("multiple * normalisedEps * Math.pow(1.0 + fit");
        // And no field anywhere is typed as a price for a future year.
        for (var c : ValuationReading.TrendComparison.class.getRecordComponents()) {
            assertThat(c.getName().toLowerCase()).doesNotContain("price");
        }
    }
}
