package io.github.gbalazsfalvi.slotkeeper.verification;

/** Frozen mandatory rehearsal cases and explicitly separate supporting checks. */
enum CheckId {
  A01("Anonymous booking access"),
  A02("Foreign workspace object lookup"),
  A03("Another member's cancellation"),
  A04("Role denial and forged authority"),
  A05("Missing and invalid CSRF"),
  A06("Concurrent idempotent retries"),
  A07("Changed input under the same key"),
  A08("Confirmation after the real hold deadline"),
  A09("Stale cancellation after reassignment"),
  A10("Hoarding beyond two active holds"),
  A11("Mutation flood with forged forwarding"),
  A12("Separate HTTP burst and two-process database contention"),
  T01("Isolated synthetic missing-scope positive control"),
  S01("Shared session and logout across application processes"),
  S02("Independent read-only state check of the owned Compose database");

  private final String title;

  CheckId(String title) {
    this.title = title;
  }

  /** Returns the fixed English title, never a value copied from a response. */
  public String title() {
    return title;
  }
}
