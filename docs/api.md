# HTTP API Reference (implemented)

This document describes the HTTP endpoints currently implemented by the backend
(Spring Boot, port 8080). It is the source of truth for wiring the frontend.
See `docs/design.md` (section 16) for the API summary in context of the system
design.

## Base URL and conventions

- Backend dev server: `http://localhost:8080`.
- The Vite dev server proxies `/api/*` to the backend, so the browser can call
  the same relative paths. Browsers should send the session cookie, so calls
  use `credentials: "include"` (same-origin requests also work).
- Request and response bodies are JSON (`Content-Type: application/json`).
- The session cookie (`JSESSIONID`) identifies the current user. URL endpoints
  return `401 {"message": "请先登录"}` without a valid session.
- The session cookie is `HttpOnly`. Its `SameSite` and `Secure` flags come from
  `SESSION_COOKIE_SAME_SITE` (default `lax`) and `SESSION_COOKIE_SECURE`
  (default `false`; set `true` behind HTTPS). A frontend served from a
  different origin must also list its origin in `CORS_ALLOWED_ORIGINS` (the
  backend writes no CORS headers when it is empty).
- A monitored URL (`MonitoredUrl`) is serialized as:

```json
{
  "id": 1,
  "userId": 2,
  "name": "GitHub Homepage",
  "url": "https://github.com",
  "description": "Tracks release announcements",
  "createdAt": "2026-09-09T09:30:00",
  "lastStatus": "UP",
  "lastHttpStatus": 200,
  "lastErrorType": null,
  "lastCheckedAt": "2026-09-12T10:00:00.123",
  "changeCount": 23,
  "checkIntervalSeconds": null
}
```

`description` is a free-text note shown on the URL entry. It may be an empty
string and is `""` when the user leaves it blank.

- A user is serialized as (password is never returned):

```json
{
  "id": 2,
  "username": "alice",
  "createdAt": "2026-09-09T09:30:00"
}
```

- Field rules: `name` is 1-100 characters; `url` must start with `http://` or
  `https://`; `description` is 0-1000 characters and may be an empty string;
  `username` is 1-50 Unicode letters/digits/combining marks plus `_`, `-`, `.`;
  `password` is ASCII letters, digits, and `_` only.
- Error responses share the shape `{"message": "..."}`. Status codes in use:
  - `400 Bad Request` - validation or login failure.
  - `401 Unauthorized` - not logged in.
  - `404 Not Found` - the resource does not exist or is not owned by the user.
  - `409 Conflict` - username already taken.
  - `503 Service Unavailable` - the AI assistant could not reach its provider.

## Endpoint summary

| Method | Path               | Description                          |
|--------|--------------------|--------------------------------------|
| POST   | /api/users         | Register a user                      |
| POST   | /api/auth/login    | Login (starts a session)             |
| POST   | /api/auth/logout   | Logout (ends the session)            |
| GET    | /api/auth/me       | Current user or null                 |
| GET    | /api/urls          | List the current user's URLs         |
| POST   | /api/urls          | Create a URL for the current user    |
| PUT    | /api/urls/{id}     | Update one of the current user's URLs|
| DELETE | /api/urls/{id}     | Delete one of the current user's URLs|
| POST   | /api/urls/{id}/check | Check whether a stored URL is reachable now |
| GET    | /api/urls/{id}/timeline | Timeline events of a stored URL |
| POST   | /api/ai/chat       | Ask the AI assistant (optional feature) |
| POST   | /api/ai/conversations | Open an empty conversation (optional feature) |
| GET    | /api/ai/conversations | List the current user's conversations (optional feature) |
| GET    | /api/ai/conversations/{id} | One conversation and its messages (optional feature) |
| DELETE | /api/ai/conversations/{id} | Delete a conversation and its messages (optional feature) |

## 1. Register user

`POST /api/users`

Request body: `{ "username": "alice", "password": "Abc_123" }`

Validation:
- `username`: 1-50 characters; Unicode letters/digits from any language,
  combining marks, `_`, `-`, `.`. Stored exactly as sent (no normalization or
  encoding conversion); anything else is rejected.
- `password`: only ASCII letters (`a-z`, `A-Z`), digits (`0-9`), and `_`.
  Stored as a PBKDF2-SHA256 hash and never returned.

Responses:
- `201 Created` - the new user (no password fields).
- `400 Bad Request` - validation message.
- `409 Conflict` - `{"message": "username 已存在"}`.

Note: registering does not log you in. Call `POST /api/auth/login` afterwards
(the frontend does this automatically after a successful registration).

curl:

```
curl -X POST http://localhost:8080/api/users -H "Content-Type: application/json" -d "{\"username\":\"alice\",\"password\":\"Abc_123\"}"
```

## 2. Login

`POST /api/auth/login`

Request body: `{ "username": "alice", "password": "Abc_123" }`

Responses:
- `200 OK` - the logged-in user. A session cookie is set on the response.
- `400 Bad Request` - `{"message": "用户名或密码错误"}`.

curl (saves the cookie to `cookies.txt`):

```
curl -c cookies.txt -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d "{\"username\":\"alice\",\"password\":\"Abc_123\"}"
```

## 3. Logout

`POST /api/auth/logout`

Invalidates the session. Response `200 OK` with a message body.

## 4. Current user (session check)

`GET /api/auth/me`

Response `200 OK`:
- logged in: `{"user": {"id":2,"username":"alice","createdAt":"..."}}`
- not logged in: `{"user": null}`

Use this when the app loads to restore the session.

## 5. List URLs

`GET /api/urls` (requires login)

Response `200 OK`: array of the current user's `MonitoredUrl`, newest first.
Response `401 Unauthorized` if not logged in.

## 6. Create URL

`POST /api/urls` (requires login)

Request body: `{ "name": "Example", "url": "https://example.com",
"description": "Optional note" }`. `description` is optional at creation and
stored as `""` when omitted. The owner comes from the session; do not send
`userId`.

Responses:
- `201 Created` - the stored entity including `id`, `userId`, `description`,
  `createdAt`.
- `400 Bad Request` - validation message.
- `401 Unauthorized` - not logged in.

Creating a URL also schedules its first check straight away, so a
`FIRST_CHECK` event (section 10) appears in its timeline within a few seconds
rather than at the next scheduled pass.

## 7. Update URL

`PUT /api/urls/{id}` (requires login)

Full replacement of the editable fields; updates the URL only if it belongs to
the current user.

Request body: `{ "name": "GitHub Homepage", "url": "https://github.com",
"description": "Tracks release announcements" }`. `name` and `url` are required
and use the same rules as create. `description` may be an empty string or
omitted; both store `""` and clear any previous note.

Responses:
- `200 OK` - the updated `MonitoredUrl`.
- `400 Bad Request` - validation message.
- `404 Not Found` - no such URL for this user (including other users' URLs).
- `401 Unauthorized` - not logged in.

Changing `url` to a different address drops that entry's stored timeline and
schedules a fresh first check, because the old history belongs to the old page.
Edits that leave `url` unchanged keep the history as it is.

## 8. Delete URL

`DELETE /api/urls/{id}` (requires login)

Deletes the URL only if it belongs to the current user.

Responses:
- `200 OK` - the deleted `MonitoredUrl` as the body.
- `404 Not Found` - no such URL for this user (including other users' URLs).
- `401 Unauthorized` - not logged in.

## 9. Check URL accessibility

`POST /api/urls/{id}/check` (requires login)

Checks whether the stored URL is reachable right now, by issuing one live GET
request to it from the backend. The request has no body and the response is the
only place the result exists: **nothing is written to the database**, and the
timeline is not touched. History is maintained by the scheduler alone; read it
back with `GET /api/urls/{id}/timeline` (section 10).

Consequences for clients:

- `GET /api/urls` reports the outcome of the last *scheduled* check
  (`lastStatus`, `lastHttpStatus`, `lastCheckedAt`), and this endpoint never
  changes it. A manual result is a live reading the frontend owns: it is not
  stored and never appears in the timeline.
- The endpoint is a POST rather than a GET on purpose: it reaches out to a
  third-party site, so it must not be cached, prefetched, or retried blindly.
- Every call performs a real outbound request. Calling it from a page-load
  effect fires traffic at every tracked URL, so gate it (for example, only
  re-check entries whose last result is old).

Behaviour:

- Screens the target before every request: only `http`/`https` on ports 80/443,
  and only hosts that resolve to a public IP. Internal targets (loopback,
  private ranges, link-local/cloud metadata, multicast, IPv4-mapped IPv6) are
  refused with `errorType: "BLOCKED_TARGET"` and are never contacted.
- Follows up to 5 redirects by hand, screening each hop; more gives
  `errorType: "TOO_MANY_REDIRECTS"`.
- The body is read up to 2 MiB and hashed with SHA-256 so `changed` can be reported; this endpoint stores neither the body nor the hash.
- `2xx` and `3xx` give `status: "UP"`. Everything else gives `status: "DOWN"`.
- An unreachable target is still `200 OK` with `status: "DOWN"` and a reason in
  `errorType`; it is not an error status of this API.
- Limits: 5 s to connect, 10 s for the whole request.

Response `200 OK`:

```json
{
  "urlId": 7,
  "checkedAt": "2026-09-10T12:34:56.789",
  "status": "UP",
  "httpStatus": 200,
  "responseTimeMs": 183,
  "finalUrl": "https://example.com/",
  "errorType": null,
  "contentHash": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "changed": false,
  "changeType": null
}
```

| Field | Type | Notes |
| --- | --- | --- |
| urlId | number | The checked URL entry |
| checkedAt | string | When the check ran (server local time, millisecond precision) |
| status | string | `UP` or `DOWN` |
| httpStatus | number or null | Response status; null when no response arrived |
| responseTimeMs | number | Wall time of the request |
| finalUrl | string or null | URL after redirects; null when nothing was reached |
| errorType | string or null | Why the check failed; null when status is `UP` |
| contentHash | string or null | SHA-256 of the body, present when the probe got a `2xx`/`3xx` response; null otherwise. Informational only, never stored here. |
| changed | boolean or null | `true` when the body differs from the stored baseline (or there is no baseline yet), `false` when it matches, `null` exactly when `errorType` is set. |
| changeType | string or null | What the scheduler *would* record for this probe: `FIRST_CHECK`, `CONTENT_CHANGED`, `RECOVERED`, `UNAVAILABLE`; `null` when a successful check found no change. |

`errorType` values: `HTTP_ERROR` (4xx/5xx), `TIMEOUT`, `DNS_ERROR`,
`SSL_ERROR`, `CONNECTION_REFUSED`, `INVALID_URL`, `BLOCKED_TARGET` (internal
address or non-web port), `TOO_MANY_REDIRECTS`, `IO_ERROR`, `INTERRUPTED`.

Responses:
- `200 OK` - the check ran; inspect `status`.
- `401 Unauthorized` - not logged in.
- `404 Not Found` - no such URL for this user (including other users' URLs).

curl (checks the URL with id 1):

```
curl -b cookies.txt -X POST http://localhost:8080/api/urls/1/check
```

## 10. URL timeline

`GET /api/urls/{id}/timeline?limit=10` (requires login)

Reads back the history the scheduled checker has recorded for one URL, newest
event first. Like section 9 this endpoint is read-only, so it is safe to call
while the scheduler runs and can never conflict with a manual check.

Stored events are capped. Each URL keeps its first event (the `FIRST_CHECK`
baseline, always `changeNo = 1`) plus the newest 9 events, so at most 10 rows
are ever held. When a new event would push the count past 10, the oldest
non-baseline row is deleted in the same transaction as the insert, and the
baseline is never pruned. `totalCount` counts every event the URL has ever had,
including pruned ones, so a UI can say "showing 10 of N".

`limit` is optional, defaults to 10, and is clamped to 1-100. The cap on stored
rows makes a larger `limit` mostly pointless, but it is accepted.

Event types:

| changeType | Meaning |
| --- | --- |
| `FIRST_CHECK` | First check after the URL was created; records the baseline hash. |
| `CONTENT_CHANGED` | A successful check whose body hash differs from the stored one. |
| `RECOVERED` | A successful check after the previous state was `DOWN`. |
| `UNAVAILABLE` | The check got no usable response (4xx/5xx or a network error). |

A successful check that returns the same hash stores nothing, and a repeated
failure with the same status and `errorType` is collapsed into the existing
`UNAVAILABLE` event instead of adding another. Editing a URL to a *different*
address clears its timeline and restarts it with a fresh `FIRST_CHECK`.

Response `200 OK`:

```json
{
  "urlId": 7,
  "totalCount": 23,
  "limit": 10,
  "events": [
    {
      "id": 91,
      "changeNo": 23,
      "detectedAt": "2026-09-12T08:00:00.123",
      "changeType": "CONTENT_CHANGED",
      "status": "UP",
      "httpStatus": 200,
      "errorType": null,
      "responseTimeMs": 176,
      "oldHash": "9f86d081884c7d65...",
      "newHash": "2c26b46b68ffc68f..."
    },
    {
      "id": 88,
      "changeNo": 21,
      "detectedAt": "2026-09-11T08:00:00.456",
      "changeType": "UNAVAILABLE",
      "status": "DOWN",
      "httpStatus": 503,
      "errorType": "HTTP_ERROR",
      "responseTimeMs": 412,
      "oldHash": null,
      "newHash": null
    }
  ]
}
```

| Field | Type | Notes |
| --- | --- | --- |
| urlId | number | The URL the timeline belongs to |
| totalCount | number | All-time event count, including pruned rows |
| limit | number | Effective limit applied to `events` |
| events | array | Newest first, at most `limit` entries |
| events[].id | number | Row id, usable as a stable list key |
| events[].changeNo | number | Monotonic per-URL event number; `1` is the baseline |
| events[].detectedAt | string | When the check ran (server local time, millisecond precision) |
| events[].changeType | string | `FIRST_CHECK`, `CONTENT_CHANGED`, `RECOVERED`, or `UNAVAILABLE` |
| events[].status | string | `UP` or `DOWN`, derived from `changeType` so the UI can colour an entry without parsing it |
| events[].httpStatus | number or null | Response status; null when no response arrived |
| events[].errorType | string or null | Same values as section 9; set when `changeType` is `UNAVAILABLE` |
| events[].responseTimeMs | number or null | Wall time of the recorded check |
| events[].oldHash | string or null | Hash before the event; null for `FIRST_CHECK`, `RECOVERED`, and `UNAVAILABLE` |
| events[].newHash | string or null | Hash after the event; null for `UNAVAILABLE` |

Responses:
- `200 OK` - the timeline; `events` may be empty until the scheduler first runs.
- `401 Unauthorized` - not logged in.
- `404 Not Found` - no such URL for this user (including other users' URLs).

curl:

```
curl -b cookies.txt "http://localhost:8080/api/urls/1/timeline?limit=10"
```

## 11. AI assistant chat

Natural-language interface to the endpoints above, described in
`docs/ai_assistant_feature_design.md`. It is **off unless configured**: the
endpoints exist as soon as a credential is set, so a deployment with no LLM key
exposes nothing under `/api/ai`. `AI_ENABLED=false` keeps them hidden even with
a key; `AI_ENABLED=true` demands one.

The user is taken from the session, exactly as for the endpoints above, and is
never taken from the request body. The assistant's tools can only reach the
signed-in user's URLs.

### Configuration

| Variable | Default | Notes |
| --- | --- | --- |
| `AI_ENABLED` | (unset) | Unset exposes the endpoints whenever `AI_API_KEY` is set. `false` hides them even with a key; `true` demands a key. |
| `AI_API_KEY` | (empty) | Provider credential, kept on the backend. Setting it exposes the endpoints. `AI_ENABLED=true` without it makes the app refuse to start. |
| `AI_BASE_URL` | `https://api.deepseek.com` | Any OpenAI-compatible API root. |
| `AI_MODEL` | `deepseek-flash` | Must support tool calling, or every answer fails. |
| `AI_TEMPERATURE` | `0.2` | |
| `AI_MAX_TOKENS` | `1024` | Caps the length of one answer. |
| `AI_MAX_RETRIES` | `2` | Retries a rate-limited provider call. |
| `AI_TIMEOUT_SECONDS` | `60` | How long one provider call may take. |
| `AI_MAX_URLS_PER_CHECK` | `10` | URLs probed per `checkUrls` call. Each one is really fetched, so this bounds the latency of one answer. |
| `AI_MEMORY_MAX_MESSAGES` | `20` | Messages of a conversation replayed to the model as context. Bounds the prompt of a long conversation. |
| `AI_MAX_CONVERSATIONS` | `5` | Conversations kept per user. Past this, the least recently used conversation and its messages are deleted. |

For OpenAI itself, set `AI_BASE_URL=https://api.openai.com/v1` and
`AI_MODEL=gpt-4o-mini`.

### Knowledge base (RAG)

The assistant can also answer questions about how URLCheck itself works
(change detection, the checker, the timeline, authentication, the database,
the API). That documentation is not an endpoint: it is a **tool**,
`searchKnowledge`, which the model calls when a question is about the
application. The system prompt tells it to search the knowledge base before
claiming something is unsupported.

The documents are Markdown files that ship inside the jar
(`backend/src/main/resources/ai/knowledge/`, currently written in Chinese) and
are embedded at startup into Qdrant by a model that runs locally in the
backend (`bge-small-zh-v1.5`, ONNX) - there is no external embedding API.
Ingestion is incremental: a SHA-256 ledger in MySQL means unchanged documents
cost nothing on restart. Operation details are in `docs/knowledge_base.md`.

| Variable | Default | Notes |
| --- | --- | --- |
| `AI_KNOWLEDGE_ENABLED` | `false` | Must be `true` to turn the knowledge base on; requires Qdrant. |
| `AI_KNOWLEDGE_INGEST_ON_START` | `true` | Compare the documents with the hash ledger at startup and embed only changed ones. |
| `QDRANT_URL` / `QDRANT_API_KEY` | (empty) | Qdrant connection; read only when the knowledge base is on. |
| `QDRANT_COLLECTION` | `urlcheck_knowledge` | The Qdrant collection, created on first run. |
| `AI_KNOWLEDGE_TOP_K` | `4` | Passages returned per search. |
| `AI_KNOWLEDGE_SIMILARITY_THRESHOLD` | `0.5` | Minimum cosine similarity worth showing the model. |
| `AI_KNOWLEDGE_MAX_CHUNK_CHARS` | `800` | Chunk size the documents are split into. |

### POST /api/ai/chat

One question, one answer. `message` is required unless `confirmationToken` is
sent.

Request:

```json
{ "conversationId": 101, "message": "Which of my URLs are inaccessible?" }
```

`conversationId` is optional. Sent, the message continues that conversation and
the assistant resolves follow-ups such as "how often should I check it?" against
what was already said. Left out, the message opens a new conversation.

Response:

```json
{
  "conversationId": 101,
  "message": "2 of your 5 URLs are inaccessible: University and Research.",
  "confirmation": null
}
```

`message` is the assistant's answer, written in the language the user used.
`conversationId` is the conversation the turn was recorded in: the one that was
sent, or the one this message opened. Send it back to continue the thread.

Both the question and the answer are stored in the conversation's history, so a
client can read them back from `GET /api/ai/conversations/{id}`. A provider
failure still stores the question, so nothing the user typed is lost.

### Confirming a deletion

Deletion is never carried out on the assistant's word. When the model asks to
delete something, nothing is removed: the backend records what would go against
the session and returns it as `confirmation`.

```json
{
  "message": "I found 2 URLs that are inaccessible. Deleting them removes them permanently. Shall I continue?",
  "confirmation": {
    "token": "9f2c...",
    "urls": [
      { "id": 3, "name": "University", "url": "https://university.edu" },
      { "id": 5, "name": "Research", "url": "https://research.example" }
    ]
  }
}
```

Send the token back to carry it out:

```json
{ "confirmationToken": "9f2c..." }
```

The reply then reports the outcome, without asking the model again:

```json
{ "message": "已删除 2 个 URL：University、Research。", "confirmation": null }
```

Rules for the frontend:

- Show a confirm/cancel prompt whenever `confirmation` is non-null. On confirm,
  send the token only; do not re-send the original `message`.
- The token is single-use and bound to the session. A second attempt, another
  session's token, or an expired one answers
  `400 {"message": "确认已过期，请重新发起删除请求"}`.
- Never pass `confirmation.urls` to `DELETE /api/urls/{id}` yourself. Send the
  token, so the rows acted on are the ones the backend recorded.
- Keep the `conversationId` from each answer and send it with the next message,
  so follow-ups like "now check it" resolve against the thread. A client that
  sends none opens a new conversation every time, and the retention cap (5 by
  default) then discards older ones.
- A conversation that belongs to another user answers `404`, exactly as one that
  does not exist: the API never confirms a stranger's conversation is real.

### Failures

| Status | Meaning |
| --- | --- |
| `503` | The provider could not be reached or refused the request (`{"message": "AI 助手暂时不可用，请稍后再试"}`). The rest of the API is unaffected. |
| `400` | Empty `message`, or a confirmation token that is unknown or already used. |
| `401` | Not logged in, as for every other authenticated endpoint. |
| `404` | A conversation that does not exist, or is not the current user's. |

Free provider tiers rate-limit aggressively. `AI_MAX_RETRIES` covers the
occasional `429`, but a busy or slow model can still end in a `503`.

### Conversation history

The assistant keeps a per-user conversation history in MySQL, so a client can
show past threads and continue one. It is bounded: only the most recently *used*
`AI_MAX_CONVERSATIONS` conversations are kept (5 by default). Opening one past
that deletes the least recently used conversation and its messages. Activity,
not creation time, decides which is "recent", so a thread the user returns to
becomes recent again.

The full reasoning is in `docs/ai_context_design.md` (section 23).

#### POST /api/ai/conversations

Opens an empty conversation. The chat endpoint opens one by itself when a message
arrives without an id, so this is only for a client that wants the thread to exist
before the user has typed anything.

Response:

```json
{ "id": 101, "title": "新对话" }
```

#### GET /api/ai/conversations

This user's conversations, most recent activity first.

```json
[
  { "id": 101, "title": "URL monitoring", "updatedAt": "2026-09-17T18:30:00" },
  { "id": 102, "title": "Troubleshooting", "updatedAt": "2026-09-16T14:20:00" }
]
```

A title comes from the first user message, cut to a readable length. A
conversation that has no messages yet is titled `新对话`.

#### GET /api/ai/conversations/{id}

One conversation and its stored history, oldest message first.

```json
{
  "id": 101,
  "title": "URL monitoring",
  "messages": [
    { "role": "USER", "content": "What is URL monitoring?" },
    { "role": "ASSISTANT", "content": "URL monitoring periodically checks a URL..." }
  ]
}
```

A question whose provider call failed appears here with no answer after it.

#### DELETE /api/ai/conversations/{id}

Deletes the conversation and its messages. Answers `204 No Content`, or `404`
when the conversation does not exist or belongs to somebody else.

## Frontend wiring

`frontend/src/api.ts` exposes `registerUser`, `loginUser`, `logoutUser`,
`fetchCurrentUser`, `fetchUrls`, `createUrl`, `updateUrl`, `deleteUrl`,
`checkUrl`, and `fetchTimeline`. All requests include credentials so the
session cookie is sent.

The assistant adds `chatWithAssistant`, `fetchConversations`,
`fetchConversation`, and `deleteConversation`. `chatWithAssistant` takes the
open conversation's id, or `null` to let the backend open a thread and name it
after the first message; its answer carries the id back.

On app load, restore the session:

```ts
const current = await fetchCurrentUser(); // User | null
if (current) {
  setUser(current);
  const urls = await fetchUrls(); // only current user's URLs
}
```

Register then log in (MVP flow):

```ts
async function handleRegister(username: string, password: string) {
  await registerUser(username, password);
  const user = await loginUser(username, password);
  setUser(user);
  await loadUrls();
}
```

Logout:

```ts
async function handleLogout() {
  await logoutUser();
  setUser(null);
  setUrls([]);
}
```

Checking one URL is a separate, explicit POST (see section 9). It is never
triggered by `GET /api/urls`, which stays free of side effects:

```ts
export function checkUrl(id: number): Promise<CheckResult> {
  return request<CheckResult>(`/api/urls/${id}/check`, { method: "POST" });
}
```

`CheckResult` mirrors the JSON in section 9. `MonitoredUrl` also carries the
read-only last-check state (`lastStatus`, `lastHttpStatus`, `lastErrorType`,
`lastCheckedAt`, `changeCount`), which the scheduler writes and the manual check
never touches. Fetch the timeline with a separate read:

```ts
export function fetchTimeline(id: number, limit = 10): Promise<TimelineView> {
  return request<TimelineView>(`/api/urls/${id}/timeline?limit=${limit}`);
}
```

Because the timeline is a plain GET, a collapsed/hidden panel costs nothing:
load it lazily when the user expands it rather than on app load.
