import { useEffect, useRef, useState } from "react";
import {
  ArrowRight,
  CalendarDays,
  Check,
  Clock3,
  DoorOpen,
  Info,
  Timer,
} from "lucide-react";
import type { Reservation, Room, Slot, Workspace } from "../api/contracts";
import { ErrorNotice, StatusBadge } from "../shared/Feedback";
import { useHoldSeconds } from "../shared/hooks";
import { instantDateLabel, timeLabel } from "../shared/time";
import { Modal } from "../shared/Modal";

interface Props {
  workspace: Workspace;
  selectedRoom?: Room;
  selectedSlot?: Slot;
  reservation?: Reservation;
  reservationRoom?: Room;
  busy: boolean;
  cooldown: number;
  error: unknown;
  onHold: () => void;
  onConfirm: () => void;
  onCancel: () => void;
  onExpired: () => void;
  onStartAgain: () => void;
}

export function ReservationSummary({
  workspace,
  selectedRoom,
  selectedSlot,
  reservation,
  reservationRoom,
  busy,
  cooldown,
  error,
  onHold,
  onConfirm,
  onCancel,
  onExpired,
  onStartAgain,
}: Props) {
  const remaining = useHoldSeconds(
    reservation?.serverNow ?? "",
    reservation?.expiresAt ?? null,
  );
  const announced = useRef("");
  const [cancelOpen, setCancelOpen] = useState(false);
  const expired =
    reservation?.status === "EXPIRED" ||
    (reservation?.status === "HELD" && remaining === 0);
  useEffect(() => {
    if (expired && reservation && announced.current !== reservation.id) {
      announced.current = reservation.id;
      onExpired();
    }
  }, [expired, reservation, onExpired]);
  const room = reservation ? reservationRoom : selectedRoom;
  const time = reservation ?? selectedSlot;
  const isActiveHold = reservation?.status === "HELD" && !expired;
  const confirmed = reservation?.status === "CONFIRMED";
  const blocked = busy || cooldown > 0;
  return (
    <aside
      className={`reservation-summary surface ${isActiveHold ? "has-hold" : ""}`}
      aria-label="Reservation summary"
    >
      <div className="panel-topline">
        <span className="step-label">03 / YOUR BOOKING</span>
        {reservation && (
          <StatusBadge status={expired ? "EXPIRED" : reservation.status} />
        )}
      </div>
      <h2>
        {confirmed
          ? "All yours."
          : isActiveHold
            ? "A moment to confirm."
            : expired
              ? "Time to try again."
              : "Your next booking."}
      </h2>
      {time && room ? (
        <>
          <div className="summary-room-block">
            <span className="summary-room-icon" aria-hidden="true">
              <DoorOpen size={27} strokeWidth={1.4} />
            </span>
            <div>
              <strong className="summary-room">{room.name}</strong>
              <span>{workspace.name}</span>
            </div>
          </div>
          <dl className="summary-details">
            <div>
              <dt>
                <CalendarDays size={15} aria-hidden="true" />
                Date
              </dt>
              <dd>{instantDateLabel(time.startAt, workspace.timeZone)}</dd>
            </div>
            <div>
              <dt>
                <Clock3 size={15} aria-hidden="true" />
                Time
              </dt>
              <dd>
                {timeLabel(time.startAt, workspace.timeZone)} –{" "}
                {timeLabel(time.endAt, workspace.timeZone)}
              </dd>
            </div>
            <div>
              <dt>Duration</dt>
              <dd>30 minutes</dd>
            </div>
          </dl>
          <p className="summary-timezone">{workspace.timeZone}</p>
          {isActiveHold && (
            <div
              className={`hold-countdown ${remaining <= 30 ? "is-urgent" : ""}`}
            >
              <Timer size={20} aria-hidden="true" />
              <div>
                <span>Held for you</span>
                <strong aria-label={`${remaining} seconds remaining`}>
                  {Math.floor(remaining / 60)}:
                  {String(remaining % 60).padStart(2, "0")}
                  <small> remaining</small>
                </strong>
              </div>
            </div>
          )}
          {confirmed && (
            <div className="success-note" role="status">
              <Check size={17} aria-hidden="true" />
              <p>Your room is booked.</p>
            </div>
          )}
          {expired && (
            <div className="expiry-note" role="status">
              <Timer size={17} aria-hidden="true" />
              <p>
                Your hold has expired. Choose an available time to start again.
              </p>
            </div>
          )}
          {reservation?.status === "CANCELLED" && (
            <div className="session-notice" role="status">
              Your booking has been cancelled.
            </div>
          )}
          <ErrorNotice error={error} />
          {!reservation && (
            <>
              <p className="summary-action-hint">
                Place a hold to keep this time for up to two minutes while you
                confirm.
              </p>
              <button
                className="button button-primary full-width"
                disabled={blocked}
                onClick={onHold}
              >
                {busy
                  ? "Placing your hold…"
                  : cooldown > 0
                    ? "Hold this time"
                    : "Hold this time"}
                <ArrowRight size={16} aria-hidden="true" />
              </button>
            </>
          )}
          {isActiveHold && (
            <>
              <p className="summary-action-hint">
                Confirm before the timer ends to keep your room.
              </p>
              <button
                className="button button-primary full-width"
                disabled={blocked}
                onClick={onConfirm}
              >
                {busy ? "Saving your booking…" : "Confirm booking"}
                <ArrowRight size={16} aria-hidden="true" />
              </button>
              <button
                className="text-button summary-secondary"
                onClick={() => setCancelOpen(true)}
                disabled={blocked}
              >
                Release this hold
              </button>
            </>
          )}
          {confirmed && (
            <>
              <button
                className="button button-secondary full-width"
                onClick={onStartAgain}
                disabled={busy}
              >
                Book another time
                <ArrowRight size={16} aria-hidden="true" />
              </button>
              <button
                className="text-button summary-secondary"
                onClick={() => setCancelOpen(true)}
                disabled={blocked}
              >
                Cancel booking
              </button>
            </>
          )}
          {(expired || reservation?.status === "CANCELLED") && (
            <button
              className="button button-primary full-width"
              onClick={onStartAgain}
              disabled={busy}
            >
              Choose another time
              <ArrowRight size={16} aria-hidden="true" />
            </button>
          )}
          {cooldown > 0 && (
            <p className="cooldown-note" role="status">
              You can try again in {cooldown}s.
            </p>
          )}
        </>
      ) : (
        <>
          <div className="empty-summary-illustration" aria-hidden="true">
            <div className="empty-summary-calendar">
              <CalendarDays size={34} strokeWidth={1} />
              <span />
              <span />
              <span />
            </div>
            <span className="empty-summary-plus">+</span>
          </div>
          <div className="summary-empty-copy">
            <h3>A little space in your day.</h3>
            <p>
              Select an available time and your booking details will appear
              here.
            </p>
          </div>
          <ErrorNotice error={error} />
        </>
      )}
      <div className="booking-policy">
        <Info size={15} aria-hidden="true" />
        <p>
          Up to 2 active holds and 4 confirmed bookings per day. Cancelling a
          booking does not reset the daily allowance.
        </p>
      </div>
      {cancelOpen && (
        <Modal
          title={isActiveHold ? "Release this hold?" : "Cancel this booking?"}
          busy={busy}
          onClose={() => setCancelOpen(false)}
        >
          <p className="modal-description">
            {isActiveHold
              ? "This time will become available to everyone in the workspace."
              : "Your room will become available again. This booking still counts towards your daily allowance."}
          </p>
          <div className="modal-actions">
            <button
              className="button button-secondary"
              disabled={busy}
              onClick={() => setCancelOpen(false)}
            >
              Keep {isActiveHold ? "hold" : "booking"}
            </button>
            <button
              className="button button-danger"
              disabled={blocked}
              onClick={() => {
                onCancel();
                setCancelOpen(false);
              }}
            >
              {isActiveHold ? "Release hold" : "Cancel booking"}
            </button>
          </div>
        </Modal>
      )}
    </aside>
  );
}
