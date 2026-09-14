package com.example.trading.broker.kite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the request-pacing gate that keeps the process under Kite's ~3 req/s cap (B-027).
 *
 * <p>Retry-with-backoff was already in place and still lost 31 quotes to exhausted 429 retries
 * in a single day, because retrying only reacts once the limit has been breached. The gate is
 * the proactive half. It is lock-free and shared across the 4-thread scheduler pool, so its
 * behaviour under concurrency is worth holding still.
 */
class KiteRequestPacingTest {

    /** reserveSlot is private and has no collaborators; call it directly. */
    private static Duration reserve(KiteBrokerClient client) throws Exception {
        Method m = KiteBrokerClient.class.getDeclaredMethod("reserveSlot");
        m.setAccessible(true);
        return (Duration) m.invoke(client);
    }

    private static long intervalMs() throws Exception {
        java.lang.reflect.Field f =
                KiteBrokerClient.class.getDeclaredField("MIN_REQUEST_INTERVAL_MS");
        f.setAccessible(true);
        return (long) f.get(null);
    }

    /** A client with no HTTP wiring exercised — reserveSlot touches only the atomic slot counter. */
    private static KiteBrokerClient newClient() {
        return new KiteBrokerClient(new KiteConfig(), org.springframework.web.reactive.function.client.WebClient.builder());
    }

    @Test
    @DisplayName("The first request through an idle gate is not delayed")
    void firstRequestIsImmediate() throws Exception {
        assertThat(reserve(newClient())).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("Consecutive requests are spaced by at least the minimum interval")
    void consecutiveRequestsAreSpaced() throws Exception {
        KiteBrokerClient client = newClient();
        long interval = intervalMs();

        reserve(client); // claims "now"
        Duration second = reserve(client);
        Duration third = reserve(client);

        // Each subsequent caller waits one more interval than the last.
        assertThat(second.toMillis()).isBetween(interval - 50, interval + 50);
        assertThat(third.toMillis()).isBetween(2 * interval - 50, 2 * interval + 50);
    }

    @Test
    @DisplayName("Under concurrency every caller gets a distinct slot — no two requests share one")
    void concurrentCallersDoNotShareASlot() throws Exception {
        KiteBrokerClient client = newClient();
        long interval = intervalMs();
        int callers = 12;

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                tasks.add(() -> reserve(client).toMillis());
            }
            List<Future<Long>> futures = pool.invokeAll(tasks);

            List<Long> waits = new ArrayList<>();
            for (Future<Long> f : futures) waits.add(f.get());
            waits.sort(Long::compareTo);

            // The k-th caller waits ~k intervals. If the gate raced, two callers would come back
            // with the same wait and their requests would leave together — exactly the burst
            // that produces a 429.
            for (int i = 0; i < callers; i++) {
                assertThat(waits.get(i))
                        .as("caller %d should hold slot %d", i, i)
                        .isBetween((long) i * interval - 120, (long) i * interval + 120);
            }
            assertThat(waits).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("The gate stays under Kite's 3 req/s ceiling")
    void staysUnderKiteRateLimit() throws Exception {
        double requestsPerSecond = 1000.0 / intervalMs();
        assertThat(requestsPerSecond)
                .as("Kite answers a burst above ~3 req/s with HTTP 429")
                .isLessThanOrEqualTo(3.0);
    }
}
