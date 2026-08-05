package com.example.notifications.web.dto;

import com.example.notifications.domain.Notification;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** A notification as the API reports it. */
public record NotificationView(
        UUID id,
        String status,
        @JsonProperty("personalized_body") String personalizedBody,
        @JsonProperty("body_source") String bodySource,
        int attempts,
        String template,
        @JsonProperty("recipient_id") String recipientId,
        @JsonProperty("failure_reason") String failureReason,
        @JsonProperty("created_at") Instant createdAt) {

    public static NotificationView of(Notification n) {
        return new NotificationView(
                n.id(), n.status().name(), n.personalizedBody(),
                n.bodySource() == null ? null : n.bodySource().name(),
                n.attempts(), n.template(), n.recipientId(),
                n.failureReason(), n.createdAt());
    }
}
