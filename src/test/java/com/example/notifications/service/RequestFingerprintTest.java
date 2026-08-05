package com.example.notifications.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    private final RequestFingerprint fingerprint = new RequestFingerprint();

    @Test
    void isStableAcrossVariableOrdering() {
        // A client's serialiser must not be able to manufacture a 409 just by
        // emitting JSON fields in a different order.
        Map<String, String> a = new LinkedHashMap<>();
        a.put("name", "Aastha");
        a.put("amount", "₹10.00");

        Map<String, String> b = new LinkedHashMap<>();
        b.put("amount", "₹10.00");
        b.put("name", "Aastha");

        assertThat(fingerprint.of("r1", "welcome", a))
                .isEqualTo(fingerprint.of("r1", "welcome", b));
    }

    @Test
    void differsWhenAnyPartOfTheRequestDiffers() {
        Map<String, String> vars = Map.of("name", "Aastha");
        String base = fingerprint.of("r1", "welcome", vars);

        assertThat(fingerprint.of("r2", "welcome", vars)).isNotEqualTo(base);
        assertThat(fingerprint.of("r1", "order_shipped", vars)).isNotEqualTo(base);
        assertThat(fingerprint.of("r1", "welcome", Map.of("name", "Someone"))).isNotEqualTo(base);
    }

    @Test
    void cannotBeCollidedByMovingASeparatorIntoAValue() {
        // Length-prefixing rather than joining with a delimiter: {a:"x", b:"y"}
        // and {a:"x|y"} must not hash the same.
        assertThat(fingerprint.of("r", "t", Map.of("a", "x", "b", "y")))
                .isNotEqualTo(fingerprint.of("r", "t", Map.of("a", "x|y")));
    }

    @Test
    void isAHexSha256() {
        assertThat(fingerprint.of("r", "welcome", Map.of("name", "A")))
                .hasSize(64).matches("[0-9a-f]{64}");
    }
}
