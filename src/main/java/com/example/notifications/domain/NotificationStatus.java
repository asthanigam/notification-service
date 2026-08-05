package com.example.notifications.domain;

/**
 * Lifecycle of a notification.
 *
 * <p>{@code PENDING} exists so that the idempotency key can be claimed in the
 * database <em>before</em> the slow, failable personalisation step runs. A row in
 * this state means "somebody won the race for this key and is working on it",
 * which is exactly what a concurrent caller with the same key needs to know.
 *
 * <p>{@code RATE_LIMITED} is deliberately a terminal, recorded outcome rather than
 * a rejection that leaves no trace. Two reasons: replaying the same idempotency key
 * must return the same answer it returned the first time, and a rate-limited
 * attempt is exactly the kind of thing an operator wants to see in a recipient's
 * history when asking why a message never arrived.
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    RATE_LIMITED,
    FAILED
}
