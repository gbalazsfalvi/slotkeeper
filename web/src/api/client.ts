import type {
  ActivityEvent,
  Availability,
  CsrfToken,
  Page,
  Problem,
  Reservation,
  Room,
  RoomInput,
  SessionView,
  Workspace,
} from "./contracts";

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId?: string;
  readonly retryAfter: number;

  constructor(problem: Problem, retryAfter = 0) {
    super(
      problem.detail || "The request could not be completed. Please try again.",
    );
    this.name = "ApiError";
    this.status = problem.status;
    this.code = problem.code;
    this.requestId = problem.requestId;
    this.retryAfter = retryAfter;
  }
}

export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError))
    return "We cannot reach Slotkeeper right now. Check your connection and try again.";
  switch (error.code) {
    case "INVALID_CREDENTIALS":
      return "The username or password is incorrect. Please try again.";
    case "AUTHENTICATION_REQUIRED":
      return "Your session has ended. Sign in to continue.";
    case "FORBIDDEN":
      return "You do not have permission to make this change in this workspace.";
    case "CSRF_INVALID":
      return "Your page needs to be refreshed before this change can be made. Refresh the page and try again.";
    case "NOT_FOUND":
      return "This item is no longer available in this workspace. Refresh to see the latest information.";
    case "SLOT_UNAVAILABLE":
      return "This slot is no longer available. Choose another time.";
    case "HOLD_EXPIRED":
      return "Your hold has expired. Choose an available time to start again.";
    case "QUOTA_EXCEEDED":
      return error.message;
    case "RATE_LIMITED":
      return `Please pause and try again in ${Math.max(1, error.retryAfter)} seconds.`;
    case "RESOURCE_BUSY":
      return "This room is being updated. Please wait a moment and try again.";
    case "SERVICE_UNAVAILABLE":
      return "Slotkeeper is temporarily unavailable. Please try again shortly.";
    case "NETWORK_ERROR":
      return "We cannot reach Slotkeeper right now. Check your connection and try again.";
    case "IDEMPOTENCY_CONFLICT":
      return "This request could not be safely repeated. Refresh the booking details before trying again.";
    case "INVALID_STATE":
      return "This booking has changed. Refresh its details to see the current status.";
    case "VALIDATION_ERROR":
      return error.message;
    default:
      return "The request could not be completed. Please try again.";
  }
}

/** Same-origin, cookie-authenticated transport. CSRF tokens remain in memory. */
export class ApiClient {
  private csrf: CsrfToken | undefined;
  onSessionExpired: (() => void) | undefined;

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    try {
      const response = await fetch(`/api${path}`, {
        ...init,
        credentials: "same-origin",
        cache: "no-store",
        signal: init.signal
          ? AbortSignal.any([init.signal, AbortSignal.timeout(15_000)])
          : AbortSignal.timeout(15_000),
        headers: { Accept: "application/json", ...init.headers },
      });
      if (!response.ok) {
        const fallback: Problem = {
          status: response.status,
          code:
            response.status === 401
              ? "AUTHENTICATION_REQUIRED"
              : "SERVICE_UNAVAILABLE",
        };
        const body = (await response
          .json()
          .catch(() => fallback)) as Partial<Problem>;
        const problem: Problem = {
          ...fallback,
          ...body,
          status: response.status,
        };
        const retryAfter = Number(response.headers.get("Retry-After"));
        if (response.status === 401 && problem.code !== "INVALID_CREDENTIALS") {
          this.csrf = undefined;
          this.onSessionExpired?.();
        }
        throw new ApiError(
          problem,
          Number.isFinite(retryAfter) && retryAfter > 0
            ? Math.ceil(retryAfter)
            : 1,
        );
      }
      if (response.status === 204) return undefined as T;
      return (await response.json()) as T;
    } catch (error) {
      if (
        error instanceof ApiError ||
        (error instanceof DOMException && error.name === "AbortError")
      )
        throw error;
      throw new ApiError({ status: 0, code: "NETWORK_ERROR" });
    }
  }

  async refreshCsrf(): Promise<void> {
    this.csrf = await this.request<CsrfToken>("/csrf");
  }

  private async mutate<T>(
    path: string,
    body: unknown,
    options: { method?: "POST" | "PATCH"; key?: string } = {},
  ): Promise<T> {
    if (!this.csrf) await this.refreshCsrf();
    return this.request<T>(path, {
      method: options.method ?? "POST",
      headers: {
        "Content-Type": "application/json",
        [this.csrf!.headerName]: this.csrf!.token,
        ...(options.key ? { "Idempotency-Key": options.key } : {}),
      },
      body: JSON.stringify(body),
    });
  }

  session = (signal?: AbortSignal) =>
    this.request<SessionView>("/session", { signal });
  workspaces = (signal?: AbortSignal) =>
    this.request<{ items: Workspace[] }>("/workspaces", { signal });

  async login(username: string, password: string): Promise<SessionView> {
    await this.refreshCsrf();
    const session = await this.request<SessionView>("/session", {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        [this.csrf!.headerName]: this.csrf!.token,
      },
      body: new URLSearchParams({ username, password }).toString(),
    });
    this.csrf = undefined;
    await this.refreshCsrf();
    return session;
  }

  async logout(): Promise<void> {
    await this.mutate<void>("/logout", {});
    this.csrf = undefined;
  }

  rooms = (workspaceId: string, signal?: AbortSignal) =>
    this.request<{ items: Room[] }>(
      `/workspaces/${encodeURIComponent(workspaceId)}/rooms`,
      { signal },
    );
  availability = (
    workspaceId: string,
    roomId: string,
    date: string,
    signal?: AbortSignal,
  ) =>
    this.request<Availability>(
      `/workspaces/${encodeURIComponent(workspaceId)}/availability?${new URLSearchParams({ roomId, date })}`,
      { signal },
    );
  hold = (workspaceId: string, roomId: string, startAt: string, key: string) =>
    this.mutate<Reservation>(
      `/workspaces/${encodeURIComponent(workspaceId)}/holds`,
      { roomId, startAt },
      { key },
    );
  confirm = (workspaceId: string, reservationId: string, key: string) =>
    this.mutate<Reservation>(
      `/workspaces/${encodeURIComponent(workspaceId)}/reservations/${encodeURIComponent(reservationId)}/confirm`,
      {},
      { key },
    );
  cancel = (workspaceId: string, reservationId: string) =>
    this.mutate<Reservation>(
      `/workspaces/${encodeURIComponent(workspaceId)}/reservations/${encodeURIComponent(reservationId)}/cancel`,
      {},
    );
  reservation = (
    workspaceId: string,
    reservationId: string,
    signal?: AbortSignal,
  ) =>
    this.request<Reservation>(
      `/workspaces/${encodeURIComponent(workspaceId)}/reservations/${encodeURIComponent(reservationId)}`,
      { signal },
    );
  myBookings = (workspaceId: string, page: number, signal?: AbortSignal) =>
    this.request<Page<Reservation>>(
      `/workspaces/${encodeURIComponent(workspaceId)}/my-bookings?page=${page}&size=20`,
      { signal },
    );
  activity = (workspaceId: string, page: number, signal?: AbortSignal) =>
    this.request<Page<ActivityEvent>>(
      `/workspaces/${encodeURIComponent(workspaceId)}/activity?page=${page}&size=20`,
      { signal },
    );
  createRoom = (workspaceId: string, input: RoomInput) =>
    this.mutate<Room>(
      `/workspaces/${encodeURIComponent(workspaceId)}/rooms`,
      input,
    );
  updateRoom = (
    workspaceId: string,
    roomId: string,
    input: Partial<RoomInput & { enabled: boolean }>,
  ) =>
    this.mutate<Room>(
      `/workspaces/${encodeURIComponent(workspaceId)}/rooms/${encodeURIComponent(roomId)}`,
      input,
      { method: "PATCH" },
    );
}

export const operationKey = () => crypto.randomUUID();
