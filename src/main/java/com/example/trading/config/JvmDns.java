package com.example.trading.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.boot.autoconfigure.web.reactive.function.client.ReactorNettyHttpClientMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Makes every outbound HTTP call look host names up through the JVM, and so through Windows'
 * own DNS client, instead of Netty's built-in DNS client (B-175).
 *
 * <p>Reactor Netty resolves names with its own asynchronous DNS client by default. On this laptop
 * that client stopped resolving NSE, NSE's archive, Kite and Google News for minutes at a time
 * ("Failed to resolve 'www.nseindia.com' [A(1)]") while {@code nslookup} on the same machine
 * answered at once, and it stayed broken until the process restarted. Failures were logged every
 * day from 8 Oct, including 26 during the 12 Oct 14:00 screening, and each one turned a stock's
 * fundamentals or shareholding into "not measured" for that run. On 13 Oct it killed two
 * screening re-runs outright. The JVM resolver goes through the operating system, which
 * retries, caches and follows network changes the way every other program on the machine does.
 *
 * <p>Two entry points, because the app builds its clients two ways: {@link #webClientBuilder()}
 * replaces {@code WebClient.builder()} where a class builds its own, and the
 * {@link ReactorNettyHttpClientMapper} bean below covers every class that takes Spring's shared
 * {@code WebClient.Builder}. A class that sets its own connector (the AMFI client) must apply
 * {@link #apply(HttpClient)} itself, since a connector set on a builder replaces this one.
 */
@Configuration
public class JvmDns {

    /** Netty client with the JVM's (operating system's) name resolver. */
    public static HttpClient apply(HttpClient client) {
        return client.resolver(DefaultAddressResolverGroup.INSTANCE);
    }

    /** Use instead of {@code WebClient.builder()}: the same builder, resolving through the JVM. */
    public static WebClient.Builder webClientBuilder() {
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(apply(HttpClient.create())));
    }

    /** Applies the same resolver to Spring's auto-configured {@code WebClient.Builder}. */
    @Bean
    ReactorNettyHttpClientMapper jvmDnsResolver() {
        return JvmDns::apply;
    }
}
