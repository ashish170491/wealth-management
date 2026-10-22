package com.example.trading.valuation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.trading.valuation.ValuationFixtures.eligible;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §57.7 wire contract. There is no build step for {@code static/js} and nothing type-checks the
 * wire, so a renamed field does not fail: the panel finds {@code undefined} and draws "not measured"
 * for ever on a stock the app measured perfectly well (Gotcha 44, pointed the other way, and quieter).
 * These tests make the rename fail instead.
 */
class ValuationSurfaceContractTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    @Test
    @DisplayName("format.js has a plain-English label for every status and reason code, so the UI never prints VG_STYLE_SHOUTING")
    void everyCodeHasALabel() throws IOException {
        String format = Files.readString(JS.resolve("format.js"));
        for (String code : ValuationReading.vocabulary()) {
            if (code.startsWith("GATES_")) continue;       // a stored label, never drawn
            if (code.equals("NOT_FITTED")) continue;
            assertThat(Pattern.compile("\\b" + code + "\\s*:").matcher(format).find())
                    .as("format.js LABELS entry for %s", code).isTrue();
        }
    }

    @Test
    @DisplayName("every field valuation-gap.js reads from the reading exists on the wire")
    void fieldsTheJsReadsExist() throws Exception {
        JsonNode measured = JSON.valueToTree(ValuationGapService.assemble(eligible("NSE:GOOD")));
        String js = Files.readString(JS.resolve("valuation-gap.js"));

        List<String> topLevel = List.of("status", "reasonCode", "reason", "valuationDate", "price", "priceNote",
                "earnings", "trendComparison", "requiredGrowthGrid", "section125Reading", "blocks",
                "appliedRules", "differenceBetweenMethods", "coverageLine", "caveats", "symbol");
        for (String f : topLevel) {
            if (f.equals("reasonCode") || f.equals("reason")) continue;     // absent on a full reading by design
            assertThat(measured.has(f)).as("top-level field %s", f).isTrue();
        }
        for (String f : topLevel) assertThat(js).as("the panel reads %s", f).contains(f);

        JsonNode earnings = measured.get("earnings");
        for (String f : List.of("series", "corporateActions", "normalisedEps", "normalisation", "crossCheck", "trend")) {
            assertThat(earnings.has(f)).as("earnings.%s", f).isTrue();
            assertThat(js).contains(f);
        }
        for (String f : List.of("growthPct", "stdErrPp", "observations", "firstFy", "lastFy", "rSquared", "endpointCagrPct")) {
            assertThat(earnings.get("trend").has(f)).as("trend.%s", f).isTrue();
            assertThat(js).contains(f);
        }
        for (String f : List.of("fy", "netProfitCr", "shareCountCr", "shareFactor", "epsAdjusted")) {
            assertThat(earnings.get("series").get(0).has(f)).as("series[].%s", f).isTrue();
            assertThat(js).contains(f);
        }
        JsonNode grid = measured.get("requiredGrowthGrid");
        for (String f : List.of("methodology", "rows", "limits")) assertThat(grid.has(f)).as(f).isTrue();
        for (String f : List.of("referenceMultiple", "requiredGrowthPct", "gapToTrendPp", "atDiscount")) {
            assertThat(grid.get("rows").get(0).has(f)).as("rows[].%s", f).isTrue();
            assertThat(js).contains(f);
        }
        JsonNode d = measured.get("trendComparison");
        for (String f : List.of("label", "statement", "pe0", "epsIfTrendContinues", "referenceYear")) {
            assertThat(d.has(f)).as("trendComparison.%s", f).isTrue();
            assertThat(js).contains(f);
        }
        assertThat(measured.get("section125Reading").has("impliedGrowthPct")).isTrue();
        assertThat(measured.get("section125Reading").has("pointer")).isTrue();
        assertThat(js).contains("impliedGrowthPct").contains("pointer");
    }

    @Test
    @DisplayName("the stock page loads the new endpoint on page load, DB-only, and does not touch the research allowlist")
    void pageStockWiring() throws IOException {
        String page = Files.readString(JS.resolve("page-stock.js"));
        assertThat(page).contains("/api/valuation-gap?symbol=").contains("valuationGapPanel");
        assertThat(page).contains("DB-only");
        String api = Files.readString(JS.resolve("api.js"));
        assertThat(api).doesNotContain("valuation-gap");     // the page-load get() is ungated; nothing slow is allowlisted
        Matcher post = Pattern.compile("post\\([^)]*valuation-gap").matcher(Files.readString(JS.resolve("page-stock.js")));
        assertThat(post.find()).isFalse();                   // no write is reachable from the page module itself
    }

    @Test
    @DisplayName("the only POST the panel can make is the optional challenger, behind a confirmation")
    void onlyTheChallengerPosts() throws IOException {
        String js = Files.readString(JS.resolve("valuation-gap.js"));
        Matcher m = Pattern.compile("post\\(`([^`]*)`").matcher(js);
        int posts = 0;
        while (m.find()) {
            posts++;
            assertThat(m.group(1)).startsWith("/api/valuation-gap/challenge");
        }
        assertThat(posts).isEqualTo(1);
        assertThat(js.indexOf("window.confirm")).isLessThan(js.indexOf("post(`"));
        assertThat(js).doesNotContain("/snapshot");
    }

    @Test
    @DisplayName("the panel puts the trend comparison and the grid in different cards with another card between them")
    void cardOrder() throws IOException {
        String js = Files.readString(JS.resolve("valuation-gap.js"));
        int trend = js.indexOf("nodes.push(recordCard(r), trendCard(r), dcfCard(r), gridCard(r));");
        assertThat(trend).isPositive();
    }
}
