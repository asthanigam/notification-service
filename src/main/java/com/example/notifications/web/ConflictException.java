package com.example.notifications.web;

/** Same idempotency key, different request body. Surfaces as 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
