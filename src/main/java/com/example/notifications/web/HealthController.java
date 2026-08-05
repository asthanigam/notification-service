package com.example.notifications.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness and readiness, as separate endpoints with genuinely different meanings.
 *
 * <p>The distinction matters to an orchestrator and is routinely got wrong by
 * making both check the database. Liveness answers "is this process healthy enough
 * to keep, or should it be killed and replaced"; readiness answers "should traffic
 * be sent here right now". If liveness checked the database, a brief database
 * blip would fail liveness, the platform would kill every healthy replica, and a
 * recoverable dependency outage would become a full restart storm.
 *
 * <p>So: {@code /healthz} is a pure process check and never touches a dependency.
 * {@code /readyz} validates the connection pool, because a replica that cannot
 * reach Postgres cannot serve a send and should be taken out of rotation until it
 * can.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping(path = "/healthz", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> live() {
        return Map.of("status", "ok");
    }

    @GetMapping(path = "/readyz", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> ready() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "ok", "database", "ok"));
        } catch (RuntimeException e) {
            // 503 rather than an exception: this endpoint's job is to report a
            // verdict, and a stack trace in the readiness path is noise on every
            // probe interval during an outage.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "unavailable", "database", "unreachable"));
        }
    }
}
