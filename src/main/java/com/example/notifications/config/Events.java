package com.example.notifications.config;

import org.slf4j.Logger;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits an application event as structured fields rather than as a sentence.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code log.info("notification_sent id={} latency={}", id, ms)} produces a
 * JSON record whose entire payload is one {@code message} string. In a hosted log
 * backend that is grep with extra steps: you cannot filter to
 * {@code event:llm_call AND fallback_taken:true}, you cannot chart p99 of
 * {@code llm_latency_ms}, and every dashboard becomes a regex.
 *
 * <p>Spring Boot's structured (ECS) logging promotes MDC entries to top-level JSON
 * fields. So this puts each field in the MDC for the duration of one log call and
 * removes it immediately after - leaving the correlation id, which the filter owns
 * for the whole request. The result is a record with real fields:
 *
 * <pre>
 * {"@timestamp":"...", "message":"notification_sent", "event":"notification_sent",
 *  "correlation_id":"8983d142-...", "notification_id":"0b6b8866-...",
 *  "recipient_id":"user-42", "body_source":"LLM", "llm_latency_ms":"412"}
 * </pre>
 *
 * <p>The {@code finally} block is load-bearing. Servlet threads are pooled, so a
 * key left behind would attach one request's notification id to an unrelated
 * later request - logs that are confidently wrong, which is worse than logs that
 * are merely thin.
 *
 * <h2>What never goes in here</h2>
 *
 * <p>No {@code variables} and no {@code personalized_body}. Those carry
 * caller-supplied personal data - names, amounts, order numbers - and the whole
 * point of shipping logs to a third-party backend is that they leave your
 * infrastructure. Identifiers and outcomes are enough to debug with; the message
 * body is available from the database to someone who is authorised to read it.
 */
public final class Events {

    private Events() {
    }

    public static Builder of(String event) {
        return new Builder(event);
    }

    public static final class Builder {
        private final String event;
        private final Map<String, String> fields = new LinkedHashMap<>();

        private Builder(String event) {
            this.event = event;
            fields.put("event", event);
        }

        public Builder with(String key, Object value) {
            if (value != null) {
                fields.put(key, String.valueOf(value));
            }
            return this;
        }

        public void info(Logger log) {
            emit(log, false);
        }

        public void warn(Logger log) {
            emit(log, true);
        }

        private void emit(Logger log, boolean warn) {
            fields.forEach(MDC::put);
            try {
                // The message is the event name alone: everything else is a field,
                // so there is nothing to parse out of the string.
                if (warn) {
                    log.warn(event);
                } else {
                    log.info(event);
                }
            } finally {
                // Only the keys this call added, never the whole MDC - the
                // correlation id belongs to the request, not to this event.
                fields.keySet().forEach(MDC::remove);
            }
        }
    }
}
