import {
  AlertCircle,
  ArrowRight,
  CalendarDays,
  LoaderCircle,
} from "lucide-react";
import type { ReactNode } from "react";
import { ApiError, errorMessage } from "../api/client";

export function ErrorNotice({
  error,
  onRetry,
}: {
  error: unknown;
  onRetry?: () => void;
}) {
  if (!error) return null;
  return (
    <div className="error-notice" role="alert">
      <AlertCircle size={18} aria-hidden="true" />
      <div>
        <p>{errorMessage(error)}</p>
        {error instanceof ApiError && error.requestId && (
          <p className="request-reference">Reference: {error.requestId}</p>
        )}
        {onRetry && (
          <button className="text-button" onClick={onRetry}>
            Try again <ArrowRight size={14} aria-hidden="true" />
          </button>
        )}
      </div>
    </div>
  );
}
export function Loading({ children = "Loading…" }: { children?: ReactNode }) {
  return (
    <div className="loading-state" role="status">
      <LoaderCircle className="spinner" size={21} aria-hidden="true" />
      <span>{children}</span>
    </div>
  );
}
export function EmptyState({
  title,
  children,
  action,
}: {
  title: string;
  children: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <CalendarDays size={28} strokeWidth={1.5} aria-hidden="true" />
      <h3>{title}</h3>
      <p>{children}</p>
      {action}
    </div>
  );
}
export function StatusBadge({ status }: { status: string }) {
  const labels: Record<string, string> = {
    HELD: "On hold",
    CONFIRMED: "Confirmed",
    CANCELLED: "Cancelled",
    EXPIRED: "Expired",
  };
  return (
    <span className={`status-badge status-${status.toLowerCase()}`}>
      <span aria-hidden="true" />
      {labels[status] ?? status}
    </span>
  );
}
