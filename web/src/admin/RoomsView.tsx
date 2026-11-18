import { useCallback, useState } from "react";
import { DoorOpen, Pencil, Plus } from "lucide-react";
import type { FormEvent } from "react";
import type { Room, Workspace } from "../api/contracts";
import { useSession } from "../session/SessionProvider";
import { EmptyState, ErrorNotice } from "../shared/Feedback";
import { Modal } from "../shared/Modal";
import { useCooldown } from "../shared/hooks";

function RoomForm({
  room,
  workspace,
  onSaved,
  onClose,
}: {
  room?: Room;
  workspace: Workspace;
  onSaved: () => void;
  onClose: () => void;
}) {
  const { api } = useSession();
  const [name, setName] = useState(room?.name ?? "");
  const [description, setDescription] = useState(room?.description ?? "");
  const [enabled, setEnabled] = useState(room?.enabled ?? true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const [validation, setValidation] = useState("");
  const cooldown = useCooldown(error);
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy || cooldown > 0) return;
    if (!name.trim()) {
      setValidation("Enter a room name.");
      return;
    }
    setValidation("");
    setError(undefined);
    setBusy(true);
    try {
      if (room)
        await api.updateRoom(workspace.id, room.id, {
          name: name.trim(),
          description: description.trim(),
          enabled,
        });
      else
        await api.createRoom(workspace.id, {
          name: name.trim(),
          description: description.trim(),
        });
      onSaved();
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title={room ? "Edit room" : "Add a room"}
      onClose={onClose}
      busy={busy}
    >
      <p className="modal-description">
        {room
          ? "Update how this room appears in "
          : "Create a bookable space in "}
        {workspace.name}.
      </p>
      <ErrorNotice error={error} />
      <form
        className="room-form"
        onSubmit={(event) => {
          void save(event);
        }}
      >
        <label htmlFor="room-name">
          Room name <span aria-hidden="true">Required</span>
        </label>
        <input
          id="room-name"
          autoComplete="off"
          maxLength={80}
          required
          value={name}
          aria-invalid={!!validation}
          aria-describedby={validation ? "room-name-error" : undefined}
          onChange={(event) => {
            setName(event.target.value);
            setValidation("");
          }}
        />
        {validation && (
          <p id="room-name-error" className="field-error" role="alert">
            {validation}
          </p>
        )}
        <label htmlFor="room-description">
          Description <span aria-hidden="true">Optional</span>
        </label>
        <textarea
          id="room-description"
          maxLength={500}
          value={description}
          rows={4}
          placeholder="Help your team choose the right space."
          onChange={(event) => setDescription(event.target.value)}
        />
        <div className="field-character-count">{description.length} / 500</div>
        {room && (
          <div className="room-enabled-field">
            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={enabled}
                onChange={(event) => setEnabled(event.target.checked)}
              />
              <span>Open for new bookings</span>
            </label>
            <p>
              Closing a room prevents new holds. Existing holds and confirmed
              bookings are kept.
            </p>
            {room.enabled && !enabled && (
              <p className="room-closing-note">
                This room will be closed to new bookings when you save.
              </p>
            )}
          </div>
        )}
        <div className="modal-actions">
          <button
            type="button"
            className="button button-secondary"
            onClick={onClose}
            disabled={busy}
          >
            Discard changes
          </button>
          <button
            type="submit"
            className="button button-primary"
            disabled={busy || cooldown > 0}
          >
            {busy ? "Saving…" : room ? "Save changes" : "Create room"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

export function RoomsView({
  workspace,
  rooms,
  onChanged,
}: {
  workspace: Workspace;
  rooms: Room[];
  onChanged: () => void;
}) {
  const [editing, setEditing] = useState<Room | "new">();
  const close = useCallback(() => setEditing(undefined), []);
  return (
    <section className="rooms-admin-view" aria-label="Room administration">
      <div className="view-toolbar">
        <p>
          {rooms.length} {rooms.length === 1 ? "room" : "rooms"} in{" "}
          {workspace.name}
        </p>
        <button
          className="button button-primary"
          onClick={() => setEditing("new")}
        >
          <Plus size={16} aria-hidden="true" />
          Add room
        </button>
      </div>
      {!rooms.length ? (
        <div className="surface">
          <EmptyState title="Make space for your team">
            Use Add room to create the first bookable room in this workspace.
          </EmptyState>
        </div>
      ) : (
        <div className="admin-room-list">
          {rooms.map((room, index) => (
            <article className="admin-room-card surface" key={room.id}>
              <span
                className={`admin-room-illustration room-tone-${index % 3}`}
                aria-hidden="true"
              >
                <DoorOpen size={34} strokeWidth={1.2} />
                <span />
              </span>
              <div className="admin-room-copy">
                <div className="admin-room-title">
                  <h2>{room.name}</h2>
                  <span
                    className={`room-state-label ${room.enabled ? "is-open" : "is-closed"}`}
                  >
                    <span />
                    {room.enabled
                      ? "Open for booking"
                      : "Closed to new bookings"}
                  </span>
                </div>
                <p>{room.description || "No description yet."}</p>
                <span className="room-card-policy">
                  30-minute bookings · Capacity one per time slot
                </span>
              </div>
              <button
                className="button button-secondary"
                onClick={() => setEditing(room)}
                aria-label={`Edit ${room.name}`}
              >
                <Pencil size={14} aria-hidden="true" />
                Edit room
              </button>
            </article>
          ))}
        </div>
      )}
      <p className="admin-guidance">
        Room access belongs to this workspace. Closing a room does not cancel
        existing bookings.
      </p>
      {editing && (
        <RoomForm
          room={editing === "new" ? undefined : editing}
          workspace={workspace}
          onClose={close}
          onSaved={() => {
            setEditing(undefined);
            onChanged();
          }}
        />
      )}
    </section>
  );
}
