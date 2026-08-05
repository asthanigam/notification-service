package com.example.notifications.domain;

/**
 * Where the delivered body actually came from.
 *
 * <p>Persisted per notification rather than only emitted as a log line, so the
 * fallback rate is answerable with a SQL query during an incident - when the log
 * pipeline is one of the things most likely to be degraded too.
 */
public enum BodySource {
    /** The model's rewrite, and it passed every output check. */
    LLM,
    /** Deterministic template render: the LLM was slow, failed, or its output was rejected. */
    FALLBACK
}
