-- PostgreSQL is the authority for slot ownership and durable retries.
CREATE TABLE users (
 id uuid PRIMARY KEY, username varchar(80) NOT NULL UNIQUE,
 display_name varchar(80) NOT NULL, password_hash varchar(256) NOT NULL, enabled boolean NOT NULL DEFAULT true
);
CREATE TABLE workspaces (id uuid PRIMARY KEY, name varchar(80) NOT NULL, time_zone varchar(80) NOT NULL);
CREATE TABLE workspace_memberships (
 workspace_id uuid NOT NULL REFERENCES workspaces(id), user_id uuid NOT NULL REFERENCES users(id),
 role varchar(20) NOT NULL CHECK (role IN ('MEMBER','WORKSPACE_ADMIN')), active boolean NOT NULL DEFAULT true,
 PRIMARY KEY(workspace_id,user_id)
);
CREATE TABLE rooms (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id),
 name varchar(80) NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 80),
 description varchar(500) NOT NULL, enabled boolean NOT NULL DEFAULT true, UNIQUE(workspace_id,id)
);
CREATE TABLE slots (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL, room_id uuid NOT NULL,
 start_at timestamptz NOT NULL, end_at timestamptz NOT NULL, assigned_reservation_id uuid,
 FOREIGN KEY(workspace_id,room_id) REFERENCES rooms(workspace_id,id),
 CHECK (end_at = start_at + interval '30 minutes'),
 UNIQUE(workspace_id,room_id,start_at), UNIQUE(workspace_id,id,room_id,start_at,end_at)
);
CREATE TABLE reservations (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL, room_id uuid NOT NULL, slot_id uuid NOT NULL,
 owner_id uuid NOT NULL, start_at timestamptz NOT NULL, end_at timestamptz NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('HELD','CONFIRMED','CANCELLED','EXPIRED')),
 created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, confirmed_at timestamptz,
 FOREIGN KEY(workspace_id,owner_id) REFERENCES workspace_memberships(workspace_id,user_id),
 FOREIGN KEY(workspace_id,slot_id,room_id,start_at,end_at) REFERENCES slots(workspace_id,id,room_id,start_at,end_at),
 CHECK (start_at > created_at AND expires_at > created_at AND expires_at <= start_at),
 CHECK (end_at = start_at + interval '30 minutes'),
 CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL),
 CHECK (status NOT IN ('HELD','EXPIRED') OR confirmed_at IS NULL),
 CHECK (confirmed_at IS NULL OR (confirmed_at >= created_at AND confirmed_at < expires_at)),
 UNIQUE(workspace_id,id,slot_id), UNIQUE(workspace_id,owner_id,id)
);
ALTER TABLE slots ADD CONSTRAINT slot_assignment_scope_fk
 FOREIGN KEY(workspace_id,assigned_reservation_id,id) REFERENCES reservations(workspace_id,id,slot_id);
CREATE INDEX reservation_owner_page ON reservations(workspace_id,owner_id,start_at DESC,id);
CREATE INDEX reservation_live_holds ON reservations(workspace_id,owner_id,expires_at) WHERE status='HELD';
CREATE INDEX reservation_confirmed_count ON reservations(workspace_id,owner_id,start_at) WHERE confirmed_at IS NOT NULL;
CREATE INDEX reservation_expiry ON reservations(expires_at) WHERE status='HELD';
CREATE TABLE actor_booking_guards (
 workspace_id uuid NOT NULL, user_id uuid NOT NULL, PRIMARY KEY(workspace_id,user_id),
 FOREIGN KEY(workspace_id,user_id) REFERENCES workspace_memberships(workspace_id,user_id)
);
CREATE TABLE workspace_room_guards (
 workspace_id uuid PRIMARY KEY REFERENCES workspaces(id)
);
CREATE TABLE idempotency_records (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL, actor_id uuid NOT NULL,
 operation varchar(20) NOT NULL CHECK(operation IN ('CREATE_HOLD','CONFIRM')),
 request_key varchar(128) NOT NULL, request_hash char(64) NOT NULL, reservation_id uuid,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), retained_until timestamptz NOT NULL DEFAULT clock_timestamp()+interval '30 days',
 UNIQUE(workspace_id,actor_id,operation,request_key),
 FOREIGN KEY(workspace_id,actor_id) REFERENCES workspace_memberships(workspace_id,user_id),
 FOREIGN KEY(workspace_id,actor_id,reservation_id) REFERENCES reservations(workspace_id,owner_id,id)
);
CREATE INDEX idempotency_retention ON idempotency_records(retained_until);
CREATE TABLE booking_events (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL, kind varchar(20) NOT NULL
 CHECK(kind IN ('HOLD_CREATED','CONFIRMED','CANCELLED','EXPIRED')),
 reservation_id uuid NOT NULL, actor_id uuid NOT NULL, room_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL,
 FOREIGN KEY(workspace_id,actor_id,reservation_id) REFERENCES reservations(workspace_id,owner_id,id),
 FOREIGN KEY(workspace_id,room_id) REFERENCES rooms(workspace_id,id),
 UNIQUE(reservation_id,kind)
);
CREATE INDEX event_page ON booking_events(workspace_id,occurred_at DESC,id);
CREATE TABLE rate_buckets (
 kind varchar(24) NOT NULL, scope_id varchar(160) NOT NULL,
 window_start timestamptz NOT NULL, attempts integer NOT NULL CHECK(attempts>0),
 PRIMARY KEY(kind,scope_id,window_start)
);
-- Standard Spring Session JDBC 4.1 schema, PostgreSQL variant (Apache-2.0).
CREATE TABLE spring_session (
 primary_id char(36) NOT NULL PRIMARY KEY, session_id char(36) NOT NULL,
 creation_time bigint NOT NULL, last_access_time bigint NOT NULL,
 max_inactive_interval integer NOT NULL, expiry_time bigint NOT NULL, principal_name varchar(100)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session(session_id);
CREATE INDEX spring_session_ix2 ON spring_session(expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session(principal_name);
CREATE TABLE spring_session_attributes (
 session_primary_id char(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE,
 attribute_name varchar(200) NOT NULL, attribute_bytes bytea NOT NULL,
 PRIMARY KEY(session_primary_id,attribute_name)
);
-- The web process never receives the migration role's credentials.
GRANT USAGE ON SCHEMA slotkeeper TO slotkeeper_app;
GRANT SELECT ON users,workspaces,workspace_memberships TO slotkeeper_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON rooms,slots,reservations,actor_booking_guards,workspace_room_guards,idempotency_records,rate_buckets,spring_session,spring_session_attributes TO slotkeeper_app;
GRANT SELECT,INSERT ON booking_events TO slotkeeper_app;
