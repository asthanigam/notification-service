package com.example.notifications.llm;

import com.example.notifications.domain.BodySource;

import java.util.Map;

/**
 * Turns a deterministic rendered body into the body that gets delivered.
 *
 * <p>Deliberately total: implementations never throw and never return null. A
 * personalisation failure is not an error the caller has to handle, it is a
 * {@link BodySource#FALLBACK} result carrying the deterministic body. That is what
 * makes "the LLM never blocks, drops, or duplicates a notification" a property of
 * the type rather than a promise in a comment - there is no exception path from
 * here for the send logic to get wrong.
 */
public interface Personalizer {

    /**
     * @param renderedBody  deterministic ground truth; must be returned unchanged
     *                      on any failure
     * @param usedVariables values substituted into the render, enforced verbatim in
     *                      the output by {@link PersonalizationGuard}
     */
    Result personalize(String renderedBody, Map<String, String> usedVariables);

    /**
     * @param body      what to deliver
     * @param source    whether it came from the model or the deterministic render
     * @param outcome   fine-grained result for metrics: {@code ok}, {@code timeout},
     *                  {@code http_error}, {@code guard_rejected:<reason>},
     *                  {@code disabled}
     * @param latencyMs wall-clock time spent on the model call, 0 when not called
     */
    record Result(String body, BodySource source, String outcome, long latencyMs) {

        public static Result fallback(String renderedBody, String outcome, long latencyMs) {
            return new Result(renderedBody, BodySource.FALLBACK, outcome, latencyMs);
        }

        public static Result personalized(String body, long latencyMs) {
            return new Result(body, BodySource.LLM, "ok", latencyMs);
        }

        public boolean usedFallback() {
            return source == BodySource.FALLBACK;
        }
    }
}
