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

2. Run the backend (port 8080). Activate the `local` profile so the scheduler
   re-checks every 60 seconds while you test:

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
| `CHECK_INTERVAL_SECONDS` | no | How often each URL is re-checked, default `3600` (`60` under the `local` profile). A per-URL override column exists but no UI sets it yet. |
| `CHECK_SCHEDULER_TICK_SECONDS` | no | How often the scheduler looks for due URLs, default `15`. |
| `CHECK_SCHEDULER_INITIAL_DELAY_SECONDS` | no | Grace period before the first scheduler pass, default `20`. |
| `CHECK_BATCH_SIZE` | no | URLs claimed per scheduler pass, default `20`. |
| `CHANGE_RETENTION_PER_URL` | no | Timeline rows kept per URL, default `10` (baseline plus newest 9). |
| `CHECK_ON_CREATE` | no | `true` (default) checks a new or edited URL immediately instead of waiting for the next pass. |

If `DB_URL` is unset the backend silently falls back to `localhost:3306`.
Set the variables explicitly in the target environment so a misconfiguration
fails loudly instead of quietly pointing at the wrong database.

### Schema

Apply the migrations in `database/` in filename order before the first start.
`006_timeline_schedule.sql` adds the scheduled-check and timeline state to an
existing database; it is safe to run more than once.
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

Apply `database/006_timeline_schedule.sql` before the first deploy of this
branch: the scheduler and the timeline need it. It is safe to re-run.

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
