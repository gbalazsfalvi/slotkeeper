package io.github.gbalazsfalvi.slotkeeper.persistence;

import io.github.gbalazsfalvi.slotkeeper.core.reservation.*;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.Room;
import java.sql.*;
import java.time.*;
import java.util.UUID;

/** Central typed mappings; SQL metadata and persistence details never enter the core. */
final class JdbcRows {
  private JdbcRows() {}

  static OffsetDateTime timestamp(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  static Instant instant(ResultSet rows, String column) throws SQLException {
    OffsetDateTime value = rows.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  static UUID uuid(ResultSet rows, String column) throws SQLException {
    return rows.getObject(column, UUID.class);
  }

  static Room room(ResultSet rows, int index) throws SQLException {
    return new Room(
        uuid(rows, "id"),
        uuid(rows, "workspace_id"),
        rows.getString("name"),
        rows.getString("description"),
        rows.getBoolean("enabled"));
  }

  static Reservation reservation(ResultSet rows, int index) throws SQLException {
    return new Reservation(
        uuid(rows, "id"),
        uuid(rows, "workspace_id"),
        uuid(rows, "room_id"),
        uuid(rows, "owner_id"),
        instant(rows, "start_at"),
        instant(rows, "end_at"),
        ReservationStatus.valueOf(rows.getString("status")),
        instant(rows, "created_at"),
        instant(rows, "expires_at"),
        instant(rows, "confirmed_at"));
  }

  static BookingEvent event(ResultSet rows, int index) throws SQLException {
    return new BookingEvent(
        uuid(rows, "id"),
        uuid(rows, "workspace_id"),
        BookingEvent.Kind.valueOf(rows.getString("kind")),
        uuid(rows, "reservation_id"),
        uuid(rows, "actor_id"),
        uuid(rows, "room_id"),
        instant(rows, "occurred_at"));
  }
}
