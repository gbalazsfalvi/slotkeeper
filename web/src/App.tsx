import { useState } from "react";
import {
  ArrowRight,
  Building2,
  CalendarDays,
  Check,
  Clock3,
  DoorOpen,
  ListChecks,
  LogOut,
  PanelTop,
} from "lucide-react";
import type { FormEvent } from "react";
import type { Reservation, Workspace } from "./api/contracts";
import { SessionProvider, useSession } from "./session/SessionProvider";
import { WorkspacePicker } from "./workspaces/WorkspacePicker";
import { AvailabilityView } from "./reservations/AvailabilityView";
import { MyBookings } from "./reservations/MyBookings";
import { RoomsView } from "./admin/RoomsView";
import { ActivityView } from "./admin/ActivityView";
import { EmptyState, ErrorNotice, Loading } from "./shared/Feedback";
import { useCooldown, useResource } from "./shared/hooks";

function Brand() {
  return (
    <a className="brand" href="/" aria-label="Slotkeeper home">
      <span className="brand-mark" aria-hidden="true">
        <PanelTop size={22} strokeWidth={1.8} />
      </span>
      <span>
        slotkeeper<span className="brand-period">.</span>
      </span>
    </a>
  );
}

function SignIn() {
  const { login, ended } = useSession();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const cooldown = useCooldown(error);
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy || cooldown > 0) return;
    setBusy(true);
    setError(undefined);
    try {
      await login(username.trim(), password);
    } catch (failure) {
      setError(failure);
      setPassword("");
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="signin-page">
      <header className="signin-header">
        <Brand />
        <span className="environment-label">
          <span /> Local reference
        </span>
      </header>
      <main className="signin-main" id="main-content">
        <section className="signin-form-panel">
          <div className="section-kicker">
            <span className="tiny-rule" /> YOUR WORKSPACE, IN ORDER
          </div>
          <h1>Welcome back.</h1>
          <p className="lead-copy">
            Sign in to find a room and make a booking.
          </p>
          {ended && (
            <div className="session-notice" role="status">
              Your session has ended. Sign in to continue.
            </div>
          )}
          <ErrorNotice error={error} />
          <form
            onSubmit={(event) => {
              void submit(event);
            }}
            className="signin-form"
          >
            <label htmlFor="username">Username</label>
            <input
              id="username"
              name="username"
              autoComplete="username"
              maxLength={80}
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              required
              autoFocus
            />
            <label htmlFor="password">Password</label>
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              maxLength={256}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
            />
            <button
              className="button button-primary signin-submit"
              disabled={busy || cooldown > 0}
              type="submit"
            >
              {busy
                ? "Signing in…"
                : cooldown > 0
                  ? `Try again in ${cooldown}s`
                  : "Sign in"}
              <ArrowRight size={18} aria-hidden="true" />
            </button>
          </form>
          <p className="form-footnote">
            <Clock3 size={15} aria-hidden="true" /> You will be signed out after
            30 minutes of inactivity.
          </p>
        </section>
        <aside className="fixture-panel" aria-label="Local reference accounts">
          <div className="fixture-icon">
            <Building2 size={26} strokeWidth={1.5} aria-hidden="true" />
          </div>
          <h2>A place to try it out.</h2>
          <p>
            This local reference uses synthetic people and workspaces. Choose an
            account to explore the booking experience.
          </p>
          <div className="fixture-accounts">
            <div>
              <span className="workspace-monogram">H</span>
              <div>
                <strong>Harbor Works</strong>
                <p>
                  <button
                    type="button"
                    onClick={() => {
                      setUsername("alex");
                      setPassword("Slotkeeper-Local-Only!");
                    }}
                  >
                    alex <span>Member</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setUsername("morgan");
                      setPassword("Slotkeeper-Local-Only!");
                    }}
                  >
                    morgan <span>Admin</span>
                  </button>
                </p>
              </div>
            </div>
            <div>
              <span className="workspace-monogram studio-monogram">S</span>
              <div>
                <strong>Studio North</strong>
                <p>
                  <button
                    type="button"
                    onClick={() => {
                      setUsername("riley");
                      setPassword("Slotkeeper-Local-Only!");
                    }}
                  >
                    riley <span>Member</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setUsername("jordan");
                      setPassword("Slotkeeper-Local-Only!");
                    }}
                  >
                    jordan <span>Admin</span>
                  </button>
                </p>
              </div>
            </div>
          </div>
          <div className="fixture-note">
            <Check size={16} aria-hidden="true" />
            <span>
              Selecting an account fills its local-only credentials. Then choose{" "}
              <strong>Sign in</strong>.
            </span>
          </div>
          <p className="fixture-password">
            Fixture password: <code>Slotkeeper-Local-Only!</code>
          </p>
        </aside>
      </main>
      <footer className="signin-footer">
        Room for your working day.<span>Slotkeeper · Local reference v0.1</span>
      </footer>
    </div>
  );
}

type View = "book" | "bookings" | "rooms" | "activity";

function WorkspaceConsole({ workspace }: { workspace: Workspace }) {
  const { api, session } = useSession();
  const [view, setView] = useState<View>("book");
  const [latestReservation, setLatestReservation] = useState<Reservation>();
  const rooms = useResource(
    (signal) => api.rooms(workspace.id, signal),
    [api, workspace.id],
  );
  const admin = workspace.role === "WORKSPACE_ADMIN";
  const titles: Record<View, [string, string]> = {
    book: ["Book a room", "A room and a time, with a moment to make it yours."],
    bookings: [
      "My bookings",
      "Your plans in this workspace, all in one place.",
    ],
    rooms: ["Manage rooms", "Keep the spaces in your workspace up to date."],
    activity: [
      "Workspace activity",
      "A record of successful booking changes in this workspace.",
    ],
  };
  return (
    <>
      <div className="navigation-band">
        <nav className="primary-navigation" aria-label="Workspace navigation">
          <button
            aria-current={view === "book" ? "page" : undefined}
            onClick={() => setView("book")}
          >
            <CalendarDays size={17} aria-hidden="true" />
            Book a room
          </button>
          <button
            aria-current={view === "bookings" ? "page" : undefined}
            onClick={() => setView("bookings")}
          >
            <ListChecks size={17} aria-hidden="true" />
            My bookings
          </button>
          {admin && (
            <>
              <span className="navigation-divider" aria-hidden="true" />
              <button
                aria-current={view === "rooms" ? "page" : undefined}
                onClick={() => setView("rooms")}
              >
                <DoorOpen size={17} aria-hidden="true" />
                Manage rooms
              </button>
              <button
                aria-current={view === "activity" ? "page" : undefined}
                onClick={() => setView("activity")}
              >
                <Clock3 size={17} aria-hidden="true" />
                Activity
              </button>
            </>
          )}
        </nav>
        <span className="workspace-role">
          {admin ? "Workspace admin" : "Workspace member"}
        </span>
      </div>
      <main className="workspace-main" id="main-content">
        <div className="page-heading">
          <div>
            <div className="section-kicker">{workspace.name}</div>
            <h1>{titles[view][0]}</h1>
            <p>{titles[view][1]}</p>
          </div>
          <div className="heading-aside">
            <Clock3 size={15} aria-hidden="true" />
            <span>
              {workspace.timeZone}
              <small>All booking times are local to this workspace</small>
            </span>
          </div>
        </div>
        {rooms.error ? (
          <ErrorNotice error={rooms.error} onRetry={rooms.refresh} />
        ) : rooms.loading ? (
          <Loading>Loading workspace rooms…</Loading>
        ) : (
          <>
            <div hidden={view !== "book"}>
              <AvailabilityView
                workspace={workspace}
                rooms={rooms.data?.items ?? []}
                serverNow={session!.serverNow}
                latestReservation={latestReservation}
                onBookingChanged={setLatestReservation}
                onManageRooms={admin ? () => setView("rooms") : undefined}
              />
            </div>
            {view === "bookings" && (
              <MyBookings
                workspace={workspace}
                rooms={rooms.data?.items ?? []}
                onBook={() => setView("book")}
                onBookingChanged={setLatestReservation}
              />
            )}
            {view === "rooms" && admin && (
              <RoomsView
                workspace={workspace}
                rooms={rooms.data?.items ?? []}
                onChanged={rooms.refresh}
              />
            )}
            {view === "activity" && admin && (
              <ActivityView
                workspace={workspace}
                rooms={rooms.data?.items ?? []}
              />
            )}
          </>
        )}
      </main>
      <footer className="app-footer">
        <span>
          <span className="footer-dot" /> {workspace.name}
        </span>
        <span>
          30-minute bookings <span className="footer-separator">/</span> 7-day
          availability
        </span>
        <span>Space for a productive day.</span>
      </footer>
    </>
  );
}

function AuthenticatedApp() {
  const { api, session, logout } = useSession();
  const workspaces = useResource((signal) => api.workspaces(signal), [api]);
  const [workspaceId, setWorkspaceId] = useState("");
  const [signingOut, setSigningOut] = useState(false);
  const [error, setError] = useState<unknown>();
  const selected =
    workspaces.data?.items.find((workspace) => workspace.id === workspaceId) ??
    workspaces.data?.items[0];
  const user = session?.authenticated ? session.user : undefined;
  const signOut = async () => {
    setSigningOut(true);
    setError(undefined);
    try {
      await logout();
    } catch (failure) {
      setError(failure);
    } finally {
      setSigningOut(false);
    }
  };
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <header className="app-header">
        <Brand />
        <div className="header-workspace">
          {selected ? (
            <WorkspacePicker
              workspaces={workspaces.data!.items}
              selectedId={selected.id}
              onChange={setWorkspaceId}
            />
          ) : (
            <span className="muted">Your workspace</span>
          )}
        </div>
        <div className="header-user">
          <span className="user-avatar" aria-hidden="true">
            {user?.displayName.slice(0, 1)}
          </span>
          <span className="user-name">{user?.displayName}</span>
          <button
            className="icon-button"
            onClick={() => {
              void signOut();
            }}
            disabled={signingOut}
            aria-label="Sign out"
          >
            <LogOut size={18} aria-hidden="true" />
          </button>
        </div>
      </header>
      {!!error && (
        <div className="global-notice">
          <ErrorNotice error={error} />
        </div>
      )}
      {workspaces.loading ? (
        <main id="main-content" className="standalone-state">
          <Loading>Finding your workspaces…</Loading>
        </main>
      ) : workspaces.error ? (
        <main id="main-content" className="standalone-state">
          <ErrorNotice error={workspaces.error} onRetry={workspaces.refresh} />
        </main>
      ) : selected ? (
        <WorkspaceConsole key={selected.id} workspace={selected} />
      ) : (
        <main id="main-content" className="standalone-state">
          <EmptyState title="No workspaces yet">
            Your account does not have a workspace. Ask your workspace
            administrator to arrange access.
          </EmptyState>
        </main>
      )}
    </div>
  );
}

function SessionGate() {
  const { session, loading, error, refresh } = useSession();
  if (loading)
    return (
      <div className="startup-page">
        <Brand />
        <main id="main-content">
          <Loading>Opening your workspace…</Loading>
        </main>
      </div>
    );
  if (error)
    return (
      <div className="startup-page">
        <Brand />
        <main id="main-content">
          <h1>Let’s reconnect.</h1>
          <ErrorNotice error={error} onRetry={refresh} />
        </main>
      </div>
    );
  return session?.authenticated ? <AuthenticatedApp /> : <SignIn />;
}

export default function App() {
  return (
    <SessionProvider>
      <SessionGate />
    </SessionProvider>
  );
}
