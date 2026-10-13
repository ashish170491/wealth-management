package com.example.trading.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-175: every outbound client resolves host names through the JVM (and so through Windows' DNS
 * client), never through Netty's own DNS client, which stopped resolving NSE for minutes at a time
 * while the operating system resolved it fine.
 */
class JvmDnsTest {

    @Test
    @DisplayName("The shared mapper and helper both swap in the JVM resolver")
    void resolverIsTheJvms() {
        HttpClient c = JvmDns.apply(HttpClient.create());
        assertThat(c.configuration().resolver()).isSameAs(DefaultAddressResolverGroup.INSTANCE);
        assertThat(new JvmDns().jvmDnsResolver().configure(HttpClient.create()).configuration().resolver())
                .isSameAs(DefaultAddressResolverGroup.INSTANCE);
    }

    /**
     * A bare {@code WebClient.builder()} gets Netty's resolver back. The one allowed exception sets
     * its own connector and applies {@link JvmDns#apply} to it (the AMFI client, for its trust store).
     */
    @Test
    @DisplayName("No class builds a WebClient with Netty's DNS client")
    void noBareBuilders() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.endsWith("JvmDns.java"))
                    .filter(p -> {
                        try {
                            String s = Files.readString(p);
                            boolean bare = s.lines()
                                    .map(String::trim)
                                    .filter(l -> !l.startsWith("*") && !l.startsWith("//"))
                                    .anyMatch(l -> l.matches(".*(?<!JvmDns\\.)\\bWebClient\\.(builder|create)\\(.*"));
                            boolean coveredByOwnConnector = s.contains("JvmDns.apply(");
                            boolean bareHttpClient = s.contains("HttpClient.create()") && !s.contains("JvmDns.apply(");
                            return (bare && !coveredByOwnConnector) || bareHttpClient;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .map(p -> root.relativize(p).toString())
                    .toList();
        }
        assertThat(offenders).as("classes building a WebClient or HttpClient without JvmDns").isEmpty();
    }
}
