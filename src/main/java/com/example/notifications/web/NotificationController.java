package com.example.notifications.web;

import com.example.notifications.domain.Notification;
import com.example.notifications.domain.RateLimitState;
import com.example.notifications.service.NotificationService;
import com.example.notifications.service.RateLimiter;
import com.example.notifications.web.dto.NotificationView;
import com.example.notifications.web.dto.RecipientHistoryResponse;
import com.example.notifications.web.dto.SendRequest;
import com.example.notifications.web.dto.SendResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The notification API. */
@RestController
public class NotificationController {

    private static final int MAX_HISTORY = 50;

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /**
     * Sends a notification, or replays the outcome of an earlier identical send.
     *
     * <p>Status codes carry the disposition so a client can branch without parsing
     * the body: <b>201</b> a send happened now, <b>200</b> this is a replay of an
     * earlier send, <b>429</b> the recipient is over their limit, <b>409</b> the
     * idempotency key was already used for a different request.
     *
     * <p>200-for-replay rather than 201 is the point of the endpoint: the second
     * caller must be able to tell that it did not cause a send, and must get the
     * original body back rather than a fresh one.
     */
    @PostMapping(path = "/notifications",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SendResponse> send(@Valid @RequestBody SendRequest request) {
        NotificationService.SendOutcome outcome = service.send(
                request.recipientId(), request.template(),
                request.safeVariables(), request.idempotencyKey());

        SendResponse body = SendResponse.of(outcome);
        HttpStatus status = switch (outcome.disposition()) {
            case SENT -> HttpStatus.CREATED;
            case DEDUPED -> HttpStatus.OK;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
        };

        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        RateLimiter.Decision limit = outcome.rateLimit();
        // Standard-shaped hints so a client can back off without guessing.
        response.header("X-RateLimit-Limit", String.valueOf(limit.limit()));
        response.header("X-RateLimit-Remaining", String.valueOf(limit.remaining()));
        response.header("X-RateLimit-Reset", String.valueOf(limit.windowReset().getEpochSecond()));
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            long retryAfter = Math.max(1,
                    limit.windowReset().getEpochSecond() - System.currentTimeMillis() / 1000);
            response.header("Retry-After", String.valueOf(retryAfter));
        }
        return response.body(body);
    }

    @GetMapping(path = "/notifications/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public NotificationView byId(@PathVariable UUID id) {
        Notification notification = service.findById(id)
                .orElseThrow(() -> new NotFoundException("no notification with id " + id));
        return NotificationView.of(notification);
    }

    /** Recent notifications for a recipient, plus where they stand against the limit. */
    @GetMapping(path = "/recipients/{recipientId}/notifications",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RecipientHistoryResponse forRecipient(@PathVariable String recipientId,
                                                 @RequestParam(defaultValue = "20") int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_HISTORY);
        List<Notification> recent = service.recentFor(recipientId, capped);
        RateLimiter.Decision decision = service.rateLimitStateFor(recipientId);

        return new RecipientHistoryResponse(
                recipientId,
                recent.stream().map(NotificationView::of).toList(),
                new RateLimitState(decision.used(), decision.limit(),
                        decision.remaining(), decision.windowReset()));
    }
}
