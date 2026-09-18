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

/** One URL a pending confirmation would delete. */
export interface AiPendingUrl {
  id: number;
  name: string;
  url: string;
}

/**
 * A deletion the assistant proposed but did not perform. `token` is the only
 * thing to send back; the rows the backend acts on come from its own copy of
 * this proposal, not from `urls`.
 */
export interface AiConfirmation {
  token: string;
  urls: AiPendingUrl[];
}

/** One assistant answer, the conversation it belongs to, and anything to confirm. */
export interface AiChatResponse {
  /** Null only on a confirmation reply that named no conversation. */
  conversationId: number | null;
  message: string;
  confirmation: AiConfirmation | null;
}

/**
 * Sends one message to the AI assistant. Pass `confirmationToken` instead of a
 * message to carry out a deletion the assistant proposed.
 *
 * `conversationId` continues an existing conversation. Pass null to start one:
 * the backend opens it and reports the id in the answer, so a new chat costs no
 * extra request and is named after its first message.
 *
 * The endpoint only exists when the backend has the assistant enabled and
 * configured, and answers `503` when the provider cannot be reached.
 */
export function chatWithAssistant(
  message: string,
  conversationId: number | null,
  confirmationToken?: string
): Promise<AiChatResponse> {
  return request<AiChatResponse>("/api/ai/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(
      confirmationToken ? { confirmationToken, conversationId } : { message, conversationId }
    )
  });
}

/** One conversation as the history list shows it, most recent activity first. */
export interface AiConversationSummary {
  id: number;
  title: string;
  updatedAt: string;
}

/** One stored message of a conversation. */
export interface AiStoredMessage {
  role: "USER" | "ASSISTANT";
  content: string;
}

/** One conversation and its complete stored history, oldest message first. */
export interface AiConversationDetail {
  id: number;
  title: string;
  messages: AiStoredMessage[];
}

/** The signed-in user's conversations. Only the most recent ones are kept. */
export function fetchConversations(): Promise<AiConversationSummary[]> {
  return request<AiConversationSummary[]>("/api/ai/conversations");
}

/** One conversation and everything that was said in it. */
export function fetchConversation(id: number): Promise<AiConversationDetail> {
  return request<AiConversationDetail>(`/api/ai/conversations/${id}`);
}

/** Deletes a conversation and its messages. */
export function deleteConversation(id: number): Promise<void> {
  return request<void>(`/api/ai/conversations/${id}`, { method: "DELETE" });
}
