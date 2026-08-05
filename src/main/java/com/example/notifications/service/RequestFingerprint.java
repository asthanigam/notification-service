package com.example.notifications.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * SHA-256 over the canonical form of a request.
 *
 * <p>Exists to tell an idempotent replay apart from a genuine conflict. Same key
 * plus the same fingerprint is a retry to be replayed; same key plus a different
 * fingerprint is a caller reusing a key for a different message, which is a 409.
 *
 * <p>Canonicalisation sorts the variable keys, so two JSON documents that differ
 * only in field order hash the same and a client's serialiser cannot accidentally
 * manufacture a conflict. Values are length-prefixed rather than concatenated with
 * a separator, so {@code {a: "x|y"}} and {@code {a: "x", b: "y"}} cannot collide
 * into the same digest.
 */
@Component
public class RequestFingerprint {

    public String of(String recipientId, String template, Map<String, String> variables) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, recipientId);
        append(canonical, template);
        for (Map.Entry<String, String> e : new TreeMap<>(variables).entrySet()) {
            append(canonical, e.getKey());
            append(canonical, e.getValue());
        }
        return sha256Hex(canonical.toString());
    }

    private static void append(StringBuilder sb, String value) {
        String v = value == null ? "" : value;
        sb.append(v.length()).append(':').append(v).append('\n');
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
