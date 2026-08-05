-- The notification table doubles as the idempotency ledger. There is no separate
-- "idempotency_key" table on purpose: a key and the outcome it names have exactly
-- the same lifetime, and splitting them would mean a two-table write to reserve a
-- key and a second one to record what happened to it, with a window in between
-- where a crash leaves a reserved key pointing at nothing.
CREATE TABLE notification (
    id                UUID         PRIMARY KEY,

    recipient_id      VARCHAR(64)  NOT NULL,
    template          VARCHAR(64)  NOT NULL,
    -- Caller-supplied substitution values. JSONB rather than TEXT so the shape is
    -- validated on write and can be queried later; never logged (see LoggingKeys).
    variables         JSONB        NOT NULL,

    idempotency_key   VARCHAR(128) NOT NULL,
    -- SHA-256 over the canonical (recipient, template, variables) triple. Lets a
    -- replay be told apart from a genuine conflict: same key + same fingerprint is
    -- a retry to be replayed, same key + different fingerprint is a 409. Storing
    -- the digest rather than comparing the JSON keeps the check fixed-width and
    -- independent of key ordering in the submitted document.
    request_fingerprint CHAR(64)   NOT NULL,

    -- PENDING is a reservation, not a state anybody asked for: the row is inserted
    -- before the LLM is called so that concurrent callers with the same key are
    -- excluded by the unique index rather than by a lock. Terminal states are
    -- SENT, RATE_LIMITED and FAILED.
    status            VARCHAR(16)  NOT NULL,

    personalized_body TEXT,
    -- LLM or FALLBACK. Recorded per row rather than inferred from a log line so
    -- the fallback rate is answerable from the database during an incident, when
    -- the log pipeline is the thing most likely to also be broken.
    body_source       VARCHAR(16),

    -- Personalisation attempts for this notification. A replay does not increment
    -- it; that is the point of the idempotency key.
    attempts          INT          NOT NULL DEFAULT 0,

    failure_reason    VARCHAR(256),

    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL
);

-- The whole dedup guarantee rests on this one index. Two concurrent requests with
-- the same key race to INSERT; Postgres admits exactly one and the loser takes the
-- replay path. No application-level check-then-act, so there is no window to lose.
CREATE UNIQUE INDEX ux_notification_idempotency_key ON notification (idempotency_key);

-- GET /recipients/{id}/notifications reads the most recent first.
CREATE INDEX ix_notification_recipient_created ON notification (recipient_id, created_at DESC);

COMMENT ON COLUMN notification.request_fingerprint IS
    'SHA-256 of the canonical request; distinguishes an idempotent replay from a 409 conflict';
COMMENT ON COLUMN notification.status IS
    'PENDING (reserved, in flight) | SENT | RATE_LIMITED | FAILED';
