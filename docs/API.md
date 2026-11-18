# Slotkeeper HTTP contract v1

All paths are same-origin. JSON uses UTF-8. Times are ISO8601 UTC instants; workspace dates use its named zone. This contract is the implementation target until DELIVERY.md records observed verification.

## Session and CSRF

GET /api/session returns200 with {"authenticated":false,"serverNow":"instant"} or {"authenticated":true,"user":{"id":"uuid","displayName":"Alex"},"serverNow":"instant"}. It never returns a password/hash/token. GET /api/workspaces requires authentication and returns {"items":[{"id":"uuid","name":"Harbor Works","timeZone":"Europe/Budapest","role":"MEMBER"}]}.

GET /api/csrf returns {"headerName":"X-CSRF-TOKEN","token":"opaque"}. This token is private runtime state and must never be copied to public evidence. Anonymous token/session creation is bounded separately at60requests per fixed minute per actual peer; normal authenticated refresh uses the existing session.

POST /api/session uses application/x-www-form-urlencoded with username and password and the CSRF header. Success200 has the authenticated session view; bad credentials401 have a fixed generic error. Login rotates the session identity. Fetch CSRF again after login. POST /api/logout with the current CSRF header returns204, invalidates server session state and expires the cookie.

Cookie: SLOTKEEPER_SESSION; HttpOnly; SameSite=Lax; Path=/; idle timeout30minutes. Local HTTP uses Secure=false. Authentication credentials are never stored in browser localStorage.

## Rooms and availability

GET /api/workspaces/{workspaceId}/rooms returns {"items":[{"id":"uuid","workspaceId":"uuid","name":"Oak","description":"A quiet room.","enabled":true}]} for an authorized member.

Scoped admins can POST the collection with {"name":"Oak","description":"A quiet room."}, returning201. PATCH /rooms/{roomId} accepts only name, description and enabled; at least one must be supplied. Names are1–80 trimmed characters, descriptions0–500. User-supplied owner/workspace/role/ID fields are forbidden. Mutations return the room view.

GET /api/workspaces/{workspaceId}/availability?roomId={uuid}&date=YYYY-MM-DD returns:

~~~json
{
  "date": "2026-11-18",
  "timeZone": "Europe/Budapest",
  "serverNow": "2026-11-18T10:00:00Z",
  "room": {"id":"uuid","name":"Oak","enabled":true},
  "slots": [{"startAt":"2026-11-18T11:00:00Z","endAt":"2026-11-18T11:30:00Z","availability":"AVAILABLE","holdExpiresAt":null}]
}
~~~

Availability values: AVAILABLE, HELD, BOOKED, UNAVAILABLE. It exposes no other user's identity. Dates are today through today+6 in the workspace zone. Local schedule is09:00–17:00, daily, at30minute boundaries. Existing past slots remain unavailable. The database time is authoritative; the client countdown is advisory.

## Reservation views and mutations

POST /api/workspaces/{workspaceId}/holds with {"roomId":"uuid","startAt":"2026-11-18T11:00:00Z"} and Idempotency-Key creates a hold. First creation201; an authorized same-key same-input replay200.

POST /api/workspaces/{workspaceId}/reservations/{id}/confirm with empty JSON object and Idempotency-Key confirms an unexpired own hold. First transition200 and harmless replay200. POST /reservations/{id}/cancel with empty JSON object cancels an own HELD/CONFIRMED reservation; repeated own CANCELLED cancellation is harmless200. Expired/invalid transitions409. All paths include the same workspace prefix.

Every success returns:

~~~json
{
  "id": "uuid",
  "workspaceId": "uuid",
  "roomId": "uuid",
  "ownerId": "uuid",
  "startAt": "2026-11-18T11:00:00Z",
  "endAt": "2026-11-18T11:30:00Z",
  "status": "HELD",
  "createdAt": "2026-11-18T10:00:00Z",
  "expiresAt": "2026-11-18T10:02:00Z",
  "confirmedAt": null,
  "serverNow": "2026-11-18T10:00:00Z",
  "replayed": false
}
~~~

Status is HELD, CONFIRMED, CANCELLED or EXPIRED at the observed time. expiresAt records the original hold deadline even after confirmation; confirmedAt remains recorded after cancellation. A replay returns the same resource identity and current authorized state, not an invented historical status. No second business effect is applied.

Idempotency-Key must match [A-Za-z0-9][A-Za-z0-9._:-]{7,127}. Its scope is workspace, authenticated actor and operation. Fingerprint input has an explicit field order, normalized UUIDs/Instant values and operation name; duplicate JSON keys/unknown fields are rejected before canonicalization. A different validated request under a retained key returns409 IDEMPOTENCY_CONFLICT. Records are retained for30days and at least until the linked slot has ended; an old request outside the booking horizon cannot recreate a past reservation after cleanup. Current authentication/authority and traffic admission precede receipt reuse.

GET /api/workspaces/{workspaceId}/reservations/{id} exposes an own scoped reservation; foreign ownership/workspace is404. GET /api/workspaces/{workspaceId}/my-bookings?page=0&size=20 returns {"items":[reservationView],"page":0,"size":20,"hasMore":false}. page is0–1000; size1–50. Order is startAt descending then ID.

## Quotas and activity

At most two unexpired holds per actor/workspace. At most four successful confirmations per actor/workspace/slot-local-date. Cancellation does not refund that allowance. At most one active reservation owns a room slot.

GET /api/workspaces/{workspaceId}/activity?page=0&size=20 requires a scoped admin and returns {"items":[{"id":"uuid","kind":"CONFIRMED","reservationId":"uuid","actorId":"uuid","roomId":"uuid","occurredAt":"instant"}],"page":0,"size":20,"hasMore":false}. It contains successful product events only; private security diagnostics and credentials are excluded.

## Errors and admission

Stable error shape:

~~~json
{"type":"urn:slotkeeper:problem:slot-unavailable","title":"Slot unavailable","status":409,"code":"SLOT_UNAVAILABLE","detail":"This slot is no longer available. Choose another time.","requestId":"uuid"}
~~~

Codes: VALIDATION_ERROR400, AUTHENTICATION_REQUIRED401, INVALID_CREDENTIALS401, FORBIDDEN403, CSRF_INVALID403, NOT_FOUND404, SLOT_UNAVAILABLE409, QUOTA_EXCEEDED409, HOLD_EXPIRED409, INVALID_STATE409, IDEMPOTENCY_CONFLICT409, PAYLOAD_TOO_LARGE413, UNSUPPORTED_MEDIA_TYPE415, RATE_LIMITED429, RESOURCE_BUSY503, SERVICE_UNAVAILABLE503.

JSON bodies max16KiB; URL-form login fields are bounded before hashing; pagination/date/text limits above apply. Password input max256characters and username max80. Reject malformed/duplicate/unknown fields without echoing the input. Login20attempts per actual peer per UTC fixed minute; authenticated mutations60per actor/workspace per UTC fixed minute. Rate decisions are atomic across app processes. 429 includes Retry-After to the next window, at least one second. A fixed-window boundary can permit a burst spanning two windows; this is documented behavior, not a rolling-window claim. Ignore client-supplied Forwarded/X-Forwarded-For when deriving the peer.

## Local health/ownership marker

GET /actuator/health returns minimal health/readiness. GET /api/instance returns {"project":"Slotkeeper","instanceId":"uuid","mode":"LOCAL","version":"0.1.0"}. The instance marker prevents accidental tests against a different local process; it is not an authentication/security attestation. The runner also verifies process/container ownership and loopback target origin.


## Synthetic local identity seed

Development-only usernames: alex (Harbor member), morgan (Harbor admin), riley (Studio member), jordan (Studio admin and Harbor member). The local fixture password is Slotkeeper-Local-Only! for these synthetic accounts; it is not a production secret or an external-service credential. Membership is read from the database for each scope. Jordan's admin role in Studio does not grant admin access in Harbor. No real names/emails or external user records are imported.
