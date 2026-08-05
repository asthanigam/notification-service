package com.example.notifications.domain;

import java.time.Instant;

/** Current rate-limit position for a recipient, as reported by the API. */
public record RateLimitState(int used, int limit, int remaining, Instant windowReset) {
}
