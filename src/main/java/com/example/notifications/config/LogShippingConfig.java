package com.example.notifications.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Attaches {@link LogShipper} to the root logger when a destination is configured.
 *
 * <p>Wired programmatically rather than in a {@code logback-spring.xml}, on
 * purpose: Spring Boot's structured console logging is enabled by a property, and
 * introducing a logback XML file to add one appender would mean taking over the
 * console configuration too and re-implementing what the property already does.
 * This way stdout stays exactly as Boot configures it and shipping is purely
 * additive.
 *
 * <p>No destination configured means no appender is attached at all - not an
 * appender that silently does nothing. A fresh clone with an empty environment
 * therefore has no background thread and no network calls, which is what makes
 * the local and CI runs identical to production minus one output.
 */
@Configuration
public class LogShippingConfig {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(LogShippingConfig.class);

    private final String endpoint;
    private final String token;
    private final String serviceName;

    private LogShipper shipper;

    public LogShippingConfig(
            @Value("${app.logging.ship-url:}") String endpoint,
            @Value("${app.logging.ship-token:}") String token,
            @Value("${spring.application.name:notification-service}") String serviceName) {
        this.endpoint = endpoint;
        this.token = token;
        this.serviceName = serviceName;
    }

    @PostConstruct
    void attach() {
        if (endpoint == null || endpoint.isBlank() || token == null || token.isBlank()) {
            log.info("Log shipping disabled (no app.logging.ship-url / ship-token). "
                    + "Structured logs still go to stdout.");
            return;
        }

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        shipper = new LogShipper(endpoint, token, serviceName);
        shipper.setContext(context);
        shipper.setName("log-shipper");
        shipper.start();

        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(shipper);

        // The endpoint host is logged, never the token.
        log.info("Log shipping enabled endpoint_host={}", hostOf(endpoint));
    }

    @PreDestroy
    void detach() {
        if (shipper != null) {
            shipper.stop();
        }
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (RuntimeException e) {
            return "unparseable";
        }
    }
}
