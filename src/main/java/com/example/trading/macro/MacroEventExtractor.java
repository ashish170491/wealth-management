package com.example.trading.macro;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns headlines into macro events, with a language model when one is configured and with rules
 * when one is not (SPEC §48.4).
 *
 * <p><b>The boundary: the model extracts, the map decides.</b> The prompt below asks for what
 * happened - which factor moved, which way, how far, whether it was on the calendar - and forbids
 * naming a company or a share price. Which businesses an event helps or hurts is settled afterwards
 * by {@link MacroExposureMap}, a written table the investor can read on screen. This is the same
 * line {@code ConcallAnalysisService} draws, and it exists because a model asked "which stocks does
 * this hurt?" produces a fluent, confident and unfalsifiable answer every time, including when it
 * is wrong.
 *
 * <p><b>There is no model bean unless one is asked for.</b> {@code spring.ai.model.chat} defaults to
 * {@code none}, so on an ordinary boot this class finds nothing in its {@link ObjectProvider} and
 * quietly uses {@link MacroKeywordExtractor}. Every event records which reader produced it, and the
 * dashboard shows that alongside the event: a reading is only as good as the thing that read it.
 *
 * <p><b>A model failure is never an outage.</b> A timeout, a refusal, a malformed reply - all of
 * them fall through to the keyword reader, and the extractor name says so
 * ({@code KEYWORD(fallback:...)}) rather than pretending the model was never configured.
 */
@Slf4j
@Component
public class MacroEventExtractor {

    /** What produced an event, when no model was configured at all. */
    public static final String KEYWORD = "KEYWORD";

    private final ObjectProvider<ChatModel> chatModels;

    public MacroEventExtractor(ObjectProvider<ChatModel> chatModels) {
        this.chatModels = chatModels;
    }

    /**
     * @param extractor a model id such as {@code openai:gpt-4o-mini}, {@code KEYWORD}, or
     *                  {@code KEYWORD(fallback:...)} when a model was configured and could not be used
     * @param note      one sentence for the ingest response, so the investor sees what happened
     */
    public record Result(List<ExtractedEvent.Validated> events, String extractor, String note) {
    }

    private static final String SYSTEM_PROMPT = """
            You read Indian and world business headlines and extract MACRO EVENTS: things that
            change the operating conditions of whole sectors, such as an interest-rate decision, a
            tariff, a currency move, the oil price, a monsoon shortfall or a regulatory change.

            RULES YOU MUST FOLLOW.

            1. Never name a company, a stock, a ticker or a share price. Not in any field. Which
               businesses an event affects is decided elsewhere, by a written rule table.
            2. Never give advice. Do not say buy, sell, hold, exit, book profits, or that anything
               is attractive or risky to own.
            3. Extract only events that HAVE HAPPENED. Ignore previews, forecasts, expectations,
               "ahead of Friday's meeting", polls and anything conditional. A decision that has not
               been taken is not an event.
            4. Ignore company results, management changes, single-stock news and market commentary
               about index levels. Those are not macro events.
            5. If several headlines describe the same event, return ONE event listing all of their
               numbers. Four outlets reporting one rate cut is one rate cut.
            6. If nothing in the list is a macro event, return an empty list. That is a normal and
               correct answer; most batches of news contain one or two events at most.

            THE FACTOR LIST. Use these names exactly. For each one, UP means the thing described
            after the arrow:

            CRUDE_OIL -> the price of Brent crude rose
            METALS_PRICES -> base metal prices rose
            COAL_POWER_PRICES -> imported coal or merchant power tariffs rose
            GOLD -> the gold price rose
            FOOD_INFLATION -> food prices rose
            INTEREST_RATES -> the RBI repo rate rose
            USDINR -> THE RUPEE WEAKENED against the dollar
            US_RATES -> the US Federal Reserve raised rates or turned hawkish
            US_TARIFFS -> the United States raised tariffs on Indian goods
            TRADE_BARRIERS_CHINA -> cheap Chinese imports rose, or a safeguard duty was removed
            GLOBAL_DEMAND_SLOWDOWN -> recession risk in the US or Europe rose
            GOVT_CAPEX -> the Indian government raised capital spending or announced orders
            DEFENCE_SPEND -> the defence budget or defence order flow rose
            GEOPOLITICAL_CONFLICT_REGIONAL -> tension on India's borders escalated
            GEOPOLITICAL_CONFLICT_GLOBAL -> a major conflict outside the region escalated
            MONSOON_DEFICIT -> monsoon rainfall ran below the long-period average
            REGULATORY_TELECOM -> a rule or dues demand went against telecom operators
            REGULATORY_CAPITAL_MARKETS -> SEBI tightened trading rules or transaction taxes rose
            REGULATORY_NBFC -> the RBI tightened risk weights or provisioning for lenders
            REGULATORY_SIN_GOODS -> excise or GST on tobacco or alcohol rose
            REGULATORY_PHARMA_USFDA -> the USFDA acted against a plant, or price controls tightened

            Note the direction carefully. "Rupee falls to a record low" is USDINR going UP, because
            the factor is how many rupees a dollar buys. "RBI cuts the repo rate" is INTEREST_RATES
            going DOWN. "Anti-dumping duty imposed on Chinese steel" is TRADE_BARRIERS_CHINA going
            DOWN, because the barrier to those imports went up.
            """;

    private static final String USER_TEMPLATE = """
            Here are today's headlines, numbered. Extract the macro events they report.

            %s
            """;

    /**
     * Read a batch of headlines.
     *
     * <p>Returns an empty event list rather than throwing on any failure, because an ingest that
     * finds nothing is an ordinary outcome and must not look like an error.
     */
    public Result extract(List<MacroKeywordExtractor.Headline> headlines) {
        if (headlines == null || headlines.isEmpty()) {
            return new Result(List.of(), configuredExtractor(), "No unread headlines to read.");
        }

        ChatModel model = chatModels.getIfAvailable();
        if (model == null) {
            return keywordResult(headlines, KEYWORD,
                    "Read with the app's keyword rules. No language model is configured "
                            + "(spring.ai.model.chat is none).");
        }

        String modelId = modelId(model);
        try {
            EventBatch batch = ChatClient.create(model)
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(String.format(USER_TEMPLATE, numbered(headlines)))
                    .call()
                    .entity(EventBatch.class);

            if (batch == null || batch.events() == null) {
                log.warn("Macro extraction: {} returned nothing usable; falling back to keyword rules. "
                        + "The events written will say so.", modelId);
                return keywordResult(headlines, KEYWORD + "(fallback:" + modelId + ")",
                        "The language model returned nothing readable, so the keyword rules read the "
                                + "headlines instead.");
            }

            List<String> problems = new ArrayList<>();
            List<ExtractedEvent.Validated> valid = new ArrayList<>();
            for (ExtractedEvent e : batch.events()) {
                if (e == null) continue;
                e.validate(headlines, problems).ifPresent(valid::add);
            }
            if (!problems.isEmpty()) {
                // Named rather than counted: "3 rows dropped" cannot be acted on, and a model
                // steadily emitting one bad value is a prompt fix rather than an acceptable loss.
                log.warn("Macro extraction: {} rows from {} were dropped in validation: {}",
                        problems.size(), modelId, problems);
            }
            log.info("Macro extraction: {} read {} headlines and returned {} usable events",
                    modelId, headlines.size(), valid.size());
            return new Result(List.copyOf(valid), modelId,
                    "Read by " + modelId + ": " + valid.size() + " event(s) from "
                            + headlines.size() + " headlines.");

        } catch (Exception ex) {
            log.warn("Macro extraction with {} failed ({}); falling back to keyword rules.",
                    modelId, ex.toString());
            return keywordResult(headlines, KEYWORD + "(fallback:" + modelId + ")",
                    "The language model could not be reached, so the keyword rules read the headlines "
                            + "instead. The events below say so.");
        }
    }

    /** What the extractor would use right now, for the status endpoint. */
    public String configuredExtractor() {
        ChatModel model = chatModels.getIfAvailable();
        return model == null ? KEYWORD : modelId(model);
    }

    /** True when a language model is wired up. */
    public boolean modelAvailable() {
        return chatModels.getIfAvailable() != null;
    }

    // ------------------------------------------------------------------ internals

    private Result keywordResult(List<MacroKeywordExtractor.Headline> headlines, String extractor, String note) {
        List<MacroKeywordExtractor.Extracted> raw = MacroKeywordExtractor.extract(headlines);
        List<ExtractedEvent.Validated> out = new ArrayList<>();
        for (MacroKeywordExtractor.Extracted e : raw) {
            out.add(new ExtractedEvent.Validated(e.factor(), e.direction(), e.magnitude(), e.kind(),
                    e.geography(), e.occurredAt(), e.headlineIds(), e.sourceUrls(), e.confidence(),
                    e.summary()));
        }
        log.info("Macro extraction: keyword rules read {} headlines and returned {} events",
                headlines.size(), out.size());
        return new Result(List.copyOf(out), extractor, note);
    }

    private static String numbered(List<MacroKeywordExtractor.Headline> headlines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < headlines.size(); i++) {
            MacroKeywordExtractor.Headline h = headlines.get(i);
            sb.append(i + 1).append(". ");
            if (h.publishedOn() != null) sb.append('[').append(h.publishedOn()).append("] ");
            sb.append(h.title() == null ? "" : h.title().trim());
            if (h.description() != null && !h.description().isBlank()) {
                String d = h.description().trim();
                sb.append(" -- ").append(d.length() > 300 ? d.substring(0, 300) : d);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * A short, stable identity for the model actually in use, e.g. {@code openai:gpt-4o-mini}.
     * Stored on every event, so a reading can always be traced to what produced it.
     */
    private static String modelId(ChatModel model) {
        String provider = model.getClass().getSimpleName()
                .replace("ChatModel", "")
                .toLowerCase(java.util.Locale.ROOT);
        String name = null;
        try {
            if (model.getDefaultOptions() != null) {
                name = model.getDefaultOptions().getModel();
            }
        } catch (Exception ignored) {
            // Options are provider-specific; an unavailable model name is not worth a failure.
        }
        return name == null || name.isBlank() ? provider : provider + ":" + name;
    }
}
