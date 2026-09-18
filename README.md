# URL Change Monitor

A lightweight URL monitoring and page-change detection system.

Add URLs with custom names, and the system periodically checks them, records each
HTTP result, and detects page changes by comparing content hashes. Change,
unavailability, and recovery events are recorded and shown on a timeline in the
frontend.

## Tech Stack

- Frontend: React + TypeScript
- Backend: Java + Spring Boot (Modular Monolith)
- Database: MySQL 8.0+
- Deployment: Docker Compose

## Repository Layout

    frontend/         React + TypeScript SPA
    backend/          Spring Boot application
    database/         SQL scripts / schema
    docs/design.md    Full design document (Chinese)
    docker-compose.yml

## Documentation

The complete architecture and design document (in Chinese) lives in
`docs/design.md`.

The implemented HTTP API reference (endpoints, payloads, error responses, and
frontend wiring) lives in `docs/api.md`.

The scheduled-check and timeline design (cadence, hashing, retention, and event
semantics) lives in `docs/timeline.md`.

The database schema reference (tables, columns, keys, and relationships)
lives in `docs/database.md`.

## How to Run (development, vertical slice)

The current vertical slice proves the full chain
`Frontend -> HTTP API -> Controller -> Service -> DB access -> MySQL` using the
`monitored_url` entity scoped to the logged-in user: register + login,
create + list + edit + delete (with an optional free-text `description`), plus
a manual accessibility check, the scheduled content-change detector, and the
stored timeline it maintains.

Prerequisites: Java 17+ (built/tested on 26), Maven 3.9+, Node.js, and MySQL 8
reachable at `localhost:3306` (start it with `docker compose up -d mysql`;
credentials `root`/`root`, database `url_monitor` as configured in
`docker-compose.yml`).

1. Create the database tables once by applying every migration in `database/`
   in filename order:

   `mysql --protocol=TCP -h127.0.0.1 -uroot -proot < database/001_create_monitored_url.sql`
   `mysql --protocol=TCP -h127.0.0.1 -uroot -proot < database/002_users_urls_timeline.sql`
   `mysql --protocol=TCP -h127.0.0.1 -uroot -proot < database/003_change_event_numbering.sql`
   `mysql --protocol=TCP -h127.0.0.1 -uroot -proot < database/004_add_url_description.sql`
   `mysql --protocol=TCP -h127.0.0.1 -uroot -proot < database/005_remove_legacy_owner.sql`
   `mysql --protocol=TCP -h127.0.0.1 -uroot -proot < database/006_timeline_schedule.sql`
   `mysql --protocol=TCP -h127.0.0.1 -uroot -proot < database/007_ai_conversations.sql`
   `mysql --protocol=TCP -h127.0.0.1 -uroot -proot < database/008_ai_knowledge.sql`

2. Run the backend (port 8080). Drop the `local` profile to re-check every URL
   every 15 minutes (the default), or keep it to re-check every 60 seconds
   while you test:

   `cd backend && SPRING_PROFILES_ACTIVE=local mvn spring-boot:run`
   (PowerShell: `$env:SPRING_PROFILES_ACTIVE="local"; mvn spring-boot:run`)

3. Run the frontend dev server (port 5173, proxies `/api` to the backend):

   `cd frontend && npm install && npm run dev`

4. Open http://localhost:5173, register/login, add a URL, and it should appear in the list.

Useful API smoke tests:

- Register a user:
- `curl -X POST http://localhost:8080/api/users -H "Content-Type: application/json" -d "{\"username\":\"alice\",\"password\":\"Abc_123\"}"`
- Login (stores the session cookie in `cookies.txt`):
- `curl -c cookies.txt -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d "{\"username\":\"alice\",\"password\":\"Abc_123\"}"`
- `curl -b cookies.txt http://localhost:8080/api/urls`
- `curl -b cookies.txt -X POST http://localhost:8080/api/urls -H "Content-Type: application/json" -d "{\"name\":\"Example\",\"url\":\"https://example.com\",\"description\":\"Optional note\"}"`
- `curl -b cookies.txt -X PUT http://localhost:8080/api/urls/1 -H "Content-Type: application/json" -d "{\"name\":\"Example\",\"url\":\"https://example.com\"}"`
- `curl -b cookies.txt -X POST http://localhost:8080/api/urls/1/check`
- `curl -b cookies.txt -X DELETE http://localhost:8080/api/urls/1`

## AI assistant (optional)

`docs/ai_assistant_feature_design.md` describes a natural-language interface to
the same URL features: list, check, create and delete a user's monitored URLs by
asking. It reuses the existing services, so validation, ownership and the
checking rules are unchanged, and its tools cannot reach another user's data.

Conversations are remembered. Each answer reports the `conversationId` it belongs
to, and sending it back lets the assistant resolve a follow-up such as "how often
should I check it?" against what was already said. A user keeps the five most
recently used conversations; past that the least recently used one, and its
messages, are deleted. `docs/ai_context_design.md` has the design and the
reasoning.

It is on as soon as it is configured, so a deployment that supplies a key gets it
and one that supplies none behaves exactly as it did before:

    AI_API_KEY=sk-...
    AI_BASE_URL=https://api.deepseek.com   # any OpenAI-compatible root
    AI_MODEL=deepseek-flash                # must support tool calling

The model, endpoint and limits are all environment variables, so the provider can
be swapped without a rebuild. `AI_ENABLED` overrides the credential in both
directions: `false` keeps the assistant off even with a key present, and `true`
demands a key, failing at startup rather than on the first question.

Deleting through the assistant is two-step: the first call only proposes the
deletion and returns a confirmation token, and the rows are removed when the
token is sent back. See section 11 of `docs/api.md` for the request and response
shapes.

The tools, the confirmation handshake and the configuration are covered by unit
tests that need neither a network nor a key. One integration test does make a
real provider call and is skipped unless `AI_API_KEY` is set:

    cd backend && AI_API_KEY=sk-... mvn test -Dtest=AiAgentIntegrationTest

## Knowledge base (RAG, optional)

The assistant can answer questions about URLCheck itself from the Markdown files
in `backend/src/main/resources/ai/knowledge/`. The documents are chunked,
embedded by a local ONNX model that ships inside the jar (no provider, no key,
no per-question cost) and stored in Qdrant. As with the URL tools, the model
decides when to call `searchKnowledge`, so a question about a user's own URLs
never pays for a vector search.

It is off unless it is configured:

    AI_KNOWLEDGE_ENABLED=true
    QDRANT_URL=https://xxxx.cloud.qdrant.io
    QDRANT_API_KEY=...

On startup the ingestor compares a SHA-256 hash of each knowledge file with the
ledger table created by `008_ai_knowledge.sql` and embeds only the files whose
hash changed, so restarting the application costs nothing and re-embeds nothing.
Deleting a file deletes its vectors. `docs/knowledge_base.md` documents the
document layout, the chunking rules and how to add or update knowledge.

## Connecting to Aiven for MySQL

The datasource is configured through environment variables, so no code change is
needed to point the backend at Aiven. The defaults still target the local Docker
MySQL.

| Variable | Default | Notes |
| --- | --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/url_monitor?...` | Full JDBC URL |
| `DB_USERNAME` | `root` | Aiven user, usually `avnadmin` |
| `DB_PASSWORD` | `root` | Aiven service password |

1. In the Aiven console, open your MySQL service and copy the **Service URI**
   (it looks like `mysql://avnadmin:PASSWORD@HOST:PORT/defaultdb`).
2. Aiven requires TLS, so build the JDBC URL from that URI, swapping
   `mysql://` for `jdbc:mysql://` and dropping the credentials:

   `jdbc:mysql://HOST:PORT/url_monitor?sslMode=REQUIRED&characterEncoding=utf8&serverTimezone=UTC`

3. Download the service CA certificate from the console
   (Overview -> Connection information -> CA certificate). To verify the server
   certificate instead of only encrypting the connection, import the PEM into a
   truststore and switch the URL to `sslMode=VERIFY_CA`:

   `keytool -importcert -alias aiven-ca -file ca.pem -keystore aiven-truststore.jks -storepass changeit -noprompt`

   `jdbc:mysql://HOST:PORT/url_monitor?sslMode=VERIFY_CA&trustCertificateKeyStoreUrl=file:/path/to/aiven-truststore.jks&trustCertificateKeyStorePassword=changeit&characterEncoding=utf8&serverTimezone=UTC`

4. Apply the schema to the Aiven service. The scripts create and use
   `url_monitor`, so run them in filename order against `defaultdb`:

   `mysql --protocol=TCP -h HOST -P PORT -u avnadmin -p --ssl-mode=REQUIRED defaultdb < database/001_create_monitored_url.sql`

   ...then `002` through `005`, in order.

5. Start the backend with the Aiven values (PowerShell):

   `$env:DB_URL="jdbc:mysql://HOST:PORT/url_monitor?sslMode=REQUIRED&characterEncoding=utf8&serverTimezone=UTC"`

   `$env:DB_USERNAME="avnadmin"`

   `$env:DB_PASSWORD="PASSWORD"`

   `cd backend && mvn spring-boot:run`

Notes:

- Never commit the Aiven password or the CA/truststore files; both are covered
  by `.gitignore` patterns for env files, but the truststore is not, so keep it
  outside the repo or ignore it explicitly.
- Timestamps: `created_at` values come from MySQL's `CURRENT_TIMESTAMP`, which
  uses the session time zone, not `serverTimezone`/`connectionTimeZone`. Aiven's
  session zone is UTC while the local Docker MySQL is pinned to `Asia/Shanghai`,
  so the same row is stored eight hours apart between the two environments. Add
  `connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true` to
  `DB_URL` so Aiven writes local-time literals exactly like the Docker setup, or
  leave it off and treat every stored timestamp as UTC.
- If the Aiven plan caps connections, lower the Hikari pool size with
  `--spring.datasource.hikari.maximum-pool-size=N`.

## Deploying to production

### Environment variables

| Variable | Required | Notes |
| --- | --- | --- |
| `DB_URL` | yes | JDBC URL, for example `jdbc:mysql://HOST:PORT/url_monitor?sslMode=VERIFY_CA&characterEncoding=utf8&serverTimezone=UTC` |
| `DB_USERNAME` | yes | Aiven user. |
| `DB_PASSWORD` | yes | Aiven password. Never commit it. |
| `CORS_ALLOWED_ORIGINS` | only for split origins | Comma-separated browser origins, for example `https://app.example.com`. Empty means same-origin only. |
| `SESSION_COOKIE_SECURE` | recommended | `true` whenever the app is served over HTTPS. |
| `SESSION_COOKIE_SAME_SITE` | recommended | `lax` (default) for same-origin. Use `none` when the frontend is on another site, which also requires `SESSION_COOKIE_SECURE=true`. |
| `SERVER_PORT` | no | Falls back to `PORT` (Render, Railway, Heroku) and then `8080`. |
| `CHECK_SCHEDULER_ENABLED` | no | `true` (default) starts the background checker. `false` disables all automatic checks. |
| `CHECK_INTERVAL_SECONDS` | no | How often each URL is re-checked, default `900` (15 minutes; `60` under the `local` profile). A per-URL override column exists but no UI sets it yet. |
| `CHECK_SCHEDULER_TICK_SECONDS` | no | How often the scheduler looks for due URLs, default `15`. |
| `CHECK_SCHEDULER_INITIAL_DELAY_SECONDS` | no | Grace period before the first scheduler pass, default `20`. |
| `CHECK_BATCH_SIZE` | no | URLs claimed per scheduler pass, default `20`. |
| `CHANGE_RETENTION_PER_URL` | no | Timeline rows kept per URL, default `10` (baseline plus newest 9). |
| `CHECK_ON_CREATE` | no | `true` (default) checks a new or edited URL immediately instead of waiting for the next pass. |
| `AI_ENABLED` | no | Unset (default) runs the assistant whenever `AI_API_KEY` is set. `false` keeps it off even with a key; `true` demands a key. |
| `AI_API_KEY` | no | LLM provider credential. Setting it turns the assistant and its `/api/ai` endpoints on. Never commit it; `AI_ENABLED=true` without it fails at startup. |
| `AI_BASE_URL` | no | OpenAI-compatible API root, default `https://api.deepseek.com`. |
| `AI_MODEL` | no | Model name, default `deepseek-flash`. Must support tool calling. |
| `AI_TEMPERATURE` | no | Default `0.2`. |
| `AI_MAX_TOKENS` | no | Default `1024`. Caps the length of one answer. |
| `AI_MAX_RETRIES` | no | Default `2`. Retries a rate-limited provider call. |
| `AI_TIMEOUT_SECONDS` | no | Default `60`. |
| `AI_MAX_URLS_PER_CHECK` | no | Default `10`. URLs probed per assistant check, bounding the latency of one answer. |
| `AI_MEMORY_MAX_MESSAGES` | no | Default `20`. Messages of a conversation replayed to the model as context. |
| `AI_MAX_CONVERSATIONS` | no | Default `5`. Conversations kept per user. Past this, the least recently used conversation and its messages are deleted. |
| `AI_KNOWLEDGE_ENABLED` | no | `false` (default). `true` turns the RAG knowledge base on and gives the assistant a `searchKnowledge` tool. |
| `QDRANT_URL` | when `AI_KNOWLEDGE_ENABLED=true` | Qdrant endpoint, for example `https://xxxx.cloud.qdrant.io`. Never commit it. |
| `QDRANT_API_KEY` | when `AI_KNOWLEDGE_ENABLED=true` | Qdrant API key. Never commit it. The app refuses to start when the knowledge base is on without it. |
| `QDRANT_COLLECTION` | no | Collection name, default `urlcheck_knowledge`. Changing it means re-ingesting. |
| `AI_KNOWLEDGE_INGEST_ON_START` | no | `true` (default). Compares the knowledge files with the ledger on startup and embeds only what changed. |
| `AI_KNOWLEDGE_TOP_K` | no | Default `4`. Passages `searchKnowledge` returns. |
| `AI_KNOWLEDGE_SIMILARITY_THRESHOLD` | no | Default `0.5`. Minimum cosine similarity worth returning, so an unanswered question retrieves nothing. |
| `AI_KNOWLEDGE_MAX_CHUNK_CHARS` | no | Default `800`. Hard cap on a chunk before it is split. |

If `DB_URL` is unset the backend silently falls back to `localhost:3306`.
Set the variables explicitly in the target environment so a misconfiguration
fails loudly instead of quietly pointing at the wrong database.

### Schema

Apply the migrations in `database/` in filename order before the first start.
`006_timeline_schedule.sql` adds the scheduled-check and timeline state to an
existing database; `007_ai_conversations.sql` adds the assistant's conversation
`008_ai_knowledge.sql` adds the hash ledger for the knowledge base.
history. Both are safe to run more than once.
`001` creates the `url_monitor` database itself, so connect to `defaultdb` for
that first run and to `url_monitor` afterwards.

### Docker

The backend has a self-contained multi-stage `backend/Dockerfile`. Tests run as
part of the image build and do not touch the network.

    docker build -t url-check-backend ./backend
    docker run --rm -p 8080:8080 \
      -e DB_URL="jdbc:mysql://HOST:PORT/url_monitor?sslMode=REQUIRED&characterEncoding=utf8&serverTimezone=UTC" \
      -e DB_USERNAME=avnadmin \
      -e DB_PASSWORD="AVNS_..." \
      url-check-backend

### Frontend

`npm run build` in `frontend/` produces `dist/`. `VITE_API_BASE_URL` is baked
in at build time (see `frontend/.env.example`); leaving it empty makes the app
call the API on its own origin.

Two shapes work:

- Same origin (simplest): serve `frontend/dist/` and proxy `/api` to the
  backend. No CORS is needed and the default `SameSite=Lax` cookie works.
- Split origins: build with `VITE_API_BASE_URL=https://api.example.com`, set
  `CORS_ALLOWED_ORIGINS=https://app.example.com`, and set
  `SESSION_COOKIE_SAME_SITE=none` together with `SESSION_COOKIE_SECURE=true`.

### Render (Web Service + Static Site)

`render.yaml` describes both services; the same two can also be created by hand
in the dashboard.

Backend, a Web Service built from `backend/Dockerfile`:

- Runtime: Docker
- Dockerfile path: `backend/Dockerfile`
- Docker build context: `backend`
- Health check path: `/actuator/health`

Nothing has to be configured for the port: `application.yml` reads `PORT` when
`SERVER_PORT` is unset.

Frontend, a Static Site built from `frontend/`:

- Build command: `npm ci && npm run build`
- Publish directory: `dist`

`VITE_API_BASE_URL` is baked in at build time, so changing it needs a rebuild.

`*.onrender.com` subdomains count as separate sites, so this split-origin shape
needs `CORS_ALLOWED_ORIGINS` on the backend, and
`SESSION_COOKIE_SAME_SITE=none` together with `SESSION_COOKIE_SECURE=true`.

Apply `database/006_timeline_schedule.sql` and
`database/007_ai_conversations.sql` before the first deploy of this branch: the
scheduler, the timeline and the assistant's conversation history need them. Both
are safe to re-run.

Two Render behaviours to plan around: sessions are held in memory, so a
redeploy logs every user out; and a free instance spins down when idle, which
stops the scheduler until the next request wakes it.

### Before going live

- Rotate the Aiven password and use a dedicated user scoped to `url_monitor`
  instead of reusing `avnadmin`.
- Prefer `sslMode=VERIFY_CA` with Aiven's CA in a truststore. `REQUIRED`
  encrypts the connection but accepts any server certificate.
- Serve over HTTPS so `SESSION_COOKIE_SECURE=true` can be enabled.
- Keep `frontend/dist/`, keystores and cookie jars out of commits; see
  `.gitignore`.
