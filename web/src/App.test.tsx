import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App";

// Synthetic HTTP fixtures exercise the browser boundary, never real availability.
const now = "2026-11-18T10:00:00Z";
const harbor = {
  id: "harbor",
  name: "Harbor Works",
  timeZone: "Europe/Budapest",
  role: "MEMBER",
};
const studio = {
  id: "studio",
  name: "Studio North",
  timeZone: "Europe/Budapest",
  role: "WORKSPACE_ADMIN",
};
const oak = {
  id: "oak",
  workspaceId: "harbor",
  name: "Oak",
  description: "A quiet room.",
  enabled: true,
};
const ash = {
  id: "ash",
  workspaceId: "studio",
  name: "Ash",
  description: "Space to collaborate.",
  enabled: true,
};
const held = {
  id: "reservation-one",
  workspaceId: "harbor",
  roomId: "oak",
  ownerId: "alex",
  startAt: "2026-11-18T11:00:00Z",
  endAt: "2026-11-18T11:30:00Z",
  status: "HELD",
  createdAt: now,
  expiresAt: "2026-11-18T10:02:00Z",
  confirmedAt: null,
  serverNow: now,
  replayed: false,
};
const json = (
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });

interface FixtureOptions {
  anonymous?: boolean;
  conflict?: boolean;
  expired?: boolean;
  rateLimited?: boolean;
  admin?: boolean;
}

function server(options: FixtureOptions = {}) {
  let loggedIn = !options.anonymous;
  let csrfReads = 0;
  let booking: Record<string, unknown> | undefined;
  let conflict = false;
  const rooms = [options.admin ? { ...oak } : oak];
  const requests: { url: string; init: RequestInit }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const url = String(input);
      const method = init.method ?? "GET";
      requests.push({ url, init });
      if (url === "/api/csrf")
        return json({
          headerName: "X-CSRF-TOKEN",
          token: `synthetic-csrf-${++csrfReads}`,
        });
      if (url === "/api/session" && method === "GET")
        return json(
          loggedIn
            ? {
                authenticated: true,
                user: { id: "alex", displayName: "Alex" },
                serverNow: now,
              }
            : { authenticated: false, serverNow: now },
        );
      if (url === "/api/session" && method === "POST") {
        const headers = new Headers(init.headers);
        if (
          headers.get("X-CSRF-TOKEN") !== "synthetic-csrf-1" ||
          String(init.body) !== "username=alex&password=synthetic-password"
        ) {
          return json(
            {
              status: 403,
              code: "CSRF_INVALID",
              detail: "Please refresh and try again.",
              requestId: "synthetic",
            },
            403,
          );
        }
        loggedIn = true;
        return json({
          authenticated: true,
          user: { id: "alex", displayName: "Alex" },
          serverNow: now,
        });
      }
      if (url === "/api/logout") {
        loggedIn = false;
        return new Response(null, { status: 204 });
      }
      if (url === "/api/workspaces")
        return json({
          items: [
            options.admin ? { ...harbor, role: "WORKSPACE_ADMIN" } : harbor,
            studio,
          ],
        });
      if (url === "/api/workspaces/harbor/rooms" && method === "GET")
        return json({ items: rooms });
      if (url === "/api/workspaces/studio/rooms") return json({ items: [ash] });
      if (
        url.startsWith("/api/workspaces/") &&
        url.includes("/availability?")
      ) {
        const studioScope = url.includes("/studio/");
        return json({
          date: "2026-11-18",
          timeZone: "Europe/Budapest",
          serverNow: now,
          room: studioScope
            ? { id: "ash", name: "Ash", enabled: true }
            : { id: "oak", name: "Oak", enabled: true },
          slots: [
            {
              startAt: held.startAt,
              endAt: held.endAt,
              availability: conflict ? "BOOKED" : "AVAILABLE",
              holdExpiresAt: null,
            },
          ],
        });
      }
      if (url.endsWith("/holds")) {
        const headers = new Headers(init.headers);
        if (!headers.get("Idempotency-Key") || !headers.get("X-CSRF-TOKEN")) {
          return json(
            {
              code: "VALIDATION_ERROR",
              detail: "Required request headers missing.",
            },
            400,
          );
        }
        if (options.rateLimited)
          return json(
            {
              code: "RATE_LIMITED",
              status: 429,
              detail: "Too many requests.",
              requestId: "limit",
            },
            429,
            { "Retry-After": "12" },
          );
        if (options.conflict) {
          conflict = true;
          return json(
            {
              code: "SLOT_UNAVAILABLE",
              status: 409,
              detail: "This slot is no longer available. Choose another time.",
              requestId: "conflict",
            },
            409,
          );
        }
        booking = options.expired ? { ...held, expiresAt: now } : { ...held };
        return json(booking, 201);
      }
      if (url.endsWith("/confirm")) {
        booking = { ...held, status: "CONFIRMED", confirmedAt: now };
        return json(booking);
      }
      if (url.endsWith("/cancel")) {
        booking = { ...held, status: "CANCELLED" };
        return json(booking);
      }
      if (url.includes("/my-bookings?"))
        return json({
          items: booking ? [booking] : [],
          page: 0,
          size: 20,
          hasMore: false,
        });
      if (url.endsWith("/reservations/reservation-one"))
        return json(booking ?? held);
      if (url.includes("/activity?"))
        return json({
          items: [
            {
              id: "event-one",
              kind: "CONFIRMED",
              reservationId: "reservation-one",
              actorId: "alex",
              roomId: "oak",
              occurredAt: now,
            },
          ],
          page: 0,
          size: 20,
          hasMore: false,
        });
      if (url === "/api/workspaces/harbor/rooms" && method === "POST") {
        const input = JSON.parse(String(init.body));
        const created = { ...oak, ...input, id: "created-room" };
        rooms.push(created);
        return json(created, 201);
      }
      if (url.includes("/rooms/") && method === "PATCH")
        return json({ ...oak, ...JSON.parse(String(init.body)) });
      throw new Error(`Unmatched synthetic request: ${method} ${url}`);
    }),
  );
  return requests;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function selectAvailableSlot() {
  const slot = await screen.findByRole("button", {
    name: /12:00.*12:30.*available/i,
  });
  await userEvent.click(slot);
}

describe("Slotkeeper product journeys with synthetic HTTP fixtures", () => {
  it("logs in with a CSRF token and refreshes it before any booking mutation", async () => {
    const requests = server({ anonymous: true });
    render(<App />);
    await userEvent.type(await screen.findByLabelText("Username"), "alex");
    await userEvent.type(
      screen.getByLabelText("Password"),
      "synthetic-password",
    );
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await selectAvailableSlot();
    await userEvent.click(
      screen.getByRole("button", { name: "Hold this time" }),
    );
    await screen.findByRole("button", { name: "Confirm booking" });
    const mutation = requests.find(({ url }) => url.endsWith("/holds"))!;
    expect(new Headers(mutation.init.headers).get("X-CSRF-TOKEN")).toBe(
      "synthetic-csrf-2",
    );
    expect(JSON.parse(String(mutation.init.body))).toEqual({
      roomId: "oak",
      startAt: held.startAt,
    });
    expect(mutation.init.credentials).toBe("same-origin");
  });

  it("clears a held reservation and changes role navigation when the workspace changes", async () => {
    server();
    render(<App />);
    await selectAvailableSlot();
    await userEvent.click(
      screen.getByRole("button", { name: "Hold this time" }),
    );
    await screen.findByRole("button", { name: "Confirm booking" });
    expect(
      screen.queryByRole("button", { name: "Manage rooms" }),
    ).not.toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Workspace"), "studio");
    await screen.findByRole("button", { name: /Ash.*Space to collaborate/i });
    expect(
      screen.queryByRole("button", { name: "Confirm booking" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Manage rooms" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("Oak", { selector: ".summary-room" }),
    ).not.toBeInTheDocument();
  });

  it("refreshes availability and clears the selected time after a last-slot conflict", async () => {
    server({ conflict: true });
    render(<App />);
    await selectAvailableSlot();
    await userEvent.click(
      screen.getByRole("button", { name: "Hold this time" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "no longer available",
    );
    expect(
      await screen.findByRole("button", { name: /12:00.*12:30.*booked/i }),
    ).toBeDisabled();
    expect(
      screen.queryByRole("button", { name: "Hold this time" }),
    ).not.toBeInTheDocument();
  });

  it("never allows confirmation when the authoritative hold deadline has passed", async () => {
    server({ expired: true });
    render(<App />);
    await selectAvailableSlot();
    await userEvent.click(
      screen.getByRole("button", { name: "Hold this time" }),
    );
    expect(
      await screen.findByText(/Your hold has expired/i),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Confirm booking" }),
    ).not.toBeInTheDocument();
  });

  it("completes hold, confirmation, personal booking inspection and cancellation", async () => {
    server();
    render(<App />);
    await selectAvailableSlot();
    await userEvent.click(
      screen.getByRole("button", { name: "Hold this time" }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Confirm booking" }),
    );
    expect(await screen.findByText("Your room is booked.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "My bookings" }));
    const card = await screen.findByRole("article", { name: /Oak.*12:00/i });
    expect(within(card).getByText("Confirmed")).toBeInTheDocument();
    await userEvent.click(
      within(card).getByRole("button", { name: "View booking" }),
    );
    const dialog = await screen.findByRole("dialog", {
      name: "Booking details",
    });
    expect(within(dialog).getByText(/reservation-one/)).toBeInTheDocument();
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Close details" }),
    );
    await userEvent.click(
      within(card).getByRole("button", { name: "Cancel booking" }),
    );
    const confirmation = await screen.findByRole("dialog", {
      name: "Cancel this booking?",
    });
    await userEvent.click(
      within(confirmation).getByRole("button", { name: "Cancel booking" }),
    );
    expect(await within(card).findByText("Cancelled")).toBeInTheDocument();
  });

  it("shows an actionable rate-limit delay without clearing the selected time", async () => {
    server({ rateLimited: true });
    render(<App />);
    await selectAvailableSlot();
    await userEvent.click(
      screen.getByRole("button", { name: "Hold this time" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(/12 seconds/i);
    expect(
      screen.getByRole("button", { name: "Hold this time" }),
    ).toBeDisabled();
  });

  it("keeps room administration scoped and renders hostile-looking names literally", async () => {
    const requests = server({ admin: true });
    render(<App />);
    await userEvent.click(
      await screen.findByRole("button", { name: "Manage rooms" }),
    );
    await userEvent.click(screen.getByRole("button", { name: "Add room" }));
    await userEvent.type(
      screen.getByRole("textbox", { name: "Room name" }),
      "<img src=x onerror=alert(1)>",
    );
    await userEvent.type(
      screen.getByRole("textbox", { name: "Description" }),
      "Synthetic test room",
    );
    await userEvent.click(screen.getByRole("button", { name: "Create room" }));
    expect(
      await screen.findByRole("heading", {
        name: "<img src=x onerror=alert(1)>",
      }),
    ).toBeInTheDocument();
    expect(document.querySelector("img")).toBeNull();
    const request = requests.find(
      ({ init, url }) => init.method === "POST" && url.endsWith("/rooms"),
    )!;
    expect(JSON.parse(String(request.init.body))).toEqual({
      name: "<img src=x onerror=alert(1)>",
      description: "Synthetic test room",
    });
    await userEvent.click(screen.getByRole("button", { name: "Activity" }));
    expect(await screen.findByText("Booking confirmed")).toBeInTheDocument();
  });

  it("offers a retry when the service cannot be reached and invents no rooms", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("synthetic network outage")),
    );
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      /cannot reach Slotkeeper/i,
    );
    expect(
      screen.getByRole("button", { name: "Try again" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("Oak")).not.toBeInTheDocument();
  });

  it("returns to sign-in after a session expires during a workspace request", async () => {
    server();
    render(<App />);
    await selectAvailableSlot();
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        json(
          {
            status: 401,
            code: "AUTHENTICATION_REQUIRED",
            detail: "Sign in to continue.",
            requestId: "expired-session",
          },
          401,
        ),
      ),
    );
    await userEvent.click(screen.getByRole("button", { name: "My bookings" }));
    expect(await screen.findByLabelText("Username")).toBeInTheDocument();
    expect(screen.getByText(/Your session has ended/i)).toBeInTheDocument();
  });

  it("ignores an old workspace response after moving to another workspace", async () => {
    const requests = server();
    const originalFetch = globalThis.fetch;
    let release: ((value: Response) => void) | undefined;
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        if (String(input).startsWith("/api/workspaces/harbor/availability"))
          return new Promise<Response>((resolve) => {
            release = resolve;
          });
        return originalFetch(input, init);
      }),
    );
    render(<App />);
    await waitFor(() => expect(release).toBeDefined());
    await userEvent.selectOptions(screen.getByLabelText("Workspace"), "studio");
    await screen.findByRole("button", { name: /Ash.*Space to collaborate/i });
    await act(async () => {
      release!(
        json({
          date: "2026-11-18",
          timeZone: "Europe/Budapest",
          serverNow: now,
          room: { id: "oak", name: "Old room", enabled: true },
          slots: [],
        }),
      );
    });
    expect(screen.queryByText("Old room")).not.toBeInTheDocument();
    expect(requests.some(({ url }) => url.includes("/studio/rooms"))).toBe(
      true,
    );
  });

  it("reuses the same hold identity after an uncertain network response", async () => {
    server();
    const originalFetch = globalThis.fetch;
    const keys: (string | null)[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        if (String(input).endsWith("/holds")) {
          keys.push(new Headers(init?.headers).get("Idempotency-Key"));
          if (keys.length === 1)
            return Promise.reject(new TypeError("Synthetic lost response"));
        }
        return originalFetch(input, init);
      }),
    );
    render(<App />);
    await selectAvailableSlot();
    await userEvent.click(
      screen.getByRole("button", { name: "Hold this time" }),
    );
    await screen.findByRole("alert");
    await userEvent.click(
      screen.getByRole("button", { name: "Hold this time" }),
    );
    await screen.findByRole("button", { name: "Confirm booking" });
    expect(keys).toHaveLength(2);
    expect(keys[0]).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/);
    expect(keys[1]).toBe(keys[0]);
  });

  it("reuses the same confirmation identity after an uncertain network response", async () => {
    server();
    const originalFetch = globalThis.fetch;
    const keys: (string | null)[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        if (String(input).endsWith("/confirm")) {
          keys.push(new Headers(init?.headers).get("Idempotency-Key"));
          if (keys.length === 1)
            return Promise.reject(new TypeError("Synthetic lost response"));
        }
        return originalFetch(input, init);
      }),
    );
    render(<App />);
    await selectAvailableSlot();
    await userEvent.click(
      screen.getByRole("button", { name: "Hold this time" }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Confirm booking" }),
    );
    await screen.findByRole("alert");
    await userEvent.click(
      screen.getByRole("button", { name: "Confirm booking" }),
    );
    await screen.findByText("Your room is booked.");
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it("updates the booking summary after cancellation from My bookings", async () => {
    server();
    render(<App />);
    await selectAvailableSlot();
    await userEvent.click(
      screen.getByRole("button", { name: "Hold this time" }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Confirm booking" }),
    );
    await screen.findByText("Your room is booked.");
    await userEvent.click(screen.getByRole("button", { name: "My bookings" }));
    const card = await screen.findByRole("article", { name: /Oak.*12:00/i });
    await userEvent.click(
      within(card).getByRole("button", { name: "Cancel booking" }),
    );
    const dialog = await screen.findByRole("dialog", {
      name: "Cancel this booking?",
    });
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Cancel booking" }),
    );
    await within(card).findByText("Cancelled");
    await userEvent.click(screen.getByRole("button", { name: "Book a room" }));
    expect(
      await screen.findByText("Your booking has been cancelled."),
    ).toBeInTheDocument();
    expect(screen.queryByText("Your room is booked.")).not.toBeInTheDocument();
  });

  it("keeps the rate-limit pause when the user changes the selected day", async () => {
    server({ rateLimited: true });
    render(<App />);
    await selectAvailableSlot();
    await userEvent.click(
      screen.getByRole("button", { name: "Hold this time" }),
    );
    await screen.findByRole("alert");
    await userEvent.click(screen.getByRole("button", { name: "Next day" }));
    await selectAvailableSlot();
    expect(
      screen.getByRole("button", { name: "Hold this time" }),
    ).toBeDisabled();
  });

  it("labels the selected day's time offset across a daylight-saving boundary", async () => {
    server();
    const originalFetch = globalThis.fetch;
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url === "/api/session")
          return Promise.resolve(
            json({
              authenticated: true,
              user: { id: "alex", displayName: "Alex" },
              serverNow: "2026-10-24T10:00:00Z",
            }),
          );
        if (url.includes("/availability?")) {
          const day = new URL(url, "http://synthetic.invalid").searchParams.get(
            "date",
          );
          return Promise.resolve(
            json({
              date: day,
              timeZone: "Europe/Budapest",
              serverNow: "2026-10-24T10:00:00Z",
              room: { id: "oak", name: "Oak", enabled: true },
              slots: [
                {
                  startAt: `${day}T11:00:00Z`,
                  endAt: `${day}T11:30:00Z`,
                  availability: "AVAILABLE",
                  holdExpiresAt: null,
                },
              ],
            }),
          );
        }
        return originalFetch(input, init);
      }),
    );
    render(<App />);
    await screen.findByText("GMT+2");
    await userEvent.click(screen.getByRole("button", { name: "Next day" }));
    expect(await screen.findByText("GMT+1")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /12:00.*12:30.*available/i }),
    ).toBeInTheDocument();
  });

  it("ignores a late booking detail response after a different booking is opened", async () => {
    server();
    const originalFetch = globalThis.fetch;
    const second = {
      ...held,
      id: "reservation-two",
      startAt: "2026-11-18T12:00:00Z",
      endAt: "2026-11-18T12:30:00Z",
    };
    let release: ((value: Response) => void) | undefined;
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.includes("/my-bookings?"))
          return Promise.resolve(
            json({ items: [held, second], page: 0, size: 20, hasMore: false }),
          );
        if (url.endsWith("/reservations/reservation-one"))
          return new Promise<Response>((resolve) => {
            release = resolve;
          });
        if (url.endsWith("/reservations/reservation-two"))
          return Promise.resolve(json(second));
        return originalFetch(input, init);
      }),
    );
    render(<App />);
    await selectAvailableSlot();
    await userEvent.click(screen.getByRole("button", { name: "My bookings" }));
    const firstCard = await screen.findByRole("article", {
      name: /Oak.*12:00/i,
    });
    await userEvent.click(
      within(firstCard).getByRole("button", { name: "View booking" }),
    );
    await screen.findByRole("dialog", { name: "Booking details" });
    await userEvent.click(
      screen.getByRole("button", { name: "Close details" }),
    );
    const secondCard = screen.getByRole("article", { name: /Oak.*13:00/i });
    await userEvent.click(
      within(secondCard).getByRole("button", { name: "View booking" }),
    );
    const dialog = await screen.findByRole("dialog", {
      name: "Booking details",
    });
    await within(dialog).findByText("reservation-two");
    await act(async () => {
      release!(json(held));
    });
    expect(within(dialog).getByText("reservation-two")).toBeInTheDocument();
    expect(
      within(dialog).queryByText("reservation-one"),
    ).not.toBeInTheDocument();
  });
});
