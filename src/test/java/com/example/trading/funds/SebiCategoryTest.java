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

    private static final String HISTORICAL_HEADERS =
            "/funds/amfi-category-headers-history-2018-2026.txt";

    private static List<String> historicalHeaders() {
        try (InputStream in = SebiCategoryTest.class.getResourceAsStream(HISTORICAL_HEADERS)) {
            assertThat(in).as("captured archive header fixture must be on the classpath").isNotNull();
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + HISTORICAL_HEADERS, e);
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
    @DisplayName("debt is spelled under TWO taxonomies and both must resolve to one category")
    void debtTaxonomiesMerge() {
        // SEBI recategorised debt in 2018 and AMFI never retired the old headers, so the same live
        // file carries both with current NAV dates. Liquid is 312 rows under the modern header and
        // 186 under the legacy one; Banking & PSU is 25 modern and 150 legacy, so matching only the
        // modern taxonomy would leave 14% of that peer group.
        assertThat(SebiCategory.resolve("Open Ended Schemes(Debt Scheme - Liquid Fund)").category())
                .isEqualTo(SebiCategory.DEBT_LIQUID);
        assertThat(SebiCategory.resolve(
                "Open Ended Schemes(Income/Debt Oriented Schemes - Liquid Fund)").category())
                .isEqualTo(SebiCategory.DEBT_LIQUID);

        assertThat(SebiCategory.resolve("Open Ended Schemes(Debt Scheme - Banking and PSU Fund)").category())
                .isEqualTo(SebiCategory.DEBT_BANKING_PSU);
        assertThat(SebiCategory.resolve(
                "Open Ended Schemes(Income/Debt Oriented Schemes - Banking and PSU Debt Fund)").category())
                .isEqualTo(SebiCategory.DEBT_BANKING_PSU);

        // The legacy wording says "Term", the modern one says "Duration". Same mandate.
        assertThat(SebiCategory.resolve("Open Ended Schemes(Debt Scheme - Short Duration Fund)").category())
                .isEqualTo(SebiCategory.DEBT_SHORT_DURATION);
        assertThat(SebiCategory.resolve(
                "Open Ended Schemes(Income/Debt Oriented Schemes - Short Term Fund)").category())
                .isEqualTo(SebiCategory.DEBT_SHORT_DURATION);
    }

    @Test
    @DisplayName("every pre-2018 debt header contains the word Debt, and must not collapse into it")
    void legacyDebtHeadersDoNotAllCollapseIntoOneBucket() {
        // Every legacy header reads "Income/Debt Oriented Schemes - ...", so a bare DEBT keyword
        // placed early in the fallback chain would swallow all fourteen SEBI categories into one
        // bucket while nothing threw and the row count stayed right. This is that guard.
        Set<SebiCategory> distinct = capturedHeaders().stream()
                .filter(h -> h.contains("Income/Debt Oriented Schemes"))
                .map(h -> SebiCategory.resolve(h).category())
                .collect(Collectors.toSet());

        assertThat(distinct)
                .as("distinct categories reached by the pre-2018 debt headers")
                .hasSizeGreaterThanOrEqualTo(12);
    }

    @Test
    @DisplayName("the straddling pre-2018 bucket is named, never filed under a neighbour")
    void straddlingBucketIsNotGuessed() {
        // 255 rows, 235 of them current. Its mandate runs from three months to three years, which
        // is an order of magnitude of duration - so filing it under Ultra Short or Short Duration
        // would answer "is this right for six-month money" from a header that cannot say.
        SebiCategory.Resolved r = SebiCategory.resolve(
                "Open Ended Schemes(Income/Debt Oriented Schemes - Ultra Short to Short Term Fund)");
        assertThat(r.category()).isEqualTo(SebiCategory.DEBT);
        assertThat(r.category()).isNotEqualTo(SebiCategory.DEBT_ULTRA_SHORT);
        assertThat(r.category()).isNotEqualTo(SebiCategory.DEBT_SHORT_DURATION);
    }

    @Test
    @DisplayName("hybrid categories merge across both spellings and keep their equity share distinct")
    void hybridSpellingsMerge() {
        assertThat(SebiCategory.resolve("Open Ended Schemes(Hybrid Scheme - Arbitrage Fund)").category())
                .isEqualTo(SebiCategory.HYBRID_ARBITRAGE);
        assertThat(SebiCategory.resolve("Open Ended Schemes(Hybrid Schemes - Arbitrage Fund)").category())
                .isEqualTo(SebiCategory.HYBRID_ARBITRAGE);

        // AMFI writes this one with and without the trailing "Fund", and with the two halves of the
        // name in the opposite order.
        assertThat(SebiCategory.resolve(
                "Open Ended Schemes(Hybrid Scheme - Dynamic Asset Allocation or Balanced Advantage)")
                .category()).isEqualTo(SebiCategory.HYBRID_BALANCED_ADVANTAGE);
        assertThat(SebiCategory.resolve(
                "Open Ended Schemes(Hybrid Schemes - Balanced Advantage Fund/ Dynamic Asset Allocation)")
                .category()).isEqualTo(SebiCategory.HYBRID_BALANCED_ADVANTAGE);

        // Conservative and Aggressive differ only in how much equity they hold, which is the one
        // thing that decides the horizon - so they must never merge with each other.
        assertThat(SebiCategory.resolve("Open Ended Schemes(Hybrid Scheme - Conservative Hybrid Fund)")
                .category()).isEqualTo(SebiCategory.HYBRID_CONSERVATIVE);
        assertThat(SebiCategory.resolve("Open Ended Schemes(Hybrid Schemes - Aggressive Hybrid Fund)")
                .category()).isEqualTo(SebiCategory.HYBRID_AGGRESSIVE);
    }

    @Test
    @DisplayName("every header in the 2018-2026 archive resolves too, not just today's file")
    void everyHistoricalHeaderResolves() {
        // The backfill parses eight years of files. A header that only ever appeared in 2019 still
        // has to resolve, or those months silently contribute no schemes and the gap looks like a
        // market with fewer funds in it.
        List<String> unresolved = historicalHeaders().stream()
                .filter(h -> SebiCategory.resolve(h).isUnclassified())
                .toList();

        assertThat(unresolved)
                .as("archive headers with no rule - add a rule, do not delete the case")
                .isEmpty();
    }

    @Test
    @DisplayName("widening the kept universe leaves the screenable set exactly where it was")
    void wideningDoesNotTouchTheScreenableSet() {
        // The safety property of SPEC 52.15, asserted rather than asserted-in-a-comment: every
        // category added for debt and hybrid answers false to isActivelyManagedEquity, so no equity
        // peer median, percentile or coverage count can move. Same shape as 52.14's index pass.
        List<SebiCategory> wronglyScreenable = java.util.Arrays.stream(SebiCategory.values())
                .filter(c -> c.assetClass() == AssetClass.DEBT || c.assetClass() == AssetClass.HYBRID)
                .filter(SebiCategory::isActivelyManagedEquity)
                .toList();
        assertThat(wronglyScreenable).isEmpty();

        // And they ARE kept, which is the other half - a category that resolves but is not kept is
        // invisible in a different way.
        assertThat(SebiCategory.DEBT_LIQUID.isKeepableAssetClass()).isTrue();
        assertThat(SebiCategory.HYBRID_ARBITRAGE.isKeepableAssetClass()).isTrue();
        assertThat(SebiCategory.FLEXI_CAP.isKeepableAssetClass()).isTrue();

        // An exchange-traded fund is never kept: its NAV is not the price you would pay for it.
        assertThat(SebiCategory.GOLD_ETF.isExchangeTraded()).isTrue();
        assertThat(SebiCategory.DEBT_ETF.isExchangeTraded()).isTrue();
        assertThat(SebiCategory.EQUITY_ETF.isExchangeTraded()).isTrue();
        assertThat(SebiCategory.DEBT_LIQUID.isExchangeTraded()).isFalse();
    }

    @Test
    @DisplayName("a null or blank header is unclassified rather than an exception")
    void nullSafe() {
        assertThat(SebiCategory.resolve(null).category()).isEqualTo(SebiCategory.UNCLASSIFIED);
        assertThat(SebiCategory.resolve("   ").category()).isEqualTo(SebiCategory.UNCLASSIFIED);
        assertThat(SebiCategory.resolve(null).rawHeader()).isEmpty();
    }
}
