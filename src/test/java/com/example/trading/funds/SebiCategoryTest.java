package com.example.trading.funds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the AMFI category resolver (SPEC §52).
 *
 * <p>The property that matters most is the <b>spelling merge</b>. AMFI prints every equity category
 * under two spellings and Sectoral/Thematic under three. If they do not merge, a fund's peer group
 * is silently halved and <em>every percentile computed inside it is wrong while looking perfectly
 * fine</em> — B-096's failure in a new feed, and the kind that survives review because nothing
 * throws.
 *
 * <p>The strictness lives here rather than in the resolver on purpose: production must not fail a
 * scheduled job because AMFI invented a fourth spelling overnight, but the build must.
 * {@code amfi-category-headers-2026-09.txt} is a verbatim capture of all 103 headers in the live
 * file, so a new one shows up as a failing assertion with its name in the message.
 */
class SebiCategoryTest {

    private static final String CAPTURED_HEADERS = "/funds/amfi-category-headers-2026-09.txt";

    private static List<String> capturedHeaders() {
        try (InputStream in = SebiCategoryTest.class.getResourceAsStream(CAPTURED_HEADERS)) {
            assertThat(in).as("captured header fixture must be on the test classpath").isNotNull();
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + CAPTURED_HEADERS, e);
        }
    }

    @Test
    @DisplayName("the two spellings of one category resolve to one category — B-096 in a new feed")
    void spellingsMerge() {
        // 130 rows and 60 rows in the live file. Matched literally, a flexi-cap fund's peer group is
        // either 130 schemes or 60 depending on which spelling its AMC used.
        assertThat(SebiCategory.resolve("Open Ended Schemes(Equity Scheme - Flexi Cap Fund)").category())
                .isEqualTo(SebiCategory.FLEXI_CAP);
        assertThat(SebiCategory.resolve("Open Ended Schemes(Equity Schemes - Flexi Cap Fund)").category())
                .isEqualTo(SebiCategory.FLEXI_CAP);

        // 98 + 45. Substituting a midcap benchmark for the missing half is B-047 in index clothing.
        assertThat(SebiCategory.resolve("Open Ended Schemes(Equity Scheme - Small Cap Fund)").category())
                .isEqualTo(SebiCategory.SMALL_CAP);
        assertThat(SebiCategory.resolve("Open Ended Schemes(Equity Schemes - Small Cap Fund)").category())
                .isEqualTo(SebiCategory.SMALL_CAP);
    }

    @Test
    @DisplayName("ELSS is spelled three ways and Sectoral/Thematic three ways; all collapse")
    void awkwardSpellingsCollapse() {
        assertThat(SebiCategory.resolve("Open Ended Schemes(Equity Scheme - ELSS)").category())
                .isEqualTo(SebiCategory.ELSS);
        assertThat(SebiCategory.resolve("Open Ended Schemes(Equity Schemes - ELSS- Tax Saver Fund)").category())
                .isEqualTo(SebiCategory.ELSS);
        // Close-ended blocks print the bare category with no "Equity Scheme" prefix.
        assertThat(SebiCategory.resolve("Close Ended Schemes(ELSS)").category())
                .isEqualTo(SebiCategory.ELSS);

        assertThat(SebiCategory.resolve("Open Ended Schemes(Equity Scheme - Sectoral/ Thematic)").category())
                .isEqualTo(SebiCategory.SECTORAL_THEMATIC);
        assertThat(SebiCategory.resolve("Open Ended Schemes(Equity Schemes - Sectoral Fund)").category())
                .isEqualTo(SebiCategory.SECTORAL_THEMATIC);
        assertThat(SebiCategory.resolve("Open Ended Schemes(Equity Schemes - Thematic Fund)").category())
                .isEqualTo(SebiCategory.SECTORAL_THEMATIC);
    }

    @Test
    @DisplayName("every captured live header resolves — a new AMFI spelling fails the build")
    void everyCapturedHeaderResolves() {
        List<String> unresolved = capturedHeaders().stream()
                .filter(h -> SebiCategory.resolve(h).isUnclassified())
                .toList();

        // If this fails, AMFI has introduced a category this app has no rule for. That is not a
        // reason to relax the assertion: add the rule, or the schemes beneath that header are
        // silently uncategorised and drop out of every peer group.
        assertThat(unresolved)
                .as("headers with no rule in SebiCategory — add a rule, do not delete the case")
                .isEmpty();
    }

    @Test
    @DisplayName("every captured EQUITY header resolves to an equity asset class")
    void equityHeadersResolveToEquity() {
        // Guards the subtler half: a header can resolve and still land in the wrong asset class,
        // which would quietly drop a whole category out of the screenable universe.
        List<String> misfiled = capturedHeaders().stream()
                .filter(h -> h.contains("Equity Scheme"))
                .filter(h -> SebiCategory.resolve(h).category().assetClass() != AssetClass.EQUITY)
                .toList();

        assertThat(misfiled).isEmpty();
    }

    @Test
    @DisplayName("the captured headers collapse to far fewer categories than spellings")
    void mergeActuallyReducesTheCount() {
        Map<SebiCategory, Set<String>> byCategory = capturedHeaders().stream()
                .map(h -> Map.entry(SebiCategory.resolve(h).category(), h))
                .collect(Collectors.groupingBy(Map.Entry::getKey, TreeMap::new,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toSet())));

        // The whole point: at least one real category is reached by more than one spelling. If this
        // ever reads 0, the merge has stopped working and every peer group has quietly split.
        long merged = byCategory.values().stream().filter(s -> s.size() > 1).count();
        assertThat(merged)
                .as("categories reached by more than one spelling")
                .isGreaterThanOrEqualTo(10);

        assertThat(byCategory.get(SebiCategory.SECTORAL_THEMATIC)).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("an unknown header is UNCLASSIFIED carrying its raw text, never the nearest category")
    void unknownHeaderIsReportedNotGuessed() {
        SebiCategory.Resolved r =
                SebiCategory.resolve("Open Ended Schemes(Equity Scheme - Quantum Cap Fund)");

        // Folding this into FLEXI_CAP because it looks closest would put schemes in a peer group
        // they do not belong to, and nothing downstream could tell.
        assertThat(r.category()).isEqualTo(SebiCategory.UNCLASSIFIED);
        assertThat(r.isUnclassified()).isTrue();
        assertThat(r.rawHeader()).contains("Quantum Cap Fund");
    }

    @Test
    @DisplayName("structure is read from the prefix, and close-ended never reaches the screenable set")
    void structureIsRead() {
        assertThat(SebiCategory.resolve("Open Ended Schemes(Equity Scheme - Mid Cap Fund)").structure())
                .isEqualTo(SchemeStructure.OPEN_ENDED);
        assertThat(SebiCategory.resolve("Close Ended Schemes(Income)").structure())
                .isEqualTo(SchemeStructure.CLOSE_ENDED);
        assertThat(SebiCategory.resolve("Interval Fund Schemes(Debt)").structure())
                .isEqualTo(SchemeStructure.INTERVAL);
    }

    @Test
    @DisplayName("the historical file's spaced bracket form parses the same")
    void spacedHistoricalFormParses() {
        // The DownloadNAVHistoryReport export writes "Schemes ( Equity Scheme - Mid Cap Fund )".
        // If this diverged, the backfill would categorise nothing while the daily pull looked fine.
        assertThat(SebiCategory.resolve("Open Ended Schemes ( Equity Scheme - Mid Cap Fund )").category())
                .isEqualTo(SebiCategory.MID_CAP);
    }

    @Test
    @DisplayName("the pre-2018 bare Growth bucket is named, not left unclassified — and is never screenable")
    void legacyTaxonomyIsNamed() {
        // "Unclassified" means this app has no rule. Here the rule is that the category is obsolete,
        // which is a different fact and must not be reported as a gap in the app.
        SebiCategory.Resolved r = SebiCategory.resolve("Close Ended Schemes(Growth)");
        assertThat(r.category()).isEqualTo(SebiCategory.LEGACY_PRE_2018);
        assertThat(r.category().isActivelyManagedEquity()).isFalse();
    }

    @Test
    @DisplayName("index funds and ETFs are equity but are not in the screenable set")
    void indexAndEtfAreNotScreenable() {
        // They are judged on tracking difference against their own stated index, which is a
        // different question from excess return over a category benchmark.
        assertThat(SebiCategory.INDEX_EQUITY.assetClass()).isEqualTo(AssetClass.EQUITY);
        assertThat(SebiCategory.INDEX_EQUITY.isActivelyManagedEquity()).isFalse();
        assertThat(SebiCategory.EQUITY_ETF.isActivelyManagedEquity()).isFalse();
        assertThat(SebiCategory.FLEXI_CAP.isActivelyManagedEquity()).isTrue();
    }

    @Test
    @DisplayName("a null or blank header is unclassified rather than an exception")
    void nullSafe() {
        assertThat(SebiCategory.resolve(null).category()).isEqualTo(SebiCategory.UNCLASSIFIED);
        assertThat(SebiCategory.resolve("   ").category()).isEqualTo(SebiCategory.UNCLASSIFIED);
        assertThat(SebiCategory.resolve(null).rawHeader()).isEmpty();
    }
}
