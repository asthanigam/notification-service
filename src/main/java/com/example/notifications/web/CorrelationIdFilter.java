package com.example.notifications.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a correlation id on every log line for a request and echoes it back.
 *
 * <p>This is what makes the shipped logs usable rather than merely present. One
 * send produces several events - the claim, the rate-limit decision, the model
 * call with its latency and outcome, the delivery - and without a shared id they
 * are unrelatable lines from concurrent requests interleaved in one stream. With
 * it, the public log link can be filtered to a single request and read as a story.
 *
 * <p>An inbound {@code X-Correlation-Id} is honoured so the id survives a load
 * balancer or an upstream caller, but it is sanitised first: it lands in a
 * response header and in log lines, so an unvalidated inbound value is header and
 * log injection. Length-capped and character-filtered, or discarded.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlation_id";

    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = sanitise(request.getHeader(HEADER));
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled. Leaving this set would attach one
            // request's id to the next unrelated request on the same thread,
            // which is worse than having no correlation id at all - it would make
            // the logs confidently wrong.
            MDC.remove(MDC_KEY);
        }
    }

    private static String sanitise(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_LENGTH) {
            return null;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!allowed) {
                return null;
            }
        }
        return candidate;
    }
}
