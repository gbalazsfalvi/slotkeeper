-- A wall clock may move backwards. Booking observations must not undo an earlier expiry decision.
CREATE TABLE decision_clock (
 id integer PRIMARY KEY CHECK(id=1),
 last_observed timestamptz NOT NULL CHECK(isfinite(last_observed))
);

-- Preserve existing committed history when upgrading an already populated local database.
-- Slot starts and hold deadlines are intentionally future values and are NOT clock observations.
INSERT INTO decision_clock(id,last_observed)
SELECT 1, greatest(
 clock_timestamp(),
 (SELECT max(created_at) FROM reservations),
 (SELECT max(confirmed_at) FROM reservations),
 (SELECT max(occurred_at) FROM booking_events)
);

CREATE FUNCTION enforce_decision_clock_monotonicity() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN
 IF NEW.last_observed < OLD.last_observed THEN
  RAISE EXCEPTION 'Decision time cannot move backwards' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$body$;
CREATE TRIGGER decision_clock_never_regresses
 BEFORE UPDATE OF last_observed ON decision_clock
 FOR EACH ROW EXECUTE FUNCTION enforce_decision_clock_monotonicity();

GRANT SELECT ON decision_clock TO slotkeeper_app;
GRANT UPDATE(last_observed) ON decision_clock TO slotkeeper_app;
