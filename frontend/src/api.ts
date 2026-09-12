export interface MonitoredUrl {
  id: number;
  name: string;
  url: string;
  description: string;
  createdAt: string;
  /** State of the last scheduled check; written only by the backend scheduler. */
  lastStatus: CheckStatus | null;
  lastHttpStatus: number | null;
  lastErrorType: string | null;
  lastCheckedAt: string | null;
  /** All-time number of stored timeline events, including pruned ones. */
  changeCount: number;
}

export interface User {
  id: number;
  username: string;
  createdAt: string;
}

export type CheckStatus = "UP" | "DOWN";

/** Result of one accessibility check. The backend does not store these. */
export interface CheckResult {
  urlId: number;
  checkedAt: string;
  status: CheckStatus;
  httpStatus: number | null;
  responseTimeMs: number;
  finalUrl: string | null;
  errorType: string | null;
}

export type ChangeType =
  | "FIRST_CHECK"
  | "CONTENT_CHANGED"
  | "RECOVERED"
  | "UNAVAILABLE";

/** One stored event on a URL timeline. */
export interface TimelineEvent {
  id: number;
  changeNo: number;
  detectedAt: string;
  changeType: ChangeType;
  status: CheckStatus;
  httpStatus: number | null;
  errorType: string | null;
  responseTimeMs: number | null;
  oldHash: string | null;
  newHash: string | null;
}

/** Newest-first page of stored history, plus the all-time event count. */
export interface TimelineView {
  urlId: number;
  totalCount: number;
  limit: number;
  events: TimelineEvent[];
}

/**
 * Base path for API calls. Empty means same origin, which is what the Vite
 * dev proxy and a reverse-proxied production deploy both expect. Set
 * VITE_API_BASE_URL at build time to target a backend on another origin.
 */
const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/+$/, "");

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE_URL}${path}`, { credentials: "include", ...init });
  if (!res.ok) {
    let message = `请求失败 (HTTP ${res.status})`;
    try {
      const body = await res.json();
      if (body && typeof body.message === "string") {
        message = body.message;
      }
    } catch {
      // keep the default message when the response is not JSON
    }
    throw new Error(message);
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return res.json() as Promise<T>;
}

export function fetchUrls(): Promise<MonitoredUrl[]> {
  return request<MonitoredUrl[]>("/api/urls");
}

export function createUrl(
  name: string,
  url: string,
  description?: string
): Promise<MonitoredUrl> {
  return request<MonitoredUrl>("/api/urls", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, url, description: description ?? "" })
  });
}

export function updateUrl(
  id: number,
  name: string,
  url: string,
  description: string
): Promise<MonitoredUrl> {
  return request<MonitoredUrl>(`/api/urls/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, url, description })
  });
}

export function deleteUrl(id: number): Promise<MonitoredUrl> {
  return request<MonitoredUrl>(`/api/urls/${id}`, {
    method: "DELETE"
  });
}

/**
 * Probes the URL right now. A POST because the backend makes an outbound
 * request; an unreachable site still resolves with `status: "DOWN"` and a
 * reason in `errorType`.
 */
export function checkUrl(id: number): Promise<CheckResult> {
  return request<CheckResult>(`/api/urls/${id}/check`, {
    method: "POST"
  });
}

/**
 * Reads a URL's stored timeline, newest event first. Read-only: the backend
 * maintains it, so a manual check and this call never conflict.
 */
export function fetchTimeline(id: number, limit = 10): Promise<TimelineView> {
  return request<TimelineView>(`/api/urls/${id}/timeline?limit=${limit}`);
}

export function registerUser(username: string, password: string): Promise<User> {
  return request<User>("/api/users", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password })
  });
}

export function loginUser(username: string, password: string): Promise<User> {
  return request<User>("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password })
  });
}

export function logoutUser(): Promise<unknown> {
  return request<unknown>("/api/auth/logout", {
    method: "POST"
  });
}

export async function fetchCurrentUser(): Promise<User | null> {
  const body = await request<{ user: User | null }>("/api/auth/me");
  return body.user;
}
