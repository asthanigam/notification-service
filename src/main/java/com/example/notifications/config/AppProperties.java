package com.example.notifications.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Everything an operator can change without a rebuild, bound from the environment.
 *
 * <p>12-factor: no secret has a real default. {@code LLM_API_KEY} defaults to
 * empty, which selects the deterministic personaliser rather than failing to
 * start - the service is fully functional with no credentials, it just does not
 * call a model.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(RateLimit rateLimit, Llm llm) {

    /**
     * @param perRecipient sends admitted per window per recipient
     * @param window       fixed window length
     */
    public record RateLimit(int perRecipient, Duration window) {
    }

    /**
     * @param apiKey         empty selects {@link
     *                       com.example.notifications.llm.DeterministicPersonalizer}
     * @param requestTimeout hard ceiling on the whole model exchange. Deliberately
     *                       short: this sits in a request a caller is waiting on,
     *                       and the fallback is a correct message, so waiting
     *                       longer trades certain latency for a nicer rewrite.
     * @param maxTokens      caps generation server-side so a runaway response
     *                       cannot consume the entire timeout budget
     */
    public record Llm(String apiKey, String baseUrl, String model,
                      Duration connectTimeout, Duration requestTimeout, int maxTokens) {

        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
