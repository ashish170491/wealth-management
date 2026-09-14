package com.example.trading.dashboard;

import com.example.trading.holdings.HoldingsReportService;
import com.example.trading.multibagger.MultibaggerReportService;
import com.example.trading.notification.MorningBriefingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Serves the scheduled reports as HTML for on-screen display, without emailing them
 * (SPEC section 27.6).
 *
 * <p><b>Why HTML and not JSON.</b> The richest analysis in this app - portfolio health
 * score, next-steps, action-required, market-intelligence overlay, ML insights, sector
 * rotation, wealth signals, capital efficiency - is computed inside {@code String.format}
 * argument lists in {@code HoldingsReportService} (2600+ lines) and
 * {@code MorningBriefingService}. No DTO survives those methods. Extracting ~38 sections
 * would be a multi-week refactor carrying real regression risk to reports the investor
 * reads every day. Serving the identical HTML is exact by construction and cannot drift.
 *
 * <p><b>Every endpoint here is click-only and cached.</b> A briefing build calls NSE and the
 * AI provider; a holdings report re-runs the full analysis pipeline. Both blow far past the
 * 2-second page-load budget in SPEC section 18, so the dashboard renders these in an iframe
 * behind an explicit button with a stated cost, never on page load.
 *
 * <p>Read-only: these methods build the report body and return it. They do not send email.
 */
@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportPreviewController {

    /**
     * How long a built report is reused. Long enough that clicking between reports (or a
     * reload) does not re-run an AI call; short enough that a refresh during the session
     * picks up new prices.
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);

    private final HoldingsReportService holdingsReportService;
    private final MorningBriefingService morningBriefingService;
    private final MultibaggerReportService multibaggerReportService;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Instant builtAt, String html) {
        boolean fresh() {
            return Duration.between(builtAt, Instant.now()).compareTo(CACHE_TTL) < 0;
        }
    }

    /**
     * @param name one of {@code holdings-actions}, {@code holdings-analysis},
     *             {@code holdings-weekly}, {@code morning-briefing},
     *             {@code multibagger-weekly}
     */
    @GetMapping(value = "/{name}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> preview(@PathVariable String name) {
        Supplier<String> builder = switch (name) {
            case "holdings-actions" -> holdingsReportService::previewDailyActionItems;
            case "holdings-analysis" -> holdingsReportService::previewDailyAnalysis;
            case "holdings-weekly" -> holdingsReportService::previewWeeklyReport;
            case "morning-briefing" -> morningBriefingService::buildBriefingHtml;
            // Renders whatever the last screening produced; never starts a screening itself.
            case "multibagger-weekly" -> multibaggerReportService::previewWeeklyReport;
            default -> null;
        };

        if (builder == null) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .body(message("Unknown report",
                            "No report is registered under the name '" + escape(name) + "'."));
        }

        Cached hit = cache.get(name);
        if (hit != null && hit.fresh()) {
            log.debug("Report preview '{}' served from cache (built {})", name, hit.builtAt());
            return html(hit.html());
        }

        try {
            long started = System.currentTimeMillis();
            String body = builder.get();
            log.info("Report preview '{}' built in {} ms", name, System.currentTimeMillis() - started);

            if (body == null || body.isBlank()) {
                // Distinguish "nothing to report" from "something broke" - an empty holdings
                // table and a failed build look identical in an iframe otherwise.
                return html(message("Nothing to report",
                        "This report had no content to build. That usually means no active holdings were found."));
            }

            cache.put(name, new Cached(Instant.now(), body));
            return html(body);
        } catch (Exception e) {
            log.error("Report preview '{}' failed: {}", name, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.TEXT_HTML)
                    .body(message("This report could not be built",
                            "The app hit an error while assembling it: " + escape(String.valueOf(e.getMessage()))));
        }
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
    }

    /** Minimal standalone page, styled to sit inside the dashboard's iframe. */
    private static String message(String title, String detail) {
        return """
                <!doctype html><html><head><meta charset="utf-8"><title>%s</title></head>
                <body style="font-family:-apple-system,'Segoe UI',Roboto,Arial,sans-serif;
                             color:#333;background:#f5f5f5;margin:0;padding:34px">
                  <div style="max-width:640px;margin:0 auto;background:#fff;border:1px dashed #e0e0e0;
                              border-radius:8px;padding:28px;text-align:center">
                    <div style="font-weight:600;font-size:16px;margin-bottom:6px">%s</div>
                    <div style="font-size:13.5px;color:#666">%s</div>
                  </div>
                </body></html>
                """.formatted(escape(title), escape(title), detail);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
