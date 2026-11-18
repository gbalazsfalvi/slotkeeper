# Five-minute reviewer walkthrough

Use this sequence after the local environment is running. Building images and running the full rehearsal take additional time, including real hold expiry. The member/admin/browser journeys and complete local rehearsal passed at the code revision recorded in [DELIVERY.md](DELIVERY.md). The [included evidence snapshot](evidence/v0.1.0/README.md) can be inspected without starting the application.

## Before the timer

From the repository root, run `.\slotkeeper.ps1 up` in PowerShell or `./slotkeeper.sh up` in Bash. Open [the local product](http://127.0.0.1:4190). Run `status` through the same entry point to inspect the owned environment.

All identities are synthetic and use the public fixture password `Slotkeeper-Local-Only!`. The sign-in page can fill each account. Use a future day if today's schedule has ended; rooms have 30-minute slots between 09:00 and 17:00 in the displayed workspace zone.

## 0:00–1:30 · Make a booking

1. Sign in as **alex**, a Harbor Works member. Check the workspace name and time zone.
2. In **Book a room**, choose Oak or Maple, tomorrow and an available time. Choose **Hold this time**.
3. Inspect the held room/date/time and countdown. The hold lasts up to two minutes, subject to slot start; the server makes the expiry decision.
4. Choose **Confirm booking**. Expect **Your room is booked.** Open **My bookings**, then **View booking** to inspect its reference and timestamps.
5. Close the details, choose **Cancel booking**, and confirm the dialog. Expect **Cancelled** while the original booking remains in history. Sign out.

## 1:30–2:30 · Inspect workspace administration

1. Sign in as **morgan**, Harbor Works admin. Open **Manage rooms**.
2. Choose **Add room**, enter `Reviewer room` and a short synthetic description, then **Create room**. Edit the room, clear **Open for new bookings**, and **Save changes**. It should show **Closed to new bookings**.
3. Open **Activity**. Inspect the successful hold/confirmation/cancellation events from the member journey. This is scoped booking history; room edits and security diagnostics are not represented as booking events.
4. Sign out. If time permits, sign in as **jordan** and switch workspaces: Studio North exposes admin navigation; Harbor Works exposes member navigation. The server applies those roles independently of what the browser shows.

## 2:30–4:00 · Read actual evidence

Before a review session, run `.\slotkeeper.ps1 rehearse` or `./slotkeeper.sh rehearse`. This obtains fresh verification evidence and explicitly adds the second app process and isolated teaching target. Use the directory printed by that run under `artifacts/rehearsal/<timestamp>/`; the included v0.1.0 snapshot records the completed run `20261118T132545704Z`.

Open the generated `report.html` and inspect:

- The exact source revision, start/finish instants, `complete` value and outcome counts.
- **A02/A03:** the denied foreign access and independently unchanged target state.
- **A06/A07:** one resource/effect for retries and a changed-input key conflict.
- **A08/A09:** real expiry and preservation of a replacement assignment.
- **A12:** HTTP outcome classification separately from the 100-attempt, two-JVM database evidence. `429` is not proof that a request reached a database lock.
- **T01:** the isolated fixture actually demonstrates the missing-scope leak. Its actor model is explicitly reduced.
- **S01:** a session and logout are checked across independent app processes.
- **S02:** a read-only consistent PostgreSQL snapshot checks slot assignments, allowances, receipts, events and restricted runtime permissions.

Use `report.json` for exact sanitized observations and assertions, `report.md` for a portable summary, and `SHA256SUMS` to check file bytes. Inspect the separate `database-contention.json` when assessing A12's database claim. A `FAIL`, `ERROR` or `INCOMPLETE` result remains meaningful; missing evidence does not pass. The included complete report and delivery entry identify the observed local run.

## 4:00–5:00 · Inspect the implementation and limits

Read the transaction sequence in [ARCHITECTURE.md](ARCHITECTURE.md) alongside `ReservationService`, `JdbcBookingTransaction` and `V001__baseline.sql`. Look for the actor/slot lock order, fresh database time, scoped receipt uniqueness and expected-reservation-ID release.

Read [THREAT_MODEL.md](THREAT_MODEL.md) for the local trust assumptions and [DELIVERY.md](DELIVERY.md) for observed checks and remaining gates. The README includes actual desktop/mobile captures and the complete report screenshot, with links to the original exports.

When finished, run `.\slotkeeper.ps1 down` or `./slotkeeper.sh down`. Shutdown is scoped to Slotkeeper and retains its persistent data; the reviewer-created room/history may remain for the next local session.
