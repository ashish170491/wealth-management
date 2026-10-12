package com.example.trading.broker.kite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the series fallback (B-169).
 *
 * <p>Kite names a stock by its CURRENT NSE series. On 2026-10-12 HFCL, STLTECH, MTARTECH,
 * RBZJEWEL, ARIES and UGARSUGAR were listed as {@code -BE} (trade-to-trade surveillance) and
 * MONOLITH as {@code -SM}, so asking for the plain ticker returned {@code {status=success,
 * data={}}} - and B-013's one-way strip, written on the belief that Kite never carries the suffix,
 * could not recover them. The stub below answers exactly as Kite did that day.
 */
class KiteSeriesFallbackTest {

    /** A Kite that knows only these tradingsymbols, and records every symbol it was asked for. */
    private static KiteBrokerClient kiteKnowing(Set<String> known, List<String> asked) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            String i = request.url().getQuery() == null ? "" :
                    java.net.URLDecoder.decode(request.url().getQuery().replaceFirst("^i=", ""),
                            java.nio.charset.StandardCharsets.UTF_8);
            asked.add(i);
            String body = known.contains(i)
                    ? "{\"status\":\"success\",\"data\":{\"" + i + "\":{\"instrument_token\":12345,\"last_price\":101.5}}}"
                    : "{\"status\":\"success\",\"data\":{}}";
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body).build());
        });
        return new KiteBrokerClient(new KiteConfig(), builder);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<String, Object> quote) {
        return (Map<String, Object>) quote.get("data");
    }

    @Test
    @DisplayName("A stock in the trade-to-trade series is found, and answered under the name the app asked for")
    void findsBeSeries() {
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        KiteBrokerClient kite = kiteKnowing(Set.of("NSE:HFCL-BE"), asked);
        Map<String, Object> q = kite.getQuote("NSE:HFCL");
        assertThat(dataOf(q)).containsKey("NSE:HFCL");      // callers read data.get(requested symbol)
        assertThat(asked).containsExactly("NSE:HFCL", "NSE:HFCL-BE");
    }

    @Test
    @DisplayName("The resolved form is remembered: the second lookup asks Kite once")
    void remembersTheForm() {
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        KiteBrokerClient kite = kiteKnowing(Set.of("NSE:MONOLITH-SM"), asked);
        kite.getQuote("NSE:MONOLITH");
        asked.clear();
        assertThat(dataOf(kite.getQuote("NSE:MONOLITH"))).containsKey("NSE:MONOLITH");
        assertThat(asked).containsExactly("NSE:MONOLITH-SM");
    }

    @Test
    @DisplayName("A plain listing costs exactly one call - the fallback only runs when Kite says nothing")
    void plainListingIsOneCall() {
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        assertThat(dataOf(kiteKnowing(Set.of("NSE:TCS"), asked).getQuote("NSE:TCS"))).containsKey("NSE:TCS");
        assertThat(asked).containsExactly("NSE:TCS");
    }

    @Test
    @DisplayName("A symbol no board answers (a rename like HEG -> HEGAM) is tried once per session, never replaced by a guess")
    void deadSymbolIsNotRetriedOrGuessed() {
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        KiteBrokerClient kite = kiteKnowing(Set.of("NSE:HEGAM"), asked);
        assertThat(dataOf(kite.getQuote("NSE:HEG"))).isEmpty();
        assertThat(asked).doesNotContain("NSE:HEGAM");     // a different ticker is a different company to this code
        asked.clear();
        kite.getQuote("NSE:HEG");
        assertThat(asked).containsExactly("NSE:HEG");     // remembered as unresolvable: one call, not six
    }

    @Test
    @DisplayName("B-013's case still works: a holding stored with a suffix Kite no longer uses")
    void storedSuffixStillStrips() {
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        assertThat(dataOf(kiteKnowing(Set.of("NSE:KWIL"), asked).getQuote("NSE:KWIL-BE"))).containsKey("NSE:KWIL-BE");
        assertThat(asked).containsExactly("NSE:KWIL-BE", "NSE:KWIL");
    }

    @Test
    @DisplayName("Indices and BSE symbols never get NSE series forms")
    void candidateShapes() {
        assertThat(KiteBrokerClient.seriesCandidates("NSE:NIFTY 50")).containsExactly("NSE:NIFTY 50");
        assertThat(KiteBrokerClient.seriesCandidates("BSE:INFY")).containsExactly("BSE:INFY");
        assertThat(KiteBrokerClient.seriesCandidates("NSE:HFCL"))
                .containsExactly("NSE:HFCL", "NSE:HFCL-BE", "NSE:HFCL-BZ", "NSE:HFCL-SM", "NSE:HFCL-ST");
    }
}
