package com.example.notifications.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A notification request and whatever became of it.
 *
 * <p>Immutable record rather than a mutable entity: every state change goes
 * through a named, atomic SQL statement in {@link
 * com.example.notifications.service.NotificationStore}, so there is nowhere for a
 * setter to be called from and no dirty-checking to reason about.
 *
 * @param status  see {@link NotificationStatus}. {@code PENDING} is a reservation
 *                held while the LLM call is in flight, not a state a caller asked
 *                for.
 * @param attempts personalisation attempts. A replay never increments it - that is
 *                 what the idempotency key buys.
 */
public record Notification(
        UUID id,
        String recipientId,
        String template,
        Map<String, String> variables,
        String idempotencyKey,
        String requestFingerprint,
        NotificationStatus status,
        String personalizedBody,
        BodySource bodySource,
        int attempts,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public boolean isTerminal() {
        return status != NotificationStatus.PENDING;
    }
}
