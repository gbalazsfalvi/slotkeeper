import { useState } from "react";
import {
  ArrowDownLeft,
  Check,
  ChevronLeft,
  ChevronRight,
  Clock3,
  RefreshCw,
  X,
} from "lucide-react";
import type { Room, Workspace } from "../api/contracts";
import { useSession } from "../session/SessionProvider";
import { EmptyState, ErrorNotice, Loading } from "../shared/Feedback";
import { useResource } from "../shared/hooks";
import { instantDateLabel, timeLabel } from "../shared/time";

function EventLabel({ kind }: { kind: string }) {
  const label: Record<string, string> = {
    HELD: "Time held",
    HOLD_CREATED: "Time held",
    CONFIRMED: "Booking confirmed",
    CANCELLED: "Booking cancelled",
    EXPIRED: "Hold expired",
  };
  const Icon =
    kind === "CONFIRMED"
      ? Check
      : kind === "CANCELLED"
        ? X
        : kind === "EXPIRED"
          ? Clock3
          : ArrowDownLeft;
  return (
    <span className={`event-label event-${kind.toLowerCase()}`}>
      <span className="event-icon">
        <Icon size={14} aria-hidden="true" />
      </span>
      {label[kind] ?? "Booking updated"}
    </span>
  );
}

export function ActivityView({
  workspace,
  rooms,
}: {
  workspace: Workspace;
  rooms: Room[];
}) {
  const { api } = useSession();
  const [page, setPage] = useState(0);
  const activity = useResource(
    (signal) => api.activity(workspace.id, page, signal),
    [api, workspace.id, page],
  );
  return (
    <section className="activity-view" aria-label="Workspace booking activity">
      <div className="view-toolbar">
        <div>
          <span className="section-kicker">BOOKING HISTORY</span>
          <p className="toolbar-description">
            Times shown in {workspace.timeZone}.
          </p>
        </div>
        <button
          className="button button-secondary"
          disabled={activity.loading}
          onClick={activity.refresh}
        >
          <RefreshCw size={14} aria-hidden="true" />
          Refresh activity
        </button>
      </div>
      {activity.error ? (
        <ErrorNotice error={activity.error} onRetry={activity.refresh} />
      ) : activity.loading ? (
        <Loading>Loading workspace activity…</Loading>
      ) : !activity.data?.items.length ? (
        <div className="surface">
          <EmptyState title="Nothing to catch up on">
            Successful booking changes will appear here as your team uses the
            workspace.
          </EmptyState>
        </div>
      ) : (
        <div className="activity-table-wrap surface">
          <table className="activity-table">
            <caption className="sr-only">
              Successful booking events in {workspace.name}
            </caption>
            <thead>
              <tr>
                <th scope="col">Activity</th>
                <th scope="col">Room</th>
                <th scope="col">Member reference</th>
                <th scope="col">When</th>
              </tr>
            </thead>
            <tbody>
              {activity.data.items.map((event) => (
                <tr key={event.id}>
                  <td>
                    <EventLabel kind={event.kind} />
                    <span
                      className="activity-booking-reference"
                      title={event.reservationId}
                    >
                      Booking {event.reservationId.slice(0, 8)}
                    </span>
                  </td>
                  <td>
                    {rooms.find((room) => room.id === event.roomId)?.name ??
                      "Room no longer listed"}
                  </td>
                  <td>
                    <span className="member-reference" title={event.actorId}>
                      {event.actorId.slice(0, 8)}
                    </span>
                  </td>
                  <td>
                    <strong>
                      {timeLabel(event.occurredAt, workspace.timeZone)}
                    </strong>
                    <span className="activity-date">
                      {instantDateLabel(event.occurredAt, workspace.timeZone)}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {activity.data && (page > 0 || activity.data.hasMore) && (
        <div className="pagination">
          <button
            className="button button-secondary"
            disabled={page === 0 || activity.loading}
            onClick={() => setPage((value) => value - 1)}
          >
            <ChevronLeft size={15} />
            Previous
          </button>
          <span>Page {page + 1}</span>
          <button
            className="button button-secondary"
            disabled={!activity.data.hasMore || activity.loading}
            onClick={() => setPage((value) => value + 1)}
          >
            Next
            <ChevronRight size={15} />
          </button>
        </div>
      )}
    </section>
  );
}
