package com.example.notifications.web.dto;

import com.example.notifications.service.NotificationService;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * @param deduped     true when this response replays an earlier send rather than
 *                    causing one. Surfaced explicitly as well as via the 200/201
 *                    split so a client reading only JSON can still tell.
 * @param rateLimited true when the recipient was over their limit
 */
public record SendResponse(
        UUID id,
        String status,
        String body,
        @JsonProperty("body_source") String bodySource,
        boolean deduped,
        @JsonProperty("rate_limited") boolean rateLimited,
        int attempts) {

    public static SendResponse of(NotificationService.SendOutcome outcome) {
        var n = outcome.notification();
        return new SendResponse(
                n.id(),
                n.status().name(),
                n.personalizedBody(),
                n.bodySource() == null ? null : n.bodySource().name(),
                outcome.disposition() == NotificationService.Disposition.DEDUPED,
                outcome.disposition() == NotificationService.Disposition.RATE_LIMITED,
                n.attempts());
    }
}
