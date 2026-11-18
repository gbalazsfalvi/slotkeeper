import { useEffect, useMemo, useState } from "react";
import type { DependencyList } from "react";
import { ApiError } from "../api/client";

export function useResource<T>(
  load: (signal: AbortSignal) => Promise<T>,
  dependencies: DependencyList,
) {
  const [data, setData] = useState<T>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>();
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const abort = new AbortController();
    let active = true;
    setData(undefined);
    setError(undefined);
    setLoading(true);
    void load(abort.signal)
      .then((result) => {
        if (active) setData(result);
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
    };
    // Callers declare every scope and input that can change the request.
  }, [...dependencies, revision]);
  return {
    data,
    loading,
    error,
    refresh: () => setRevision((value) => value + 1),
  };
}

export function useCooldown(error: unknown): number {
  const [seconds, setSeconds] = useState(0);
  useEffect(() => {
    if (!(error instanceof ApiError) || error.status !== 429) {
      setSeconds(0);
      return;
    }
    const deadline = performance.now() + Math.max(1, error.retryAfter) * 1000;
    const tick = () =>
      setSeconds(Math.max(0, Math.ceil((deadline - performance.now()) / 1000)));
    tick();
    const timer = window.setInterval(tick, 250);
    return () => window.clearInterval(timer);
  }, [error]);
  return seconds;
}

/** Advance the last server observation with elapsed monotonic time, never the wall clock. */
export function useHoldSeconds(
  serverNow: string,
  expiresAt: string | null,
): number {
  const anchor = useMemo(
    () => ({
      observedAt: performance.now(),
      duration: expiresAt
        ? Math.max(0, Date.parse(expiresAt) - Date.parse(serverNow))
        : 0,
    }),
    [serverNow, expiresAt],
  );
  const [, setFrame] = useState(0);
  useEffect(() => {
    if (anchor.duration <= 0) return;
    const timer = window.setInterval(() => {
      setFrame((value) => value + 1);
      if (performance.now() - anchor.observedAt >= anchor.duration)
        window.clearInterval(timer);
    }, 250);
    return () => window.clearInterval(timer);
  }, [anchor]);
  return Math.max(
    0,
    Math.ceil(
      (anchor.duration - (performance.now() - anchor.observedAt)) / 1000,
    ),
  );
}
