package com.example.notifications.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * @param variables caller-supplied substitution values. This is the untrusted
 *                  input in the whole design: it reaches the model, so it is the
 *                  prompt-injection surface. It is bounded here (count and length)
 *                  and neutralised downstream by output validation in
 *                  PersonalizationGuard.
 */
public record SendRequest(
        @NotBlank(message = "recipient_id is required")
        @Size(max = 64, message = "recipient_id must be at most 64 characters")
        @JsonProperty("recipient_id") String recipientId,

        @NotBlank(message = "template is required")
        @Size(max = 64, message = "template must be at most 64 characters")
        String template,

        Map<String, String> variables,

        @NotBlank(message = "idempotency_key is required")
        @Size(max = 128, message = "idempotency_key must be at most 128 characters")
        @JsonProperty("idempotency_key") String idempotencyKey) {

    /** Hard ceilings so one request cannot blow up the prompt or the row. */
    private static final int MAX_VARIABLES = 25;
    private static final int MAX_VALUE_LENGTH = 500;

    public Map<String, String> safeVariables() {
        Map<String, String> vars = variables == null ? Map.of() : variables;
        if (vars.size() > MAX_VARIABLES) {
            throw new IllegalArgumentException(
                    "at most " + MAX_VARIABLES + " variables are allowed");
        }
        vars.forEach((k, v) -> {
            if (v != null && v.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "variable '" + k + "' exceeds " + MAX_VALUE_LENGTH + " characters");
            }
        });
        return vars;
    }
}
