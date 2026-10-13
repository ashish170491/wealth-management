package com.example.trading.funds.amfi;

import com.example.trading.funds.MutualFundConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Fetches AMFI's daily all-scheme NAV file (SPEC §52.4).
 *
 * <p>One HTTP GET to {@code portal.amfiindia.com}, a host unrelated to both Kite and NSE — so this
 * competes for neither the ~2.9 req/s broker gate (Gotcha 23) nor the NSE session that the 09:45
 * and 10:00 jobs share. That is why the job can sit at 12:30 without a pacing gate.
 *
 * <p><b>The buffer is the whole reliability story.</b> Spring's WebClient default is 256 KB and the
 * body is 1.5 MB, so on the default the call fails, the catch returns nothing, and every consumer
 * reads that as <em>"there are no mutual funds"</em> — B-054 exactly, and the same failure CLAUDE.md
 * had already documented twice (the Kite instruments CSV, then the NSE client). The limit is set on
 * the builder so it cannot be forgotten at a call site.
 *
 * <p><b>Failure is reported, never degraded to empty.</b> {@link #fetchNavAll()} throws rather than
 * returning a blank string, because an empty file and a failed fetch must not look alike to the
 * caller: one means AMFI published nothing, the other means we did not ask successfully, and the
 * survivorship ledger would read the second as thousands of schemes winding up on the same day.
 */
@Slf4j
@Component
public class AmfiNavClient {

    private final MutualFundConfig config;
    private final WebClient webClient;

    public AmfiNavClient(MutualFundConfig config) {
        this.config = config;
        this.webClient = WebClient.builder()
                // 256 KB default vs a 1.5 MB body. See the class javadoc - this line is load-bearing.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(config.getMaxResponseBytes()))
                // AMFI chains to a Sectigo root newer than this JDK's bundled cacerts, so the
                // default trust store fails PKIX on every fetch while curl on the same machine
                // succeeds. Widened to the union of the JDK and OS root programmes - scoped to this
                // one client, and verification itself is untouched. See PlatformTrust.
                .clientConnector(new ReactorClientHttpConnector(secureHttpClient()))
                .build();
    }

    private static HttpClient secureHttpClient() {
        try {
            SslContext ssl = SslContextBuilder.forClient()
                    .trustManager(PlatformTrust.unionOfJdkAndOs())
                    .build();
            return com.example.trading.config.JvmDns.apply(HttpClient.create().secure(spec -> spec.sslContext(ssl)));
        } catch (Exception e) {
            // Fall back to the default client rather than to an insecure one. If the JDK trust
            // store alone cannot verify AMFI, the fetch fails loudly and says so - which is the
            // whole contract of this class.
            log.warn("Could not build the widened trust context ({}); falling back to the default "
                    + "JDK trust store. AMFI fetches may fail PKIX validation.", e.getMessage());
            return com.example.trading.config.JvmDns.apply(HttpClient.create());
        }
    }

    /**
     * @return the raw file, never null and never blank
     * @throws AmfiFetchException on any transport failure, an empty body, or a body that does not
     *         look like the expected feed
     */
    public String fetchNavAll() {
        String url = config.getNavAllUrl();
        log.info("Fetching AMFI NAV file from {}", url);
        String body;
        try {
            body = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(config.getTimeoutSeconds()));
        } catch (Exception e) {
            throw new AmfiFetchException("AMFI NAV fetch failed: " + e.getMessage(), e);
        }

        if (body == null || body.isBlank()) {
            throw new AmfiFetchException("AMFI returned an empty body - treating as a failed fetch, "
                    + "not as a day on which no scheme published a NAV");
        }

        // A sanity check on shape rather than size. If AMFI ever serves an error page with HTTP 200,
        // parsing it would yield zero rows and look exactly like a quiet day.
        if (!body.contains("Scheme Code")) {
            throw new AmfiFetchException("AMFI response does not contain the expected header row - "
                    + "the feed layout may have changed, or an error page was served with HTTP 200");
        }

        log.info("AMFI NAV file fetched: {} bytes", body.length());
        return body;
    }

    /**
     * AMFI's date format in the historical report's query string. Locale-pinned on purpose: under a
     * non-English default locale {@code MMM} renders a month name AMFI does not recognise, and the
     * endpoint answers HTTP 200 with an empty report rather than an error - which the caller would
     * read as "no fund published that month".
     */
    private static final DateTimeFormatter AMFI_DATE =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    /**
     * One historical day's file, for the month-end series (SPEC §52.7).
     *
     * <p>Same shape, same parser and same failure contract as {@link #fetchNavAll()}: a transport
     * failure throws rather than returning a blank string, because a month we failed to fetch and a
     * month in which nothing published must never look alike downstream.
     *
     * @param date the single day requested; {@code frmdt} and {@code todt} are both set to it
     */
    public String fetchNavHistory(LocalDate date) {
        String stamp = AMFI_DATE.format(date);
        String url = config.getNavHistoryUrl() + "?frmdt=" + stamp + "&todt=" + stamp;
        String body;
        try {
            body = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(config.getTimeoutSeconds()));
        } catch (Exception e) {
            throw new AmfiFetchException("AMFI history fetch for " + stamp + " failed: "
                    + e.getMessage(), e);
        }
        if (body == null || body.isBlank()) {
            throw new AmfiFetchException("AMFI returned an empty body for " + stamp
                    + " - treating as a failed fetch, not as a day on which nothing published");
        }
        if (!body.contains("Scheme Code")) {
            throw new AmfiFetchException("AMFI history response for " + stamp + " has no header row"
                    + " - the feed layout may have changed, or an error page was served with 200");
        }
        log.debug("AMFI history {}: {} bytes", stamp, body.length());
        return body;
    }

    /** Signals that the fetch did not succeed - deliberately distinct from a fetch that found nothing. */
    public static class AmfiFetchException extends RuntimeException {
        public AmfiFetchException(String message) {
            super(message);
        }

        public AmfiFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
