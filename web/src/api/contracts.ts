export interface User {
  id: string;
  displayName: string;
}
export type SessionView =
  | { authenticated: false; serverNow: string }
  | { authenticated: true; user: User; serverNow: string };
export interface CsrfToken {
  headerName: string;
  token: string;
}
export interface Workspace {
  id: string;
  name: string;
  timeZone: string;
  role: "MEMBER" | "WORKSPACE_ADMIN";
}
export interface Room {
  id: string;
  workspaceId: string;
  name: string;
  description: string;
  enabled: boolean;
}
export interface RoomInput {
  name: string;
  description: string;
}
export interface Slot {
  startAt: string;
  endAt: string;
  availability: "AVAILABLE" | "HELD" | "BOOKED" | "UNAVAILABLE";
  holdExpiresAt: string | null;
}
export interface Availability {
  date: string;
  timeZone: string;
  serverNow: string;
  room: Pick<Room, "id" | "name" | "enabled">;
  slots: Slot[];
}
export type ReservationStatus = "HELD" | "CONFIRMED" | "CANCELLED" | "EXPIRED";
export interface Reservation {
  id: string;
  workspaceId: string;
  roomId: string;
  ownerId: string;
  startAt: string;
  endAt: string;
  status: ReservationStatus;
  createdAt: string;
  expiresAt: string | null;
  confirmedAt: string | null;
  serverNow: string;
  replayed: boolean;
}
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  hasMore: boolean;
}
export interface ActivityEvent {
  id: string;
  kind: string;
  reservationId: string;
  actorId: string;
  roomId: string;
  occurredAt: string;
}
export interface Problem {
  type?: string;
  title?: string;
  status: number;
  code: string;
  detail?: string;
  requestId?: string;
}
