package com.example.trading.valuation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An optional second reader that <i>questions</i> a computed reading (SPEC §57.8). Off by default.
 *
 * <p><b>Two gates, both closed on a normal boot.</b> {@code valuation-gap.challenge-enabled} is false,
 * and {@code spring.ai.model.chat} is {@code none}, so no {@code ChatModel} bean exists and the
 * {@link ObjectProvider} is empty (CLAUDE.md §7a). Either one alone answers {@code DISABLED} or
 * {@code AI_NOT_CONFIGURED}; nothing is called.
 *
 * <p><b>It can only ask.</b> The model receives the already-computed reading as text and returns up
 * to five {@link AssumptionCritique}s. It never alters a result, and nothing reads a critique back:
 * not the service, not the snapshot (a snapshot never stores AI text), not any score. Its output is
 * displayed with the model's name beside it, and a failure of any kind is an ordinary status, never
 * an exception.
 *
 * <p><b>What it is allowed to say is enforced after the fact, not requested.</b> A row is dropped if
 * its target or kind is not in the closed lists, if it quotes a number that is not in the input
 * (within rounding), or if it uses transaction or forecast wording. This is the same posture as the
 * macro extractor: a model's output is untrusted until validated.
 */
@Slf4j
@Component
public class ValuationAssumptionChallenger {

    public enum Status { DISABLED, AI_NOT_CONFIGURED, READING_UNAVAILABLE, OK, MODEL_ERROR }

    public record Critique(String target, String kind, String text) {
    }

    public record Result(Status status, List<Critique> critiques, String model, String note) {
    }

    static final Set<String> TARGETS = Set.of("NORMALISED_EPS", "TREND", "SHARE_ADJUSTMENT",
            "REFERENCE_MULTIPLES", "HURDLE_RATE", "REVERSE_DCF", "DATA");
    static final Set<String> KINDS = Set.of("QUESTIONABLE_ASSUMPTION", "MISSING_EVIDENCE", "POSSIBLE_CONTRADICTION");

    /** Words a critique may not use: advice and prediction. Matched on word boundaries. */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(buy|sell|hold|exit|accumulate|avoid|target|forecast|forecasts|predict|predicts|prediction|"
                    + "expected|upside|recommend|recommended|undervalued|overvalued|cheap|expensive|will rise|will fall)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?");

    private static final ObjectMapper PAYLOAD = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private static final String SYSTEM_PROMPT = """
            You review a table that compares a stock's price with its own past earnings record. The
            table was computed by fixed rules. You are given its inputs and its results as JSON.

            Your only job is to raise QUESTIONS about the assumptions behind the table: a figure that
            looks inconsistent with another, evidence that is missing, an assumption that looks
            doubtful for this particular business.

            RULES YOU MUST FOLLOW.
            1. Do not advise. Never say buy, sell, hold, exit, accumulate or avoid, and never say a
               stock is cheap, expensive, attractive or risky to own.
            2. Do not say what will happen. Never forecast, predict or give a price or earnings level
               for any future date.
            3. Use only numbers that appear in the JSON you are given. Do not introduce a figure of
               your own.
            4. Do not change or recompute any result. You may only question it.
            5. Return at most five questions. If nothing looks questionable, return an empty list. That
               is a normal and correct answer.
            6. Each question names one target from this list, exactly: NORMALISED_EPS, TREND,
               SHARE_ADJUSTMENT, REFERENCE_MULTIPLES, HURDLE_RATE, REVERSE_DCF, DATA.
            """;

    private final ObjectProvider<ChatModel> chatModels;
    private final ValuationGapConfig config;

    public ValuationAssumptionChallenger(ObjectProvider<ChatModel> chatModels, ValuationGapConfig config) {
        this.chatModels = chatModels;
        this.config = config;
    }

    /** Never throws; every failure is a status. */
    public Result challenge(ValuationReading reading) {
        if (!config.isChallengeEnabled()) {
            return new Result(Status.DISABLED, List.of(), null,
                    "The assumption challenger is switched off (valuation-gap.challenge-enabled is false).");
        }
        ChatModel model = chatModels.getIfAvailable();
        if (model == null) {
            return new Result(Status.AI_NOT_CONFIGURED, List.of(), null,
                    "No language model is configured (spring.ai.model.chat is none).");
        }
        if (reading == null || reading.status() == ValuationReading.Status.UNAVAILABLE) {
            return new Result(Status.READING_UNAVAILABLE, List.of(), null,
                    "There is no computed reading to question for this stock.");
        }
        String modelId = model.getClass().getSimpleName().replace("ChatModel", "").toLowerCase(Locale.ROOT);
        try {
            String payload = PAYLOAD.writeValueAsString(reading);
            CritiqueBatch batch = ChatClient.create(model)
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user("Here is the reading:\n" + payload)
                    .call()
                    .entity(CritiqueBatch.class);
            if (batch == null || batch.critiques() == null) {
                return new Result(Status.MODEL_ERROR, List.of(), modelId, "The model returned nothing readable.");
            }
            List<String> problems = new ArrayList<>();
            List<Critique> valid = validate(batch.critiques(), payload, problems);
            if (!problems.isEmpty()) {
                log.warn("Valuation challenger: {} row(s) from {} dropped in validation: {}",
                        problems.size(), modelId, problems);
            }
            return new Result(Status.OK, valid, modelId, valid.size() + " question(s) raised"
                    + (problems.isEmpty() ? "." : "; " + problems.size() + " more were dropped as unusable."));
        } catch (Exception e) {
            log.warn("Valuation challenger with {} failed: {}", modelId, e.toString());
            return new Result(Status.MODEL_ERROR, List.of(), modelId, "The language model could not be used.");
        }
    }

    /** Pure and static so the validation can be tested without a model. */
    static List<Critique> validate(List<AssumptionCritique> rows, String payload, List<String> problems) {
        List<Double> known = numbersIn(payload);
        List<Critique> out = new ArrayList<>();
        for (AssumptionCritique c : rows) {
            if (c == null || c.text() == null || c.text().isBlank()) {
                problems.add("empty row");
                continue;
            }
            String target = c.target() == null ? "" : c.target().trim().toUpperCase(Locale.ROOT);
            String kind = c.kind() == null ? "" : c.kind().trim().toUpperCase(Locale.ROOT);
            if (!TARGETS.contains(target)) {
                problems.add("unknown target '" + c.target() + "'");
                continue;
            }
            if (!KINDS.contains(kind)) {
                problems.add("unknown kind '" + c.kind() + "'");
                continue;
            }
            Matcher bad = FORBIDDEN.matcher(c.text());
            if (bad.find()) {
                problems.add("forbidden wording '" + bad.group() + "'");
                continue;
            }
            Double stray = firstUnknownNumber(c.text(), known);
            if (stray != null) {
                problems.add("number " + stray + " is not in the input");
                continue;
            }
            out.add(new Critique(target, kind, c.text().trim()));
            if (out.size() == 5) break;
        }
        return out;
    }

    private static List<Double> numbersIn(String text) {
        List<Double> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            try {
                out.add(Double.parseDouble(m.group().replace(",", "")));
            } catch (NumberFormatException ignored) {
                // A token that is not a number is not evidence for or against anything.
            }
        }
        return out;
    }

    /** A number in the critique with no counterpart in the input, allowing for rounding. */
    private static Double firstUnknownNumber(String text, List<Double> known) {
        for (Double x : numbersIn(text)) {
            boolean found = false;
            for (Double p : known) {
                if (Math.abs(p - x) <= Math.max(0.051, 0.005 * Math.abs(p))) {
                    found = true;
                    break;
                }
            }
            if (!found) return x;
        }
        return null;
    }
}
