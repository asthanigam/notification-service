package com.example.notifications.config;

import com.example.notifications.llm.DeterministicPersonalizer;
import com.example.notifications.llm.GroqPersonalizer;
import com.example.notifications.llm.PersonalizationGuard;
import com.example.notifications.llm.Personalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the framework-free classes into the container. */
@Configuration
public class ServiceConfig {

    private static final Logger log = LoggerFactory.getLogger(ServiceConfig.class);

    /**
     * Injected everywhere instead of calling Instant.now(), so rate-limit windows
     * can be tested by moving the clock rather than sleeping through a real minute.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Selects the personaliser at startup rather than branching per request.
     *
     * <p>No key configured means the deterministic implementation - the service
     * runs and every test passes with an empty environment. Logged at startup
     * because "why are all my messages unpersonalised" should be answerable from
     * the first ten lines of the log rather than by reading this file.
     */
    @Bean
    public Personalizer personalizer(ObjectMapper objectMapper, PersonalizationGuard guard,
                                     AppProperties properties) {
        AppProperties.Llm llm = properties.llm();
        if (!llm.enabled()) {
            log.warn("LLM personalisation disabled (no app.llm.api-key configured); "
                    + "every notification will be delivered from the deterministic template. "
                    + "Set LLM_API_KEY to enable it.");
            return new DeterministicPersonalizer();
        }
        log.info("LLM personalisation enabled model={} request_timeout={}",
                llm.model(), llm.requestTimeout());
        return new GroqPersonalizer(objectMapper, guard, llm);
    }
}
