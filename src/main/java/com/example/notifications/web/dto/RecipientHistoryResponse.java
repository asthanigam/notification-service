package com.example.notifications.web.dto;

import com.example.notifications.domain.RateLimitState;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record RecipientHistoryResponse(
        @JsonProperty("recipient_id") String recipientId,
        List<NotificationView> notifications,
        @JsonProperty("rate_limit") RateLimitState rateLimit) {
}
