import { useCallback, useEffect, useRef, useState } from "react";
import {
  CalendarDays,
  Check,
  ChevronLeft,
  ChevronRight,
  DoorOpen,
  Plus,
  RefreshCw,
} from "lucide-react";
import type { Reservation, Room, Slot, Workspace } from "../api/contracts";
import { ApiError, operationKey } from "../api/client";
import { useSession } from "../session/SessionProvider";
import { EmptyState, ErrorNotice, Loading } from "../shared/Feedback";
import { useCooldown, useResource } from "../shared/hooks";
import {
  addDays,
  dateLabel,
  timeLabel,
  timeZoneLabel,
  workspaceDate,
} from "../shared/time";
import { ReservationSummary } from "./ReservationSummary";

interface Props {
  workspace: Workspace;
  rooms: Room[];
  serverNow: string;
  latestReservation?: Reservation;
  onBookingChanged: (reservation: Reservation) => void;
  onManageRooms?: () => void;
}

export function AvailabilityView({
  workspace,
  rooms,
  serverNow,
  latestReservation,
  onBookingChanged,
  onManageRooms,
}: Props) {
  const { api } = useSession();
  const today = workspaceDate(serverNow, workspace.timeZone);
  const [date, setDate] = useState(today);
  const [roomId, setRoomId] = useState(
    rooms.find((room) => room.enabled)?.id ?? "",
  );
  const [slot, setSlot] = useState<Slot>();
  const [reservation, setReservation] = useState<Reservation>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const [availabilityVersion, setAvailabilityVersion] = useState(0);
  const holdKey = useRef(operationKey());
  const confirmKeys = useRef(new Map<string, string>());
  const room = rooms.find((item) => item.id === roomId);
  const cooldown = useCooldown(error);
  const availability = useResource(
    async (signal) =>
      roomId ? api.availability(workspace.id, roomId, date, signal) : undefined,
    [api, workspace.id, roomId, date, availabilityVersion, latestReservation],
  );
  const refreshAvailability = useCallback(
    () => setAvailabilityVersion((value) => value + 1),
    [],
  );

  useEffect(() => {
    if (latestReservation)
      setReservation((current) =>
        current?.id === latestReservation.id ? latestReservation : current,
      );
  }, [latestReservation]);

  useEffect(() => {
    if (roomId && !rooms.some((item) => item.id === roomId && item.enabled)) {
      setRoomId(rooms.find((item) => item.enabled)?.id ?? "");
      setSlot(undefined);
    }
  }, [rooms, roomId]);

  const clearSelectionError = () =>
    setError((current: unknown) =>
      current instanceof ApiError && current.status === 429 && cooldown > 0
        ? current
        : undefined,
    );
  const chooseDate = (next: string) => {
    setDate(next);
    setSlot(undefined);
    clearSelectionError();
  };
  const chooseRoom = (next: string) => {
    setRoomId(next);
    setSlot(undefined);
    clearSelectionError();
  };
  const chooseSlot = (next: Slot) => {
    setSlot(next);
    clearSelectionError();
    holdKey.current = operationKey();
  };
  const handleFailure = (failure: unknown) => {
    setError(failure);
    if (failure instanceof ApiError && [404, 409].includes(failure.status)) {
      setSlot(undefined);
      refreshAvailability();
      if (failure.code === "HOLD_EXPIRED")
        setReservation((current) =>
          current ? { ...current, status: "EXPIRED" } : current,
        );
    }
  };
  const hold = async () => {
    if (!slot || !room || busy || cooldown > 0) return;
    setBusy(true);
    setError(undefined);
    try {
      const result = await api.hold(
        workspace.id,
        room.id,
        slot.startAt,
        holdKey.current,
      );
      setReservation(result);
      setSlot(undefined);
      refreshAvailability();
      onBookingChanged(result);
    } catch (failure) {
      handleFailure(failure);
    } finally {
      setBusy(false);
    }
  };
  const confirm = async () => {
    if (!reservation || busy || cooldown > 0) return;
    setBusy(true);
    setError(undefined);
    const key = confirmKeys.current.get(reservation.id) ?? operationKey();
    confirmKeys.current.set(reservation.id, key);
    try {
      const result = await api.confirm(workspace.id, reservation.id, key);
      setReservation(result);
      refreshAvailability();
      onBookingChanged(result);
    } catch (failure) {
      handleFailure(failure);
    } finally {
      setBusy(false);
    }
  };
  const cancel = async () => {
    if (!reservation || busy || cooldown > 0) return;
    setBusy(true);
    setError(undefined);
    try {
      const result = await api.cancel(workspace.id, reservation.id);
      setReservation(result);
      refreshAvailability();
      onBookingChanged(result);
    } catch (failure) {
      handleFailure(failure);
    } finally {
      setBusy(false);
    }
  };
  const startAgain = () => {
    setReservation(undefined);
    setSlot(undefined);
    setError(undefined);
    refreshAvailability();
  };

  if (rooms.length === 0)
    return (
      <div className="surface">
        <EmptyState
          title="This workspace has room to grow"
          action={
            onManageRooms && (
              <button className="button button-primary" onClick={onManageRooms}>
                <Plus size={16} />
                Add a room
              </button>
            )
          }
        >
          {onManageRooms
            ? "Add your first room so your team can start booking."
            : "There are no rooms here yet. Your workspace administrator can add one."}
        </EmptyState>
      </div>
    );

  return (
    <div className="booking-layout">
      <aside className="room-rail surface" aria-label="Choose a room">
        <div className="panel-topline">
          <span className="step-label">01 / ROOM</span>
          <span className="quiet-count">
            {rooms.filter((item) => item.enabled).length} open
          </span>
        </div>
        <h2>Find your space.</h2>
        <p className="panel-intro">Choose a room for your booking.</p>
        <div className="room-list">
          {rooms.map((item, index) => (
            <button
              key={item.id}
              className={`room-option ${roomId === item.id ? "is-selected" : ""}`}
              onClick={() => chooseRoom(item.id)}
              disabled={!item.enabled}
              aria-pressed={roomId === item.id}
            >
              <span
                className={`room-illustration room-tone-${index % 3}`}
                aria-hidden="true"
              >
                <DoorOpen size={25} strokeWidth={1.3} />
                <span className="room-illustration-line" />
              </span>
              <span className="room-option-copy">
                <strong>{item.name}</strong>
                <span>
                  {item.description || "A space for your working day."}
                </span>
                {!item.enabled && (
                  <span className="room-disabled-label">
                    Closed for new bookings
                  </span>
                )}
              </span>
              {roomId === item.id && (
                <Check
                  size={16}
                  className="room-selection-check"
                  aria-hidden="true"
                />
              )}
            </button>
          ))}
        </div>
        <div className="room-rail-note">
          <DoorOpen size={17} aria-hidden="true" />
          <p>
            Every booking is for one room,
            <br />
            for 30 minutes.
          </p>
        </div>
      </aside>
      <section
        className="availability-panel surface"
        aria-labelledby="availability-heading"
      >
        <div className="panel-topline">
          <span className="step-label">02 / TIME</span>
          <button
            className="text-button compact"
            onClick={refreshAvailability}
            disabled={availability.loading}
            aria-label="Refresh availability"
          >
            <RefreshCw size={13} aria-hidden="true" /> Refresh
          </button>
        </div>
        <div className="availability-title">
          <div>
            <h2 id="availability-heading">{room?.name ?? "Choose a room"}</h2>
            <p>{dateLabel(date)}</p>
          </div>
          <span className="duration-tag">
            <CalendarDays size={13} aria-hidden="true" /> 30 min
          </span>
        </div>
        <div className="day-strip" aria-label="Choose a booking day">
          {Array.from({ length: 7 }, (_, index) => {
            const day = addDays(today, index);
            return (
              <button
                key={day}
                className={date === day ? "is-selected" : ""}
                aria-label={dateLabel(day)}
                aria-pressed={date === day}
                onClick={() => chooseDate(day)}
              >
                <span>
                  {index === 0 ? "Today" : dateLabel(day, { weekday: "short" })}
                </span>
                <strong>{dateLabel(day, { day: "numeric" })}</strong>
                <span className="day-indicator" aria-hidden="true" />
              </button>
            );
          })}
        </div>
        <div className="date-toolbar">
          <div className="date-field">
            <label htmlFor="booking-date" className="sr-only">
              Booking date
            </label>
            <input
              type="date"
              id="booking-date"
              value={date}
              min={today}
              max={addDays(today, 6)}
              onChange={(event) => {
                if (
                  event.target.value >= today &&
                  event.target.value <= addDays(today, 6)
                )
                  chooseDate(event.target.value);
              }}
            />
          </div>
          <div className="date-arrows">
            <button
              className="icon-button"
              aria-label="Previous day"
              disabled={date <= today}
              onClick={() => chooseDate(addDays(date, -1))}
            >
              <ChevronLeft size={17} />
            </button>
            <button
              className="icon-button"
              aria-label="Next day"
              disabled={date >= addDays(today, 6)}
              onClick={() => chooseDate(addDays(date, 1))}
            >
              <ChevronRight size={17} />
            </button>
          </div>
        </div>
        <div className="availability-body" aria-busy={availability.loading}>
          {!roomId ? (
            <EmptyState title="No rooms open for booking">
              Your workspace administrator can reopen a room when it is ready.
            </EmptyState>
          ) : availability.error ? (
            <ErrorNotice
              error={availability.error}
              onRetry={refreshAvailability}
            />
          ) : availability.loading ? (
            <Loading>Checking available times…</Loading>
          ) : !availability.data?.slots.length ? (
            <EmptyState title="No times for this day">
              Choose another day or room to see more availability.
            </EmptyState>
          ) : (
            <>
              <div className="slots-intro">
                <span>Choose a time</span>
                <span>
                  {timeZoneLabel(
                    availability.data.slots[0]?.startAt ??
                      availability.data.serverNow,
                    workspace.timeZone,
                  )}
                </span>
              </div>
              <div className="slot-grid">
                {availability.data.slots.map((item) => {
                  const label =
                    item.availability === "HELD"
                      ? "On hold"
                      : item.availability.charAt(0) +
                        item.availability.slice(1).toLowerCase();
                  const selected = slot?.startAt === item.startAt;
                  return (
                    <button
                      key={item.startAt}
                      className={`slot-option slot-${item.availability.toLowerCase()} ${selected ? "is-selected" : ""}`}
                      disabled={item.availability !== "AVAILABLE" || busy}
                      aria-pressed={selected}
                      onClick={() => chooseSlot(item)}
                      aria-label={`${timeLabel(item.startAt, workspace.timeZone)} to ${timeLabel(item.endAt, workspace.timeZone)}, ${label}`}
                    >
                      <span className="slot-times">
                        {timeLabel(item.startAt, workspace.timeZone)}
                        <span>
                          – {timeLabel(item.endAt, workspace.timeZone)}
                        </span>
                      </span>
                      <span className="slot-state">
                        {selected ? (
                          <>
                            <Check size={12} aria-hidden="true" />
                            Selected
                          </>
                        ) : (
                          <>
                            <span className="slot-dot" aria-hidden="true" />
                            {label}
                          </>
                        )}
                      </span>
                    </button>
                  );
                })}
              </div>
              {!availability.data.slots.some(
                (item) => item.availability === "AVAILABLE",
              ) && (
                <p className="all-booked-note">
                  No available times here. Try a different room or day.
                </p>
              )}
              <div className="availability-legend">
                <span>
                  <span className="legend-dot available-dot" />
                  Available
                </span>
                <span>
                  <span className="legend-dot held-dot" />
                  On hold
                </span>
                <span>
                  <span className="legend-dot booked-dot" />
                  Booked / unavailable
                </span>
              </div>
            </>
          )}
        </div>
        <div className="availability-footnote">
          Availability can change. Your time is reserved when you place a hold.
        </div>
      </section>
      <ReservationSummary
        workspace={workspace}
        selectedRoom={room}
        selectedSlot={slot}
        reservation={reservation}
        reservationRoom={rooms.find((item) => item.id === reservation?.roomId)}
        busy={busy}
        cooldown={cooldown}
        error={error}
        onHold={() => {
          void hold();
        }}
        onConfirm={() => {
          void confirm();
        }}
        onCancel={() => {
          void cancel();
        }}
        onExpired={refreshAvailability}
        onStartAgain={startAgain}
      />
    </div>
  );
}
