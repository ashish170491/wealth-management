package com.example.trading.analyst;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins which company a headline is filed against (SPEC §49.4).
 *
 * <p>A misattributed target is not a small error: the ledger's entire output is attribution, and
 * a call filed against the wrong company is a wrong claim about both of them. So the cases below
 * are mostly about refusing.
 */
class HeadlineSubjectResolverTest {

    @Test
    @DisplayName("The company name in the headline resolves to its ticker")
    void resolvesByCompanyName() {
        HeadlineSubjectResolver.Subject s = HeadlineSubjectResolver.resolve(
                "Motilal Oswal raises Bharti Airtel target price to Rs 2,100");

        assertThat(s.resolved()).isTrue();
        assertThat(s.symbol()).isEqualTo("NSE:BHARTIARTL");
        // The matched text travels with the answer so an attribution can be audited rather than
        // trusted — the same discipline as DecayAlert.resolvedSymbol (Gotcha 84).
        assertThat(s.matchedOn()).isEqualTo("BHARTI AIRTEL");
    }

    @Test
    @DisplayName("A bare ticker in capitals resolves when no company name does")
    void resolvesByTicker() {
        HeadlineSubjectResolver.Subject s = HeadlineSubjectResolver.resolve(
                "Jefferies upgrades NTPC to Buy, target Rs 450");

        // NTPC matches both ways here (the company is literally "NTPC Ltd."), which is fine — the
        // point of the case is that a capitalised ticker is a usable subject.
        assertThat(s.symbol()).isEqualTo("NSE:NTPC");
    }

    @Test
    @DisplayName("Two different companies in one headline resolve to neither")
    void ambiguityRefuses() {
        HeadlineSubjectResolver.Subject s = HeadlineSubjectResolver.resolve(
                "Nomura prefers Infosys over Tata Consultancy Services, target Rs 1,900");

        assertThat(s.resolved()).isFalse();
        assertThat(s.miss()).isEqualTo(HeadlineSubjectResolver.Miss.AMBIGUOUS);
        assertThat(s.alsoMatched()).contains("INFY", "TCS");
    }

    @Test
    @DisplayName("One company written two ways is not two companies")
    void longestContainingMatchWins() {
        // "HDFC Bank" also contains "HDFC". Refusing here would throw away a perfectly clear
        // headline, so a match that strictly contains the others wins.
        HeadlineSubjectResolver.Subject s = HeadlineSubjectResolver.resolve(
                "CLSA maintains Buy on HDFC Bank Ltd with target price Rs 2,200");

        assertThat(s.symbol()).isEqualTo("NSE:HDFCBANK");
    }

    @Test
    @DisplayName("A headline naming no listed company resolves to nothing")
    void noMatchIsARefusal() {
        HeadlineSubjectResolver.Subject s = HeadlineSubjectResolver.resolve(
                "Brokerages turn cautious on mid-caps after the rally");

        assertThat(s.resolved()).isFalse();
        assertThat(s.miss()).isEqualTo(HeadlineSubjectResolver.Miss.NO_MATCH);
    }

    @Test
    @DisplayName("An all-caps word that is also an abbreviation is not a ticker")
    void stoplistedTokensAreNotTickers() {
        // Without the stoplist a Budget headline lands on whichever company is listed nearest to
        // the word, and a sector story becomes a call on one stock.
        assertThat(HeadlineSubjectResolver.resolve(
                "SEBI and RBI tighten NBFC norms; GST relief likely in the Budget").resolved()).isFalse();
    }

    @Test
    @DisplayName("Nothing at all is a refusal, not a crash")
    void nullSafe() {
        assertThat(HeadlineSubjectResolver.resolve(null).resolved()).isFalse();
        assertThat(HeadlineSubjectResolver.resolve("  ").resolved()).isFalse();
    }

    @Test
    @DisplayName("The name index is actually populated — an empty one would refuse everything silently")
    void indexIsPopulated() {
        // The failure this guards against is the quiet one: a missing or renamed classpath file
        // makes every headline unresolvable, which looks exactly like "no analyst published
        // anything" (Gotcha 106's question — measured on how many?).
        assertThat(HeadlineSubjectResolver.indexedNames()).isGreaterThan(500);
    }

    @Test
    @DisplayName("Corporate suffixes are stripped, and a name too generic to match is dropped")
    void indexKeyRules() {
        assertThat(HeadlineSubjectResolver.indexKey("Reliance Industries Ltd.")).isEqualTo("RELIANCE INDUSTRIES");
        assertThat(HeadlineSubjectResolver.indexKey("Infosys Limited")).isEqualTo("INFOSYS");
        // Four characters is a word before it is a company.
        assertThat(HeadlineSubjectResolver.indexKey("ABC Ltd")).isNull();
    }

    @Test
    @DisplayName("A name only matches on whole tokens")
    void tokenBoundaries() {
        assertThat(HeadlineSubjectResolver.containsTokenSequence("SWITCH GEAR MAKER", "ITC")).isFalse();
        assertThat(HeadlineSubjectResolver.containsTokenSequence("ITC GAINS ON RESULTS", "ITC")).isTrue();
    }
}
