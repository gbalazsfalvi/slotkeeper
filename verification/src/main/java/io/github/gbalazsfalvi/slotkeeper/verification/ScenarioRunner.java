package io.github.gbalazsfalvi.slotkeeper.verification;

import static io.github.gbalazsfalvi.slotkeeper.verification.ActorSession.CsrfMode.CURRENT;
import static io.github.gbalazsfalvi.slotkeeper.verification.ActorSession.CsrfMode.INVALID;
import static io.github.gbalazsfalvi.slotkeeper.verification.ActorSession.CsrfMode.NONE;
import static io.github.gbalazsfalvi.slotkeeper.verification.SanitizedObservation.Endpoint.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * Independent real-HTTP checks of the frozen API, with explicit prerequisite and error outcomes.
 */
final class ScenarioRunner {
  private final RehearsalOptions options;
  private final DatabaseStateProbe databaseProbe;
  private final java.util.function.Supplier<String> databasePassword;
  private final Instant startedAt = Instant.now();
  private final Map<CheckId, ScenarioResult> results = new EnumMap<>(CheckId.class);
  private final java.util.Set<UUID> matchedTargets = new java.util.HashSet<>();
  private ActorSession alex;
  private ActorSession morgan;
  private ActorSession riley;
  private ActorSession jordan;
  private OwnedTarget second;
  private UUID harbor;
  private UUID studio;
  private UUID harborRoom;
  private UUID studioRoom;
  private String date;
  private String studioDate;
  private List<String> slots;
  private List<String> studioSlots;
  private ActorSession.Response expiryHold;

  ScenarioRunner(RehearsalOptions options) {
    this(options, new DatabaseStateProbe(), () -> System.getenv("SLOTKEEPER_PROBE_DB_PASSWORD"));
  }

  ScenarioRunner(
      RehearsalOptions options,
      DatabaseStateProbe databaseProbe,
      java.util.function.Supplier<String> databasePassword) {
    this.options = options;
    this.databaseProbe = databaseProbe;
    this.databasePassword = databasePassword;
  }

  EvidenceReport run() {
    boolean admitted = false;
    boolean prepared = false;
    try {
      new ActorSession(options.app()).verifyTarget();
      admitted = true;
      matchedTargets.add(options.app().expectedInstanceId());
      prepare();
      prepared = true;
    } catch (IllegalArgumentException exception) {
      putError(CheckId.A01, admitted ? Reason.UNEXPECTED_RESPONSE : Reason.TARGET_NOT_OWNED);
    } catch (IOException exception) {
      putError(CheckId.A01, Reason.TARGET_UNAVAILABLE);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      putError(CheckId.A01, Reason.TRANSPORT_ERROR);
    } catch (Prerequisite exception) {
      results.put(CheckId.A01, ScenarioResult.incomplete(CheckId.A01, Reason.PREREQUISITE_MISSING));
    } catch (RuntimeException exception) {
      putError(CheckId.A01, Reason.UNEXPECTED_RESPONSE);
    }

    if (prepared) {
      admitSecond();
      runCase(CheckId.A01, this::anonymous);
      runCase(CheckId.A02, this::foreignWorkspace);
      runCase(CheckId.A03, this::foreignCancellation);
      runCase(CheckId.A04, this::forgedAuthority);
      runCase(CheckId.A05, this::csrf);
      runCase(CheckId.A06, this::idempotent);
      runCase(CheckId.A07, this::changedInput);
      runCase(CheckId.A10, this::hoarding);
      runCase(CheckId.A12, this::contention);
      runCase(CheckId.A11, this::flood);
      runCase(CheckId.A08, this::expiry);
      runCase(CheckId.A09, this::staleCancellation);
      if (!results.containsKey(CheckId.S01)) runCase(CheckId.S01, this::sharedSession);
    }
    runCase(CheckId.T01, this::teachingControl);
    if (admitted) {
      try {
        results.put(
            CheckId.S02,
            databaseProbe.check(
                options.databaseTarget(),
                options.databaseTarget() == null ? null : databasePassword.get()));
      } catch (RuntimeException exception) {
        putError(CheckId.S02, Reason.DATABASE_PROBE_UNAVAILABLE);
      }
    } else
      results.put(CheckId.S02, ScenarioResult.incomplete(CheckId.S02, Reason.PREREQUISITE_MISSING));
    var snapshots = new ArrayList<TargetSnapshot>();
    for (OwnedTarget target : new OwnedTarget[] {options.app(), options.app2(), options.training()})
      if (target != null)
        snapshots.add(
            new TargetSnapshot(
                target.kind(),
                target.origin().getPort(),
                target.expectedInstanceId(),
                matchedTargets.contains(target.expectedInstanceId()),
                target.trustedFixturePort()));
    return EvidenceReport.of(
        startedAt, Instant.now(), options.revision(), new ArrayList<>(results.values()), snapshots);
  }

  private void prepare() throws IOException, InterruptedException, Prerequisite {
    alex = actor("alex");
    morgan = actor("morgan");
    riley = actor("riley");
    jordan = actor("jordan");
    Map<String, Object> harborView =
        firstItem(alex.request("GET", "/api/workspaces", null, null, NONE, Map.of()));
    Map<String, Object> studioView =
        firstItem(riley.request("GET", "/api/workspaces", null, null, NONE, Map.of()));
    harbor = uuid(harborView, "id");
    studio = uuid(studioView, "id");
    need(!harbor.equals(studio));
    harborRoom =
        firstEnabledRoom(alex.request("GET", root(harbor) + "/rooms", null, null, NONE, Map.of()));
    studioRoom =
        firstEnabledRoom(riley.request("GET", root(studio) + "/rooms", null, null, NONE, Map.of()));
    Instant now =
        Instant.parse(
            String.valueOf(
                alex.request("GET", "/api/session", null, null, NONE, Map.of())
                    .body()
                    .get("serverNow")));
    date =
        LocalDate.ofInstant(now, ZoneId.of(String.valueOf(harborView.get("timeZone"))))
            .plusDays(2)
            .toString();
    studioDate =
        LocalDate.ofInstant(now, ZoneId.of(String.valueOf(studioView.get("timeZone"))))
            .plusDays(2)
            .toString();
    slots = availableSlots(alex, harbor, harborRoom, date);
    studioSlots = availableSlots(riley, studio, studioRoom, studioDate);
    need(slots.size() >= 12 && !studioSlots.isEmpty());
    expiryHold =
        alex.request(
            "POST",
            root(harbor) + "/holds",
            holdBody(alex, harborRoom, slots.getFirst()),
            key(),
            CURRENT,
            Map.of());
    need(expiryHold.status() == 201 && expiryHold.state().equals("HELD"));
  }

  private ActorSession actor(String name) throws IOException, InterruptedException {
    var session = new ActorSession(options.app());
    session.login(name);
    return session;
  }

  private void admitSecond() {
    if (options.app2() == null) {
      results.put(
          CheckId.S01, ScenarioResult.incomplete(CheckId.S01, Reason.SECOND_PROCESS_MISSING));
      return;
    }
    try {
      new ActorSession(options.app2()).verifyTarget();
      second = options.app2();
      matchedTargets.add(second.expectedInstanceId());
    } catch (IllegalArgumentException exception) {
      putError(CheckId.S01, Reason.TARGET_NOT_OWNED);
    } catch (IOException exception) {
      putError(CheckId.S01, Reason.TARGET_UNAVAILABLE);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      putError(CheckId.S01, Reason.TRANSPORT_ERROR);
    }
  }

  private void anonymous(CaseEvidence evidence) throws IOException, InterruptedException {
    String before = availability(alex, harbor, harborRoom, date, slots.get(1));
    var anonymous = new ActorSession(options.app());
    anonymous.refreshCsrf();
    var read =
        observe(
            evidence,
            anonymous,
            "GET",
            root(harbor) + "/my-bookings",
            MY_BOOKINGS,
            null,
            null,
            NONE,
            Map.of());
    var mutation =
        observe(
            evidence,
            anonymous,
            "POST",
            root(harbor) + "/holds",
            HOLDS,
            holdBody(anonymous, harborRoom, slots.get(1)),
            key(),
            CURRENT,
            Map.of());
    evidence.expect(
        AssertionKind.ANONYMOUS_DENIED,
        read.status() == 401
            && mutation.status() == 401
            && read.code().equals("AUTHENTICATION_REQUIRED")
            && mutation.code().equals("AUTHENTICATION_REQUIRED"));
    evidence.expect(
        AssertionKind.ORIGINAL_STATE_UNCHANGED,
        before.equals("AVAILABLE")
            && before.equals(availability(alex, harbor, harborRoom, date, slots.get(1))));
  }

  private void foreignWorkspace(CaseEvidence evidence)
      throws IOException, InterruptedException, Prerequisite {
    var own =
        observe(
            evidence,
            riley,
            "POST",
            root(studio) + "/holds",
            HOLDS,
            holdBody(riley, studioRoom, studioSlots.getFirst()),
            key(),
            CURRENT,
            Map.of());
    need(own.status() == 201);
    UUID id = own.id();
    var direct =
        observe(
            evidence,
            alex,
            "GET",
            reservation(studio, id),
            RESERVATION,
            null,
            null,
            NONE,
            Map.of());
    var swapped =
        observe(
            evidence,
            alex,
            "GET",
            reservation(harbor, id),
            RESERVATION,
            null,
            null,
            NONE,
            Map.of());
    evidence.expect(
        AssertionKind.FOREIGN_OBJECT_ABSENT, absentObject(direct) && absentObject(swapped));
    var after =
        observe(
            evidence,
            riley,
            "GET",
            reservation(studio, id),
            RESERVATION,
            null,
            null,
            NONE,
            Map.of());
    evidence.expect(
        AssertionKind.ORIGINAL_STATE_UNCHANGED, sameHeld(after, id, studio, riley.userId()));
    cancel(riley, studio, id);
  }

  private void foreignCancellation(CaseEvidence evidence)
      throws IOException, InterruptedException, Prerequisite {
    var own =
        observe(
            evidence,
            morgan,
            "POST",
            root(harbor) + "/holds",
            HOLDS,
            holdBody(morgan, harborRoom, slots.get(1)),
            key(),
            CURRENT,
            Map.of());
    need(own.status() == 201);
    var denied =
        observe(
            evidence,
            alex,
            "POST",
            reservation(harbor, own.id()) + "/cancel",
            CANCEL,
            "{}",
            null,
            CURRENT,
            Map.of());
    evidence.expect(AssertionKind.FOREIGN_OBJECT_ABSENT, absentObject(denied));
    var after =
        observe(
            evidence,
            morgan,
            "GET",
            reservation(harbor, own.id()),
            RESERVATION,
            null,
            null,
            NONE,
            Map.of());
    evidence.expect(
        AssertionKind.ORIGINAL_STATE_UNCHANGED, sameHeld(after, own.id(), harbor, morgan.userId()));
    cancel(morgan, harbor, own.id());
  }

  private void forgedAuthority(CaseEvidence evidence) throws IOException, InterruptedException {
    String room =
        alex.json(
            Map.of("name", "Synthetic forbidden room", "description", "Rehearsal role check"));
    var member =
        observe(
            evidence, alex, "POST", root(harbor) + "/rooms", ROOMS, room, null, CURRENT, Map.of());
    var crossRole =
        observe(
            evidence,
            jordan,
            "POST",
            root(harbor) + "/rooms",
            ROOMS,
            room,
            null,
            CURRENT,
            Map.of());
    evidence.expect(
        AssertionKind.ROLE_DENIED,
        member.status() == 403
            && member.code().equals("FORBIDDEN")
            && crossRole.status() == 403
            && crossRole.code().equals("FORBIDDEN"));
    String forged =
        alex.json(
            Map.of(
                "roomId",
                harborRoom,
                "startAt",
                slots.get(2),
                "ownerId",
                morgan.userId(),
                "role",
                "WORKSPACE_ADMIN",
                "workspaceId",
                studio));
    var fields =
        observe(
            evidence,
            alex,
            "POST",
            root(harbor) + "/holds",
            HOLDS,
            forged,
            key(),
            CURRENT,
            Map.of());
    String duplicate =
        "{\"roomId\":\""
            + harborRoom
            + "\",\"roomId\":\""
            + harborRoom
            + "\",\"startAt\":\""
            + slots.get(2)
            + "\"}";
    var repeated =
        observe(
            evidence,
            alex,
            "POST",
            root(harbor) + "/holds",
            HOLDS,
            duplicate,
            key(),
            CURRENT,
            Map.of());
    evidence.expect(
        AssertionKind.FORGED_FIELDS_REJECTED,
        fields.status() == 400
            && fields.code().equals("VALIDATION_ERROR")
            && repeated.status() == 400
            && repeated.code().equals("VALIDATION_ERROR"));
    evidence.expect(
        AssertionKind.UNTOUCHED_SLOT_AVAILABLE,
        availability(alex, harbor, harborRoom, date, slots.get(2)).equals("AVAILABLE"));
  }

  private void csrf(CaseEvidence evidence) throws IOException, InterruptedException {
    var missing =
        observe(
            evidence,
            alex,
            "POST",
            root(harbor) + "/holds",
            HOLDS,
            holdBody(alex, harborRoom, slots.get(2)),
            key(),
            NONE,
            Map.of());
    var invalid =
        observe(
            evidence,
            alex,
            "POST",
            root(harbor) + "/holds",
            HOLDS,
            holdBody(alex, harborRoom, slots.get(2)),
            key(),
            INVALID,
            Map.of());
    evidence.expect(
        AssertionKind.CSRF_DENIED,
        missing.status() == 403
            && missing.code().equals("CSRF_INVALID")
            && invalid.status() == 403
            && invalid.code().equals("CSRF_INVALID"));
    evidence.expect(
        AssertionKind.UNTOUCHED_SLOT_AVAILABLE,
        availability(alex, harbor, harborRoom, date, slots.get(2)).equals("AVAILABLE"));
  }

  private void idempotent(CaseEvidence evidence)
      throws IOException, InterruptedException, Prerequisite {
    String idempotency = key();
    String body = holdBody(alex, harborRoom, slots.get(2));
    ActorSession alternate = second == null ? alex : alex.at(second);
    var batch =
        parallel(
            6,
            i ->
                (i % 2 == 0 ? alex : alternate)
                    .request(
                        "POST", root(harbor) + "/holds", body, idempotency, CURRENT, Map.of()));
    List<ActorSession.Response> responses = batch.responses();
    for (var response : responses) evidence.observations.add(response.sanitized("POST", HOLDS));
    requireTransport(batch, evidence);
    boolean accepted = responses.stream().allMatch(r -> r.status() == 200 || r.status() == 201);
    evidence.expect(
        AssertionKind.ONE_RESOURCE_IDENTITY,
        accepted
            && responses.stream().map(r -> r.body().get("id")).distinct().count() == 1
            && responses.stream().filter(r -> r.status() == 201).count() == 1
            && responses.stream()
                .allMatch(
                    r ->
                        r.state().equals("HELD")
                            && Boolean.valueOf(r.status() == 200)
                                .equals(r.body().get("replayed"))));
    need(accepted);
    UUID id = responses.getFirst().id();
    evidence.expect(
        AssertionKind.ONE_LISTED_EFFECT,
        ownBookings(alex, harbor).stream().filter(r -> id.toString().equals(r.get("id"))).count()
            == 1);
    var cancelled =
        observe(
            evidence,
            alternate,
            "POST",
            reservation(harbor, id) + "/cancel",
            CANCEL,
            "{}",
            null,
            CURRENT,
            Map.of());
    var replay =
        observe(
            evidence,
            alex,
            "POST",
            root(harbor) + "/holds",
            HOLDS,
            body,
            idempotency,
            CURRENT,
            Map.of());
    evidence.expect(
        AssertionKind.REPLAY_RETURNS_CURRENT_STATE,
        cancelled.status() == 200
            && replay.status() == 200
            && id.equals(replay.id())
            && replay.state().equals("CANCELLED")
            && Boolean.TRUE.equals(replay.body().get("replayed")));
  }

  private void changedInput(CaseEvidence evidence)
      throws IOException, InterruptedException, Prerequisite {
    String idempotency = key();
    var first =
        observe(
            evidence,
            alex,
            "POST",
            root(harbor) + "/holds",
            HOLDS,
            holdBody(alex, harborRoom, slots.get(3)),
            idempotency,
            CURRENT,
            Map.of());
    need(first.status() == 201);
    var changed =
        observe(
            evidence,
            alex,
            "POST",
            root(harbor) + "/holds",
            HOLDS,
            holdBody(alex, harborRoom, slots.get(4)),
            idempotency,
            CURRENT,
            Map.of());
    evidence.expect(
        AssertionKind.CHANGED_INPUT_CONFLICT,
        changed.status() == 409 && changed.code().equals("IDEMPOTENCY_CONFLICT"));
    var after =
        observe(
            evidence,
            alex,
            "GET",
            reservation(harbor, first.id()),
            RESERVATION,
            null,
            null,
            NONE,
            Map.of());
    evidence.expect(
        AssertionKind.ORIGINAL_STATE_UNCHANGED, sameHeld(after, first.id(), harbor, alex.userId()));
    evidence.expect(
        AssertionKind.UNTOUCHED_SLOT_AVAILABLE,
        availability(alex, harbor, harborRoom, date, slots.get(4)).equals("AVAILABLE"));
    cancel(alex, harbor, first.id());
  }

  private void hoarding(CaseEvidence evidence)
      throws IOException, InterruptedException, Prerequisite {
    var secondHold =
        observe(
            evidence,
            alex,
            "POST",
            root(harbor) + "/holds",
            HOLDS,
            holdBody(alex, harborRoom, slots.get(5)),
            key(),
            CURRENT,
            Map.of());
    need(secondHold.status() == 201);
    var third =
        observe(
            evidence,
            alex,
            "POST",
            root(harbor) + "/holds",
            HOLDS,
            holdBody(alex, harborRoom, slots.get(6)),
            key(),
            CURRENT,
            Map.of());
    evidence.expect(
        AssertionKind.THIRD_HOLD_DENIED,
        third.status() == 409 && third.code().equals("QUOTA_EXCEEDED"));
    var firstAfter =
        observe(
            evidence,
            alex,
            "GET",
            reservation(harbor, expiryHold.id()),
            RESERVATION,
            null,
            null,
            NONE,
            Map.of());
    var secondAfter =
        observe(
            evidence,
            alex,
            "GET",
            reservation(harbor, secondHold.id()),
            RESERVATION,
            null,
            null,
            NONE,
            Map.of());
    evidence.expect(
        AssertionKind.TWO_EXISTING_HOLDS_RETAINED,
        sameHeld(firstAfter, expiryHold.id(), harbor, alex.userId())
            && sameHeld(secondAfter, secondHold.id(), harbor, alex.userId())
            && ownBookings(alex, harbor).stream()
                    .filter(r -> "HELD".equals(r.get("status")))
                    .count()
                == 2);
    cancel(alex, harbor, secondHold.id());
  }

  private void contention(CaseEvidence evidence)
      throws IOException, InterruptedException, Prerequisite {
    ActorSession alex2 = second == null ? alex : alex.at(second);
    ActorSession morgan2 = second == null ? morgan : morgan.at(second);
    List<ActorSession> clients = List.of(alex, morgan, alex2, morgan2);
    String body = holdBody(alex, harborRoom, slots.get(7));
    var batch =
        parallel(
            100,
            i ->
                clients
                    .get(i % 4)
                    .request("POST", root(harbor) + "/holds", body, key(), CURRENT, Map.of()));
    List<ActorSession.Response> responses = batch.responses();
    for (var response : responses) evidence.observations.add(response.sanitized("POST", HOLDS));
    var summary =
        HttpBurstSummary.classify(
            responses.stream().map(ActorSession.Response::status).toList(),
            responses.stream().map(ActorSession.Response::code).toList(),
            batch.transportErrors());
    evidence.assertions.addAll(summary.assertions());
    evidence.metrics.putAll(summary.metrics());
    evidence.metrics.put(Metric.HTTP_MAX_IN_FLIGHT, 100L);
    requireTransport(batch, evidence);
    var winners = responses.stream().filter(r -> r.status() == 201).toList();
    if (winners.size() == 1) {
      var winner = winners.getFirst();
      UUID owner = uuid(winner.body(), "ownerId");
      ActorSession actor = owner.equals(alex.userId()) ? alex : morgan;
      var read =
          observe(
              evidence,
              actor,
              "GET",
              reservation(harbor, winner.id()),
              RESERVATION,
              null,
              null,
              NONE,
              Map.of());
      long active = 0;
      for (var client : List.of(alex, morgan))
        active +=
            ownBookings(client, harbor).stream()
                .filter(
                    r ->
                        slots.get(7).equals(r.get("startAt"))
                            && harborRoom.toString().equals(r.get("roomId"))
                            && List.of("HELD", "CONFIRMED").contains(r.get("status")))
                .count();
      evidence.expect(
          AssertionKind.ONE_HTTP_ACTIVE_ASSIGNMENT,
          active == 1
              && sameHeld(read, winner.id(), harbor, owner)
              && availability(alex, harbor, harborRoom, date, slots.get(7)).equals("HELD"));
    } else evidence.expect(AssertionKind.ONE_HTTP_ACTIVE_ASSIGNMENT, false);
    var database =
        DatabaseContentionEvidence.read(options.contentionEvidence(), options.revision());
    evidence.assertions.addAll(database.assertions());
    evidence.metrics.putAll(database.metrics());
    if (database.outcome() == Outcome.INCOMPLETE)
      throw new Prerequisite(Reason.DATABASE_EVIDENCE_MISSING);
    if (database.outcome() == Outcome.ERROR)
      throw new IOException("Independent database evidence is invalid");
  }

  private void flood(CaseEvidence evidence) throws IOException, InterruptedException, Prerequisite {
    var hold =
        observe(
            evidence,
            jordan,
            "POST",
            root(harbor) + "/holds",
            HOLDS,
            holdBody(jordan, harborRoom, slots.get(8)),
            key(),
            CURRENT,
            Map.of());
    need(hold.status() == 201);
    var cancelled =
        observe(
            evidence,
            jordan,
            "POST",
            reservation(harbor, hold.id()) + "/cancel",
            CANCEL,
            "{}",
            null,
            CURRENT,
            Map.of());
    need(cancelled.status() == 200 && cancelled.state().equals("CANCELLED"));
    var batch =
        HttpRequestBatch.execute(
            130,
            8,
            i ->
                jordan.request(
                    "POST",
                    reservation(harbor, hold.id()) + "/cancel",
                    "{}",
                    null,
                    CURRENT,
                    Map.of(
                        "X-Forwarded-For",
                        "198.51.100." + (i % 240 + 1),
                        "Forwarded",
                        "for=198.51.100." + (i % 240 + 1))));
    List<ActorSession.Response> responses = batch.responses();
    for (var response : responses) evidence.observations.add(response.sanitized("POST", CANCEL));
    long limited =
        responses.stream()
            .filter(r -> r.status() == 429 && r.code().equals("RATE_LIMITED"))
            .count();
    evidence.metrics.put(Metric.FLOOD_ATTEMPTS, 130L);
    evidence.metrics.put(Metric.FLOOD_MAX_IN_FLIGHT, 8L);
    evidence.metrics.put(Metric.FLOOD_RATE_LIMITED, limited);
    requireTransport(batch, evidence);
    evidence.expect(
        AssertionKind.RATE_LIMIT_OBSERVED,
        limited > 0
            && responses.stream()
                .allMatch(
                    r ->
                        (r.status() == 200 && r.state().equals("CANCELLED"))
                            || (r.status() == 429 && r.code().equals("RATE_LIMITED"))));
    evidence.expect(
        AssertionKind.RETRY_AFTER_IS_BOUNDED,
        responses.stream()
            .filter(r -> r.status() == 429)
            .allMatch(r -> retryAfter(r.retryAfter())));
    var bypass =
        observe(
            evidence,
            jordan,
            "POST",
            reservation(harbor, hold.id()) + "/cancel",
            CANCEL,
            "{}",
            null,
            CURRENT,
            Map.of("X-Forwarded-For", "203.0.113.222", "Forwarded", "for=203.0.113.222"));
    Instant lastLimitedAt =
        responses.stream()
            .filter(r -> r.status() == 429)
            .map(ActorSession.Response::observedAt)
            .max(Instant::compareTo)
            .orElse(null);
    Outcome probeOutcome =
        RateWindowVerdict.classify(
            bypass.status(), bypass.code(), lastLimitedAt, bypass.observedAt());
    if (probeOutcome != Outcome.INCOMPLETE)
      evidence.expect(AssertionKind.FORWARDED_ADDRESS_CANNOT_BYPASS, probeOutcome == Outcome.PASS);
    var after =
        observe(
            evidence,
            jordan,
            "GET",
            reservation(harbor, hold.id()),
            RESERVATION,
            null,
            null,
            NONE,
            Map.of());
    evidence.expect(
        AssertionKind.FLOOD_STATE_UNCHANGED,
        after.status() == 200
            && after.id().equals(hold.id())
            && after.state().equals("CANCELLED")
            && ownBookings(jordan, harbor).stream()
                    .filter(r -> hold.id().toString().equals(r.get("id")))
                    .count()
                == 1);
    if (probeOutcome == Outcome.INCOMPLETE) throw new Prerequisite(Reason.FIXED_WINDOW_BOUNDARY);
  }

  private void expiry(CaseEvidence evidence)
      throws IOException, InterruptedException, Prerequisite {
    evidence.observations.add(expiryHold.sanitized("POST", HOLDS));
    Instant created = Instant.parse(String.valueOf(expiryHold.body().get("createdAt")));
    Instant expires = Instant.parse(String.valueOf(expiryHold.body().get("expiresAt")));
    Duration ttl = Duration.between(created, expires);
    evidence.metrics.put(Metric.HOLD_TTL_SECONDS, ttl.toSeconds());
    evidence.expect(AssertionKind.HOLD_TTL_IS_120_SECONDS, ttl.equals(Duration.ofSeconds(120)));
    long deadline = System.nanoTime() + Duration.ofSeconds(135).toNanos();
    ActorSession.Response read;
    do {
      read = alex.request("GET", reservation(harbor, expiryHold.id()), null, null, NONE, Map.of());
      need(read.status() == 200);
      Instant serverNow = Instant.parse(String.valueOf(read.body().get("serverNow")));
      if (!serverNow.isBefore(expires)) break;
      if (System.nanoTime() >= deadline) throw new Prerequisite(Reason.HOLD_DEADLINE_NOT_OBSERVED);
      Thread.sleep(Math.min(1000, Math.max(50, Duration.between(serverNow, expires).toMillis())));
    } while (true);
    evidence.observations.add(read.sanitized("GET", RESERVATION));
    evidence.expect(
        AssertionKind.OBSERVED_AFTER_HOLD_DEADLINE,
        !Instant.parse(String.valueOf(read.body().get("serverNow"))).isBefore(expires));
    var confirm =
        observe(
            evidence,
            alex,
            "POST",
            reservation(harbor, expiryHold.id()) + "/confirm",
            CONFIRM,
            "{}",
            key(),
            CURRENT,
            Map.of());
    evidence.expect(
        AssertionKind.EXPIRED_CONFIRMATION_DENIED,
        confirm.status() == 409 && confirm.code().equals("HOLD_EXPIRED"));
    var after =
        observe(
            evidence,
            alex,
            "GET",
            reservation(harbor, expiryHold.id()),
            RESERVATION,
            null,
            null,
            NONE,
            Map.of());
    evidence.expect(
        AssertionKind.EXPIRED_STATE_WITHOUT_CONFIRMATION,
        after.status() == 200
            && after.state().equals("EXPIRED")
            && after.body().get("confirmedAt") == null);
  }

  private void staleCancellation(CaseEvidence evidence)
      throws IOException, InterruptedException, Prerequisite {
    need(results.containsKey(CheckId.A08) && results.get(CheckId.A08).outcome() == Outcome.PASS);
    var replacement =
        observe(
            evidence,
            morgan,
            "POST",
            root(harbor) + "/holds",
            HOLDS,
            holdBody(morgan, harborRoom, slots.getFirst()),
            key(),
            CURRENT,
            Map.of());
    need(replacement.status() == 201);
    var stale =
        observe(
            evidence,
            alex,
            "POST",
            reservation(harbor, expiryHold.id()) + "/cancel",
            CANCEL,
            "{}",
            null,
            CURRENT,
            Map.of());
    evidence.expect(
        AssertionKind.STALE_CANCELLATION_DENIED,
        stale.status() == 409 && List.of("HOLD_EXPIRED", "INVALID_STATE").contains(stale.code()));
    var after =
        observe(
            evidence,
            morgan,
            "GET",
            reservation(harbor, replacement.id()),
            RESERVATION,
            null,
            null,
            NONE,
            Map.of());
    evidence.expect(
        AssertionKind.REPLACEMENT_STILL_OWNS_SLOT,
        sameHeld(after, replacement.id(), harbor, morgan.userId())
            && availability(morgan, harbor, harborRoom, date, slots.getFirst()).equals("HELD"));
    cancel(morgan, harbor, replacement.id());
  }

  private void sharedSession(CaseEvidence evidence)
      throws IOException, InterruptedException, Prerequisite {
    need(second != null);
    ActorSession other = riley.at(second);
    var shared =
        observe(evidence, other, "GET", "/api/session", SESSION, null, null, NONE, Map.of());
    Object user = shared.body().get("user");
    evidence.expect(
        AssertionKind.SESSION_SHARED_BETWEEN_PROCESSES,
        shared.status() == 200
            && Boolean.TRUE.equals(shared.body().get("authenticated"))
            && user instanceof Map<?, ?> map
            && riley.userId().toString().equals(map.get("id")));
    ActorSession retained = riley.retainedCookieSnapshot(options.app());
    var logout =
        observe(evidence, other, "POST", "/api/logout", LOGOUT, "{}", null, CURRENT, Map.of());
    var invalidated =
        observe(evidence, retained, "GET", "/api/session", SESSION, null, null, NONE, Map.of());
    evidence.expect(
        AssertionKind.LOGOUT_INVALIDATES_RETAINED_SESSION,
        logout.status() == 204
            && invalidated.status() == 200
            && Boolean.FALSE.equals(invalidated.body().get("authenticated")));
  }

  private void teachingControl(CaseEvidence evidence)
      throws IOException, InterruptedException, Prerequisite {
    if (options.training() == null) throw new Prerequisite(Reason.FIXTURE_UNAVAILABLE);
    var fixture = new ActorSession(options.training());
    fixture.verifyTarget();
    matchedTargets.add(options.training().expectedInstanceId());
    var leak =
        observe(
            evidence,
            fixture,
            "GET",
            "/api/workspaces/10000000-0000-0000-0000-000000000001/reservations/30000000-0000-0000-0000-000000000002",
            TRAINING_RESERVATION,
            null,
            null,
            NONE,
            Map.of("X-Training-Actor", "harbor-member"));
    evidence.expect(
        AssertionKind.FIXTURE_FOREIGN_OBJECT_LEAK_OBSERVED,
        leak.status() == 200
            && "10000000-0000-0000-0000-000000000002".equals(leak.body().get("workspaceId"))
            && "20000000-0000-0000-0000-000000000002".equals(leak.body().get("ownerId"))
            && leak.state().equals("CONFIRMED")
            && "REDUCED_SYNTHETIC_ACTOR".equals(leak.body().get("identityModel")));
  }

  private void runCase(CheckId id, CheckedCase action) {
    var evidence = new CaseEvidence(id);
    try {
      action.run(evidence);
      results.put(id, evidence.finish());
    } catch (Prerequisite exception) {
      results.put(
          id,
          evidence.assertions.stream().anyMatch(a -> !a.passed())
              ? evidence.finish()
              : evidence.incomplete(exception.reason));
    } catch (IOException | ExecutionException exception) {
      results.put(id, evidence.error(Reason.TRANSPORT_ERROR));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      results.put(id, evidence.error(Reason.TRANSPORT_ERROR));
    } catch (RuntimeException exception) {
      results.put(id, evidence.error(Reason.UNEXPECTED_RESPONSE));
    }
  }

  private void putError(CheckId id, Reason reason) {
    results.put(id, new CaseEvidence(id).error(reason));
  }

  private static ActorSession.Response observe(
      CaseEvidence evidence,
      ActorSession actor,
      String method,
      String path,
      SanitizedObservation.Endpoint endpoint,
      String body,
      String key,
      ActorSession.CsrfMode mode,
      Map<String, String> extra)
      throws IOException, InterruptedException {
    var response = actor.request(method, path, body, key, mode, extra);
    evidence.observations.add(response.sanitized(method, endpoint));
    return response;
  }

  private List<Map<String, Object>> ownBookings(ActorSession actor, UUID workspace)
      throws IOException, InterruptedException {
    var all = new ArrayList<Map<String, Object>>();
    for (int page = 0; page <= 20; page++) {
      var response =
          actor.request(
              "GET",
              root(workspace) + "/my-bookings?page=" + page + "&size=50",
              null,
              null,
              NONE,
              Map.of());
      if (response.status() != 200) throw new IOException("Own-booking observation unavailable");
      all.addAll(items(response.body()));
      if (Boolean.FALSE.equals(response.body().get("hasMore"))) return all;
    }
    throw new IOException("Bounded booking inventory exhausted");
  }

  private List<String> availableSlots(ActorSession actor, UUID workspace, UUID room, String day)
      throws IOException, InterruptedException, Prerequisite {
    var response =
        actor.request("GET", availabilityPath(workspace, room, day), null, null, NONE, Map.of());
    need(response.status() == 200);
    var output = new ArrayList<String>();
    for (var slot : listOfMaps(response.body().get("slots")))
      if ("AVAILABLE".equals(slot.get("availability")))
        output.add(String.valueOf(slot.get("startAt")));
    return List.copyOf(output);
  }

  private String availability(
      ActorSession actor, UUID workspace, UUID room, String day, String startAt)
      throws IOException, InterruptedException {
    var response =
        actor.request("GET", availabilityPath(workspace, room, day), null, null, NONE, Map.of());
    if (response.status() != 200) throw new IOException("Availability observation unavailable");
    return listOfMaps(response.body().get("slots")).stream()
        .filter(slot -> startAt.equals(slot.get("startAt")))
        .map(slot -> String.valueOf(slot.get("availability")))
        .findFirst()
        .orElseThrow(() -> new IOException("Expected slot missing"));
  }

  private static boolean sameHeld(
      ActorSession.Response response, UUID id, UUID workspace, UUID owner) {
    return response.status() == 200
        && id.toString().equals(response.body().get("id"))
        && workspace.toString().equals(response.body().get("workspaceId"))
        && owner.toString().equals(response.body().get("ownerId"))
        && response.state().equals("HELD")
        && response.body().get("confirmedAt") == null;
  }

  private static boolean absentObject(ActorSession.Response response) {
    return response.status() == 404
        && response.code().equals("NOT_FOUND")
        && List.of(
                "id",
                "ownerId",
                "workspaceId",
                "roomId",
                "startAt",
                "endAt",
                "expiresAt",
                "confirmedAt")
            .stream()
            .noneMatch(response.body()::containsKey);
  }

  private static boolean retryAfter(String value) {
    try {
      int seconds = Integer.parseInt(value);
      return seconds >= 1 && seconds <= 60;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private static String root(UUID workspace) {
    return "/api/workspaces/" + workspace;
  }

  private static String reservation(UUID workspace, UUID id) {
    return root(workspace) + "/reservations/" + id;
  }

  private static String availabilityPath(UUID workspace, UUID room, String day) {
    return root(workspace) + "/availability?roomId=" + room + "&date=" + day;
  }

  private static String holdBody(ActorSession actor, UUID room, String start) {
    return actor.json(Map.of("roomId", room.toString(), "startAt", start));
  }

  private static String key() {
    return "rehearsal:" + UUID.randomUUID();
  }

  private static UUID uuid(Map<String, Object> body, String field) {
    return UUID.fromString(String.valueOf(body.get(field)));
  }

  private static void need(boolean available) throws Prerequisite {
    if (!available) throw new Prerequisite(Reason.PREREQUISITE_MISSING);
  }

  private static Map<String, Object> firstItem(ActorSession.Response response) throws Prerequisite {
    need(response.status() == 200);
    var list = items(response.body());
    need(!list.isEmpty());
    return list.getFirst();
  }

  private static UUID firstEnabledRoom(ActorSession.Response response) throws Prerequisite {
    need(response.status() == 200);
    for (Map<String, Object> room : items(response.body()))
      if (Boolean.TRUE.equals(room.get("enabled"))) return uuid(room, "id");
    throw new Prerequisite(Reason.PREREQUISITE_MISSING);
  }

  private static List<Map<String, Object>> items(Map<String, Object> body) {
    return listOfMaps(body.get("items"));
  }

  private static List<Map<String, Object>> listOfMaps(Object value) {
    if (!(value instanceof List<?> list))
      throw new IllegalArgumentException("Expected collection view");
    var result = new ArrayList<Map<String, Object>>();
    for (Object element : list) {
      if (!(element instanceof Map<?, ?> map))
        throw new IllegalArgumentException("Expected object view");
      var typed = new java.util.LinkedHashMap<String, Object>();
      for (var entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String name))
          throw new IllegalArgumentException("Expected JSON property");
        typed.put(name, entry.getValue());
      }
      result.add(java.util.Collections.unmodifiableMap(typed));
    }
    return List.copyOf(result);
  }

  private static void cancel(ActorSession actor, UUID workspace, UUID id)
      throws IOException, InterruptedException {
    var response =
        actor.request(
            "POST", reservation(workspace, id) + "/cancel", "{}", null, CURRENT, Map.of());
    if (response.status() != 200 || !response.state().equals("CANCELLED"))
      throw new IOException("Owned fixture cleanup failed");
  }

  private static HttpRequestBatch.Result parallel(int count, HttpRequestBatch.Operation request)
      throws InterruptedException {
    return HttpRequestBatch.execute(count, count, request);
  }

  private static void requireTransport(HttpRequestBatch.Result batch, CaseEvidence evidence)
      throws IOException {
    evidence.metrics.put(
        Metric.HTTP_ATTEMPTS, (long) batch.responses().size() + batch.transportErrors());
    evidence.metrics.put(Metric.HTTP_RESPONSES, (long) batch.responses().size());
    evidence.metrics.put(Metric.HTTP_TRANSPORT_ERRORS, (long) batch.transportErrors());
    if (batch.transportErrors() > 0)
      throw new IOException("Concurrent HTTP outcomes include transport failures");
  }

  @FunctionalInterface
  private interface CheckedCase {
    void run(CaseEvidence evidence)
        throws IOException, InterruptedException, Prerequisite, ExecutionException;
  }

  private static final class Prerequisite extends Exception {
    private static final long serialVersionUID = 1L;
    private final Reason reason;

    Prerequisite(Reason reason) {
      super("Required synthetic observation unavailable");
      this.reason = reason;
    }
  }
}
