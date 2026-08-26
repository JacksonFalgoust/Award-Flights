# AwardWatch — Build Plan

Ordered so that something runs end to end early, and each phase leaves the
project in a working state. See `ARCHITECTURE.md` for the design behind these.

---

## Phase 0 — Access and reconnaissance

Do this before writing any code. If the API key doesn't materialize, the whole
plan changes.

- [x] Subscribe to seats.aero Pro and confirm the **API tab appears** in account
      settings — not every Pro account gets API access, and it's geo-restricted.
- [x] Generate the API key. Store it in an env var, never in the repo.
- [x] Hit `/partnerapi/search` with curl for a route you know has availability.
      Save the raw JSON to `docs/samples/` — you'll use it for test fixtures.
- [x] Hit `/partnerapi/availability` for one program. Save that response too.
- [x] Read the response bodies carefully and write down, in `docs/notes.md`:
      - which fields are always present vs. sometimes null
      - how mixed-cabin itineraries are represented
      - how the API signals "cached" vs "live" data and how stale the cache is
      - what a rate-limit response actually looks like
- [x] Confirm how many calls a single search consumes (some endpoints cost more
      than one) — the budgeter depends on this number being right.
- [x] Re-read the Partner API terms. Confirm your usage stays personal and
      non-commercial, and note the daily cap in the README.

---

## Phase 1 — Skeleton and domain

- [x] `spring init` — Web, Data JPA, PostgreSQL, Validation, Mail, Redis, Flyway.
- [x] Docker Compose with `postgres` and `redis` services for local dev.
- [ ] Package structure: `domain`, `ingest`, `persistence`, `alerting`, `api`.
- [ ] Write the `domain` package with **no Spring imports**:
      - [ ] `AvailabilityEntry` record
      - [ ] `Program` and `Cabin` enums (Y / W / J / F)
      - [ ] `RouteQuery` record
      - [ ] `AvailabilitySource` interface
- [ ] Add a `FakeAvailabilitySource` returning canned data. Everything downstream
      gets built and tested against this before real HTTP is involved.

---

## Phase 2 — Ingest

- [ ] `SeatsAeroClient` using `RestClient`, `Partner-Authorization` header from config.
- [ ] `SeatsAeroSource implements AvailabilitySource` — mapping layer only.
- [ ] Unit tests for the mapper using the Phase 0 saved JSON. Cover the ugly
      cases: null mileage, zero seats, mixed cabin, multi-segment.
- [ ] Error handling: distinguish 401 (bad key), 429 (rate limit), 5xx (retry),
      and map each to a typed exception. Don't let a 429 look like "no seats."
- [ ] Retry with exponential backoff on 429/5xx only.
- [ ] Verify against the live API and compare one result by hand with the
      seats.aero website. If they disagree, the mapper is wrong.

---

## Phase 3 — Persistence

- [ ] Flyway migration V1: `app_user`, `watch`, `snapshot`, `availability_entry`,
      `crawl_state`, `alert_event`.
- [ ] JPA entities + Spring Data repositories.
- [ ] `SnapshotService.record(route, entries, callsUsed)` — writes one snapshot
      plus its entries in a single transaction. **Append only; never update an
      entry in place.**
- [ ] Repository method: newest two *successful* snapshots for a route.
      The diff engine needs exactly this and nothing else.
- [ ] Integration test with Testcontainers (or a throwaway Compose DB).

---

## Phase 4 — Quota budgeting

- [ ] `QuotaBudgeter` backed by Redis key `quota:{UTC date}`.
      - [ ] `tryReserve(int calls)` — atomic `DECRBY`, returns false if it would
            go negative. Reserve *before* the call, refund on transport failure.
      - [ ] Reserve floor: refuse automated spend below 10% remaining so manual
            searches still work.
      - [ ] TTL on the key so old days expire on their own.
- [ ] `@Scheduled` `CrawlScheduler` running every 5 minutes.
- [ ] Route scoring: `urgency * staleness * hitRate` (formula in ARCHITECTURE §5).
      - [ ] Unit test the scorer directly — it's pure logic, no excuse not to.
- [ ] Redis response cache with 15-minute TTL, keyed by normalized query.
- [ ] Distributed lock per route so two ticks can't crawl the same route at once.
- [ ] Log every call with its cost. You need to see where quota went.

---

## Phase 5 — Alerting

- [ ] `DiffEngine.diff(previous, current)` → `List<AvailabilityChange>`.
      - [ ] Key on `(departureDate, program, cabin)`.
      - [ ] Classify NEW / CHEAPER / MORE_SEATS / GONE.
      - [ ] Unit tests, including: empty→populated, populated→empty,
            price drop, seat count change, identical snapshots (must yield zero).
- [ ] **Guard against the false-positive trap:** a failed or empty-because-of-error
      snapshot must never serve as a diff baseline. Flag failed snapshots and skip them.
- [ ] Watch filtering: cabins, programs, `max_mileage`, `min_seats`.
- [ ] Cooldown suppression via `alert_event` (default 24h per entry per watch).
- [ ] `Notifier` interface + `EmailNotifier` (SMTP). Batch all changes for one
      watch into a single message.
- [ ] End-to-end test: seed old snapshot → ingest new one → assert exactly one
      email with the expected contents.

---

## Phase 6 — REST API

- [ ] `GET /api/search` — serve cached first, spend quota only if stale.
      Always return an `asOf` timestamp so the UI can show data age.
- [ ] `GET/POST/PATCH/DELETE /api/watches`.
- [ ] `GET /api/watches/{id}/history` — time series for charts.
- [ ] `GET /api/admin/quota` — calls used today, remaining, queue depth.
- [ ] Auth: Spring Security with a single user and form login. Don't build
      multi-tenant auth for an app with one user.
- [ ] Bean validation on all request DTOs (valid IATA codes, sane date ranges).
- [ ] Global `@ControllerAdvice` for consistent error responses.

---

## Phase 7 — Frontend

- [ ] Vite + React + Tailwind scaffold.
- [ ] Search page: origin/destination/date-range/cabin → results table grouped
      by date, sorted by mileage cost.
- [ ] Prominent "data as of X minutes ago" indicator. Staleness is a feature of
      this app, so surface it rather than hiding it.
- [ ] Watches page: create, list, pause, delete.
- [ ] Watch detail: mileage-over-time chart from the history endpoint.
- [ ] Quota widget on an admin/status page.
- [ ] Empty and error states — "no availability" and "couldn't reach the API"
      must look different to the user.

---

## Phase 8 — Deploy and operate

- [ ] Dockerfile for the backend; multi-stage build.
- [ ] Compose file: app + postgres + redis, with volumes.
- [ ] Externalized config; secrets via env only.
- [ ] `/actuator/health` including an API-key validity check.
- [ ] Nightly Postgres dump to a mounted volume.
- [ ] Retention job: roll snapshots older than 90 days into daily aggregates.
- [ ] Run it for two weeks against real watches and read the logs. Tune the
      scoring weights based on where quota actually went.

---

## Phase 9 — Portfolio polish

- [ ] README: problem statement, screenshot, architecture diagram, run instructions.
- [ ] Note the non-commercial API constraint explicitly — it shows you read the terms.
- [ ] Short write-up of the two decisions worth defending in an interview:
      quota budgeting under a hard cap, and diff-based alerting with false-positive
      suppression.
- [ ] Make sure `AvailabilitySource` is genuinely swappable — if a second
      implementation would require touching `alerting` or `api`, fix the seam.

---

## Deliberately out of scope

- Booking or payment flows.
- Multi-user accounts and billing.
- Direct airline scraping (different project, different legal footing).
- Sub-minute freshness — the quota forbids it.
