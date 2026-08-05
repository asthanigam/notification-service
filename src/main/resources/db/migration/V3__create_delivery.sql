-- "Sending" in this exercise means persist + log. This is the persist half: an
-- append-only record that a delivery actually happened.
--
-- It is a separate table from notification rather than a column on it, because the
-- two answer different questions. notification.status answers "what is the state
-- of this request"; delivery answers "how many times did we actually put a message
-- on the wire". Keeping them apart is what makes the dedup gate checkable by
-- counting rows: fire the same idempotency key fifty times and this table must
-- contain exactly one row, no matter what happened to the fifty requests.
--
-- Nothing ever updates a row here. The unique index below is a second, independent
-- guard on the invariant - even if the application logic were wrong, the database
-- would refuse a duplicate delivery for a notification.
CREATE TABLE delivery (
    id              BIGSERIAL   PRIMARY KEY,
    notification_id UUID        NOT NULL REFERENCES notification (id) ON DELETE CASCADE,
    channel         VARCHAR(16) NOT NULL,
    delivered_at    TIMESTAMPTZ NOT NULL
);

-- One delivery per notification, enforced by the database rather than trusted from
-- the service layer. This is the assertion the concurrency test reads.
CREATE UNIQUE INDEX ux_delivery_notification ON delivery (notification_id);

COMMENT ON TABLE delivery IS
    'Append-only proof of send; unique on notification_id so a double-send is impossible by construction';
