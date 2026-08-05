package com.example.notifications.web;

/** Surfaces as 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
