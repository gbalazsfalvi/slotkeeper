import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
} from "react";
import type { ReactNode } from "react";
import { ApiClient } from "../api/client";
import type { SessionView } from "../api/contracts";

interface SessionContextValue {
  api: ApiClient;
  session: SessionView | undefined;
  loading: boolean;
  error: unknown;
  ended: boolean;
  refresh: () => void;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}
const SessionContext = createContext<SessionContextValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [api] = useState(() => new ApiClient());
  const [session, setSession] = useState<SessionView>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>();
  const [ended, setEnded] = useState(false);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const abort = new AbortController();
    let active = true;
    api.onSessionExpired = () => {
      setEnded(true);
      setSession({ authenticated: false, serverNow: new Date().toISOString() });
    };
    setLoading(true);
    setError(undefined);
    void api
      .session(abort.signal)
      .then((result) => {
        if (active) setSession(result);
      })
      .catch((failure: unknown) => {
        if (active) setError(failure);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
      abort.abort();
      api.onSessionExpired = undefined;
    };
  }, [api, revision]);

  const login = useCallback(
    async (username: string, password: string) => {
      const result = await api.login(username, password);
      setSession(result);
      setEnded(false);
    },
    [api],
  );
  const logout = useCallback(async () => {
    await api.logout();
    setSession({ authenticated: false, serverNow: new Date().toISOString() });
    setEnded(false);
  }, [api]);

  return (
    <SessionContext.Provider
      value={{
        api,
        session,
        loading,
        error,
        ended,
        refresh: () => setRevision((value) => value + 1),
        login,
        logout,
      }}
    >
      {children}
    </SessionContext.Provider>
  );
}

export function useSession() {
  const context = useContext(SessionContext);
  if (!context) throw new Error("SessionProvider is required.");
  return context;
}
