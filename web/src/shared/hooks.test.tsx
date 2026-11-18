import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useHoldSeconds } from "./hooks";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("server-observed hold countdown", () => {
  it("provides the full new hold duration on the first render that receives it", () => {
    let firstRender: number | undefined;
    const hook = renderHook(
      ({ now, expiry }) => {
        const seconds = useHoldSeconds(now, expiry);
        if (expiry && firstRender === undefined) firstRender = seconds;
        return seconds;
      },
      { initialProps: { now: "", expiry: null as string | null } },
    );
    hook.rerender({
      now: "2026-11-18T10:00:00Z",
      expiry: "2026-11-18T10:02:00Z",
    });
    expect(firstRender).toBe(120);
  });

  it("expires at the server deadline using monotonic elapsed time despite a wall-clock change", () => {
    vi.useFakeTimers();
    const monotonic = vi.spyOn(performance, "now").mockReturnValue(10_000);
    const { result } = renderHook(() =>
      useHoldSeconds("2026-11-18T10:00:00Z", "2026-11-18T10:02:00Z"),
    );
    expect(result.current).toBe(120);
    vi.setSystemTime(new Date("2035-01-01T00:00:00Z"));
    monotonic.mockReturnValue(11_250);
    act(() => {
      vi.advanceTimersByTime(250);
    });
    expect(result.current).toBe(119);
    monotonic.mockReturnValue(130_000);
    act(() => {
      vi.advanceTimersByTime(250);
    });
    expect(result.current).toBe(0);
  });
});
