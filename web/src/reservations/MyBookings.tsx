import { useCallback, useRef, useState } from "react";
import {
  ArrowRight,
  CalendarDays,
  ChevronLeft,
  ChevronRight,
  Clock3,
  DoorOpen,
  RefreshCw,
} from "lucide-react";
import type { Reservation, Room, Workspace } from "../api/contracts";
import { operationKey } from "../api/client";
import { useSession } from "../session/SessionProvider";
import {
  EmptyState,
  ErrorNotice,
  Loading,
  StatusBadge,
} from "../shared/Feedback";
import { useCooldown, useHoldSeconds, useResource } from "../shared/hooks";
import { instantDateLabel, timeLabel } from "../shared/time";
import { Modal } from "../shared/Modal";

interface Props {
  workspace: Workspace;
  rooms: Room[];
  onBook: () => void;
  onBookingChanged: (reservation: Reservation) => void;
}

function BookingDetails({
  reservation,
  roomName,
  workspace,
  busy,
  cooldown,
  onConfirm,
  onCancel,
}: {
  reservation: Reservation;
  roomName: string;
  workspace: Workspace;
  busy: boolean;
  cooldown: number;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const remaining = useHoldSeconds(
    reservation.serverNow,
    reservation.expiresAt,
  );
  const expired =
    reservation.status === "EXPIRED" ||
    (reservation.status === "HELD" && remaining === 0);
  return (
    <>
      <div className="detail-status">
        <StatusBadge status={expired ? "EXPIRED" : reservation.status} />
      </div>
      <h3 className="detail-room">{roomName}</h3>
      <p className="detail-workspace">{workspace.name}</p>
      <dl className="detail-list">
        <div>
          <dt>Date</dt>
          <dd>{instantDateLabel(reservation.startAt, workspace.timeZone)}</dd>
        </div>
        <div>
          <dt>Time</dt>
          <dd>
            {timeLabel(reservation.startAt, workspace.timeZone)} –{" "}
            {timeLabel(reservation.endAt, workspace.timeZone)}
          </dd>
        </div>
        <div>
          <dt>Time zone</dt>
          <dd>{workspace.timeZone}</dd>
        </div>
        <div>
          <dt>Created</dt>
          <dd>
            {instantDateLabel(reservation.createdAt, workspace.timeZone)},{" "}
            {timeLabel(reservation.createdAt, workspace.timeZone)}
          </dd>
        </div>
        {reservation.confirmedAt && (
          <div>
            <dt>Confirmed</dt>
            <dd>
              {instantDateLabel(reservation.confirmedAt, workspace.timeZone)},{" "}
              {timeLabel(reservation.confirmedAt, workspace.timeZone)}
            </dd>
          </div>
        )}
        <div className="booking-reference">
          <dt>Booking reference</dt>
          <dd>{reservation.id}</dd>
        </div>
      </dl>
      {reservation.status === "HELD" && (
        <p
          className={expired ? "expiry-copy" : "hold-detail-copy"}
          role="status"
        >
          {expired
            ? "Your hold has expired. Choose another available time."
            : `Your time is held for ${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, "0")}. Confirm to keep it.`}
        </p>
      )}
      <div className="modal-actions">
        {reservation.status === "HELD" && !expired && (
          <button
            className="button button-primary"
            disabled={busy || cooldown > 0}
            onClick={onConfirm}
          >
            {busy ? "Confirming…" : "Confirm booking"}
            <ArrowRight size={16} />
          </button>
        )}
        {(reservation.status === "CONFIRMED" ||
          (reservation.status === "HELD" && !expired)) && (
          <button
            className="button button-secondary"
            disabled={busy || cooldown > 0}
            onClick={onCancel}
          >
            {reservation.status === "HELD" ? "Release hold" : "Cancel booking"}
          </button>
        )}
      </div>
    </>
  );
}

export function MyBookings({
  workspace,
  rooms,
  onBook,
  onBookingChanged,
}: Props) {
  const { api } = useSession();
  const [page, setPage] = useState(0);
  const bookings = useResource(
    (signal) => api.myBookings(workspace.id, page, signal),
    [api, workspace.id, page],
  );
  const [updates, setUpdates] = useState<Record<string, Reservation>>({});
  const [detailId, setDetailId] = useState<string>();
  const [detail, setDetail] = useState<Reservation>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [cancelTarget, setCancelTarget] = useState<Reservation>();
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const cooldown = useCooldown(error);
  const keys = useRef(new Map<string, string>());
  const detailGeneration = useRef(0);
  const closeDetails = useCallback(() => {
    detailGeneration.current += 1;
    setDetailId(undefined);
    setDetail(undefined);
    setError(undefined);
  }, []);
  const closeCancel = useCallback(() => {
    setCancelTarget(undefined);
  }, []);
  const roomName = (id: string) =>
    rooms.find((room) => room.id === id)?.name ?? "Room no longer listed";
  const inspect = async (id: string) => {
    const generation = ++detailGeneration.current;
    setDetailId(id);
    setDetail(undefined);
    setError(undefined);
    setDetailLoading(true);
    try {
      const current = await api.reservation(workspace.id, id);
      if (generation !== detailGeneration.current) return;
      setDetail(current);
      setUpdates((old) => ({ ...old, [id]: current }));
    } catch (failure) {
      if (generation === detailGeneration.current) setError(failure);
    } finally {
      if (generation === detailGeneration.current) setDetailLoading(false);
    }
  };
  const cancel = async () => {
    if (!cancelTarget || busy || cooldown > 0) return;
    setBusy(true);
    setError(undefined);
    try {
      const result = await api.cancel(workspace.id, cancelTarget.id);
      setUpdates((old) => ({ ...old, [result.id]: result }));
      setDetail((current) => (current?.id === result.id ? result : current));
      setCancelTarget(undefined);
      onBookingChanged(result);
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  };
  const confirm = async () => {
    if (!detail || busy || cooldown > 0) return;
    setBusy(true);
    setError(undefined);
    const key = keys.current.get(detail.id) ?? operationKey();
    keys.current.set(detail.id, key);
    try {
      const result = await api.confirm(workspace.id, detail.id, key);
      setUpdates((old) => ({ ...old, [result.id]: result }));
      setDetail(result);
      onBookingChanged(result);
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="bookings-view" aria-label="Your bookings">
      <div className="view-toolbar">
        <div className="section-kicker">
          YOUR PLANS <span className="toolbar-dot">/</span> {workspace.name}
        </div>
        <button
          className="button button-secondary"
          onClick={() => {
            setUpdates({});
            bookings.refresh();
          }}
          disabled={bookings.loading}
        >
          <RefreshCw size={14} />
          Refresh bookings
        </button>
      </div>
      {!detailId && !cancelTarget && <ErrorNotice error={error} />}
      <ErrorNotice error={bookings.error} onRetry={bookings.refresh} />
      {bookings.loading ? (
        <Loading>Loading your bookings…</Loading>
      ) : bookings.data?.items.length === 0 ? (
        <div className="surface">
          <EmptyState
            title="A clear calendar"
            action={
              <button className="button button-primary" onClick={onBook}>
                Book a room
                <ArrowRight size={16} />
              </button>
            }
          >
            You do not have any bookings in this workspace yet. Find a room when
            you need a little space.
          </EmptyState>
        </div>
      ) : (
        <div className="booking-cards">
          {bookings.data?.items.map((item) => {
            const booking = updates[item.id] ?? item;
            const name = roomName(booking.roomId);
            const active =
              booking.status === "HELD" || booking.status === "CONFIRMED";
            return (
              <article
                key={booking.id}
                data-reservation-id={booking.id}
                className="booking-card surface"
                aria-label={`${name}, ${instantDateLabel(booking.startAt, workspace.timeZone)}, ${timeLabel(booking.startAt, workspace.timeZone)}`}
              >
                <div className="booking-card-room">
                  <span className="booking-card-icon">
                    <DoorOpen size={25} strokeWidth={1.4} aria-hidden="true" />
                  </span>
                  <div>
                    <h2>{name}</h2>
                    <span>{workspace.name}</span>
                  </div>
                  <StatusBadge status={booking.status} />
                </div>
                <div className="booking-card-meta">
                  <span>
                    <CalendarDays size={16} aria-hidden="true" />
                    {instantDateLabel(booking.startAt, workspace.timeZone)}
                  </span>
                  <span>
                    <Clock3 size={16} aria-hidden="true" />
                    {timeLabel(booking.startAt, workspace.timeZone)} –{" "}
                    {timeLabel(booking.endAt, workspace.timeZone)}
                  </span>
                </div>
                <div className="booking-card-actions">
                  <button
                    className="text-button"
                    onClick={() => {
                      void inspect(booking.id);
                    }}
                  >
                    View booking
                    <ArrowRight size={14} aria-hidden="true" />
                  </button>
                  {active && (
                    <button
                      className="text-button subtle-button"
                      disabled={busy || cooldown > 0}
                      onClick={() => {
                        setError(undefined);
                        setCancelTarget(booking);
                      }}
                    >
                      {booking.status === "HELD"
                        ? "Release hold"
                        : "Cancel booking"}
                    </button>
                  )}
                </div>
              </article>
            );
          })}
        </div>
      )}
      {bookings.data && (page > 0 || bookings.data.hasMore) && (
        <div className="pagination">
          <button
            className="button button-secondary"
            disabled={page === 0 || bookings.loading}
            onClick={() => setPage((value) => value - 1)}
          >
            <ChevronLeft size={15} />
            Previous
          </button>
          <span>Page {page + 1}</span>
          <button
            className="button button-secondary"
            disabled={!bookings.data.hasMore || bookings.loading}
            onClick={() => setPage((value) => value + 1)}
          >
            Next
            <ChevronRight size={15} />
          </button>
        </div>
      )}
      {detailId && !cancelTarget && (
        <Modal title="Booking details" onClose={closeDetails} busy={busy}>
          <ErrorNotice
            error={error}
            onRetry={
              !detail && !busy
                ? () => {
                    void inspect(detailId);
                  }
                : undefined
            }
          />
          {detailLoading ? (
            <Loading>Checking your booking…</Loading>
          ) : (
            detail && (
              <BookingDetails
                reservation={detail}
                roomName={roomName(detail.roomId)}
                workspace={workspace}
                busy={busy}
                cooldown={cooldown}
                onConfirm={() => {
                  void confirm();
                }}
                onCancel={() => {
                  setError(undefined);
                  setCancelTarget(detail);
                }}
              />
            )
          )}
        </Modal>
      )}
      {cancelTarget && (
        <Modal
          title={
            cancelTarget.status === "HELD"
              ? "Release this hold?"
              : "Cancel this booking?"
          }
          onClose={closeCancel}
          busy={busy}
        >
          <p className="modal-description">
            {roomName(cancelTarget.roomId)} ·{" "}
            {instantDateLabel(cancelTarget.startAt, workspace.timeZone)} ·{" "}
            {timeLabel(cancelTarget.startAt, workspace.timeZone)}
          </p>
          <p className="modal-description">
            {cancelTarget.status === "HELD"
              ? "This time will become available to everyone in the workspace."
              : "Your room will become available again. This booking still counts towards your daily allowance."}
          </p>
          <ErrorNotice error={error} />
          <div className="modal-actions">
            <button
              className="button button-secondary"
              disabled={busy}
              onClick={closeCancel}
            >
              Keep {cancelTarget.status === "HELD" ? "hold" : "booking"}
            </button>
            <button
              className="button button-danger"
              disabled={busy || cooldown > 0}
              onClick={() => {
                void cancel();
              }}
            >
              {busy
                ? "Cancelling…"
                : cancelTarget.status === "HELD"
                  ? "Release hold"
                  : "Cancel booking"}
            </button>
          </div>
        </Modal>
      )}
    </section>
  );
}
