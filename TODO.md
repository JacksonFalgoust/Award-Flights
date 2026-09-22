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
- [x] Package structure: `domain`, `ingest`, `persistence`, `alerting`, `api`.
- [x] Write the `domain` package with **no Spring imports**:
      - [x] `AvailabilityEntry` record
      - [x] `Program` and `Cabin` enums. `Cabin` constants are spelled out —
            `ECONOMY` / `PREMIUM_ECONOMY` / `BUSINESS` / `FIRST` — each carrying
            its IATA letter (Y / W / J / F, seats.aero's field prefixes) via
            `code()`. See `ARCHITECTURE.md` §3 for why, and note `W` means
            premium economy, not discounted economy.
      - [x] `AirportCode` and `Route` value types. Validation lives here once, not
            copy-pasted into every record holding a pair of strings — which is
            exactly how `RouteQuery` and `AvailabilityEntry` drifted apart on the
            same-airport check. Keep them out of `persistence` and `api`: those
            layers hold `String`/`CHAR(3)` and convert at their existing mapping
            boundary, so no JPA converter or Jackson serializer is needed.
      - [x] `DateRange` record, capped at `MAX_DAYS = 90`. `intersection()` is
            what Phase 5 needs to scope a diff to dates both snapshots covered.
      - [x] `RouteQuery` record — `(Route, DateRange, Program)`. **No cabin:**
            sources return every cabin in one response, so a per-cabin query
            multiplies quota spend without narrowing the payload.
      - [x] `Snapshot` record — `(RouteQuery, Instant fetchedAt, List<AvailabilityEntry>)`.
            Carries the query so the `DateRange` actually fetched travels with the
            result, which is what Phase 5 needs `intersection()` for. The
            constructor rejects entries that do not belong to the query — wrong
            route or program, date outside the range, `observedAt` after the fetch,
            or a duplicate `AwardKey` — so a mapper bug is caught at the edge
            rather than reaching the diff engine as inventory that moved.
      - [x] `AvailabilitySourceException` — checked, built by the named factories
            `retryable(query, msg)` and `permanent(query, msg)` rather than a
            constructor taking a boolean. Carries the failed `RouteQuery` for the
            layer that fans a Watch out into many queries.
            - [x] Add `serialVersionUID`. `Throwable` is `Serializable`, so javac
                  warns and the JVM otherwise derives a value that changes whenever
                  a factory is added. One line. While there: `query()` is documented
                  "never null" but is `transient`, and deserialization bypasses the
                  constructor — either soften the wording or accept it as moot.
      - [x] `AvailabilitySource` interface — `Snapshot fetch(RouteQuery) throws
            AvailabilitySourceException` plus `boolean supports(Program)`.
            `bulkByProgram(Program, Region)` was **dropped**, not deferred: no
            `Region` type was needed, and Phase 2 can add a bulk path if the
            per-route fan-out proves too expensive. `estimatedCost(RouteQuery)`
            was dropped too — see the open question in `docs/notes.md` about where
            quota estimation lives now.
- [x] Domain unit tests — 200 of them, in `src/test/java/com/awardwatch/domain/`.
      Mutation-checked rather than assumed: breaking the duplicate-`AwardKey` guard,
      the 90-day cap and the no-Spring rule each failed exactly the test meant to
      catch it. Watch for assertions that build an AssertJ assert and never call a
      terminal method — `assertThat(range.contains(d));` always passes.
      - [x] `AirportCode`: lower case canonicalizes, 2/4 letters and digits rejected.
            The Turkish-locale case earns its keep — a default-locale `toUpperCase`
            turns `ist` into `İST`, which fails `[A-Z]{3}`.
      - [x] `Route`: same-airport rejected, including across case; `ATL-NRT` does not
            equal `NRT-ATL`.
      - [x] `DateRange`: `single()` has length 1, exactly 90 days passes, 91 throws,
            `dates()` includes `end`, `intersection()` is empty when disjoint — and a
            one-day overlap is a length-1 range, not empty, which is the boundary
            between "diffable" and "cannot be diffed at all".
      - [x] `AvailabilityEntry`: negative miles/seats rejected, zero of either
            accepted, `refreshedAt` after `observedAt` rejected, `staleness()` empty
            when `refreshedAt` is null. `AwardKey` ignores price and seats — that is
            what pairs an entry with its predecessor — and is deliberately blind to
            route, which is only safe because a Snapshot spans exactly one Route.
      - [x] `Snapshot`: entry on a different route/program rejected, entry outside
            the queried `DateRange` rejected, duplicate `AwardKey` rejected,
            `observedAt` after `fetchedAt` rejected. An **empty** entry list passes —
            that is the "asked, nothing available" case and the whole reason failure
            throws instead. `entries` is unmodifiable, and mutating the caller's list
            afterwards does not affect the Snapshot. Entries differing only by cabin,
            `nonstop` or departure date are accepted, so the duplicate check cannot
            over-reject an ordinary multi-cabin response.
      - [x] `RouteQuery`: null components rejected, value equality and `hashCode`
            hold — Phase 4 uses it as the Redis cache key.
      - [x] `Cabin` and `Program`: cabin letters are Y/W/J/F and distinct; every
            program has a non-blank display name and no two are the same.
      - [x] `AvailabilitySourceException`: each factory sets `retryable` the way its
            name claims, the query and cause survive, and the class is **not** a
            `RuntimeException` — the checked-ness is the entire argument for the type.
      - [x] `DomainPackageTest`: scans the package's sources and fails on any
            `org.springframework`, JPA, Jackson or Hibernate import. The "depends on
            nothing" promise in `package-info` was until now enforced by nothing.
            It reads sources by relative path, so it assumes the Gradle project is the
            working directory; swap it for ArchUnit if that ever bites.
- [x] Docs housekeeping, now that the domain vocabulary has grown:
      - [x] Write `docs/adr/0001-program-naming.md` — `ARCHITECTURE.md` §3 cites it
            and neither the file nor the `docs/adr/` directory exists.
      - [x] Add **Airport Code** and **Date Range** entries to `CONTEXT.md`. It
            defines Route but not the two types Route is now built from.
- [x] Add a `FakeAvailabilitySource` returning canned data. Everything downstream
      gets built and tested against this before real HTTP is involved. Its own tests
      are the one Phase 1 test file still unwritten, and they pin the two halves of
      the `AvailabilitySource` contract that the interface can only state in prose:
      an unconfigured route returns an **empty** Snapshot rather than throwing, an
      unsupported `Program` throws a **permanent** failure rather than returning
      empty, and the Snapshot comes back carrying the query it was handed.

---

## Phase 2 — Ingest

- [x] `SeatsAeroClient` using `RestClient`, `Partner-Authorization` header from config.
- [x] `SeatsAeroSource implements AvailabilitySource` — mapping layer only.
- [x] Unit tests for the mapper using the Phase 0 saved JSON. Cover the ugly
      cases: null mileage, zero seats, mixed cabin, multi-segment.
- [x] Error handling: distinguish 401 (bad key), 429 (rate limit), 5xx (retry),
      and map each to a typed exception. Don't let a 429 look like "no seats."
- [x] Retry with exponential backoff on 429/5xx only.
- [x] Verify against the live API and compare one result by hand with the
      seats.aero website. If they disagree, the mapper is wrong.

---

## Phase 3 — Persistence

- [x] Flyway migration V1: `app_user`, `watch`, `snapshot`, `availability_entry`,
      `crawl_state`, `alert_event`.
      - [x] `snapshot` carries `date_from`, `date_to`, `program` and `succeeded`
            (ARCHITECTURE §4). The date span is not decoration: without it Phase 5
            cannot intersect two snapshots' ranges, and a narrowed range reads as
            every award on the dropped dates going **GONE**.
      - [x] Key `crawl_state` by
            `(origin, destination, program, date_from, date_to)`, matching the full
            identity of the `RouteQuery` being scheduled.
- [x] JPA entities + Spring Data repositories.
- [x] `SnapshotService.record(snapshot, callsUsed, source)` — writes one snapshot
      plus its entries in a single transaction. **Append only; never update an
      entry in place.**
- [x] Repository method: newest two *successful* snapshots for one
      `(route, program)`. The diff engine needs exactly this and nothing else —
      note it is keyed on program too, since snapshots for different programs are
      different fetches and never diff against each other.
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
      - [ ] Split a `watch` window longer than `DateRange.MAX_DAYS` (90) into
            several `RouteQuery` objects. `DateRange` rejects a wider span at
            construction, so an unsplit long watch is a hard failure, not a slow one.
- [ ] Route scoring: `urgency * staleness * hitRate` (formula in ARCHITECTURE §5).
      - [ ] Unit test the scorer directly — it's pure logic, no excuse not to.
- [ ] Redis response cache with 15-minute TTL, keyed by normalized query.
- [ ] Distributed lock per route so two ticks can't crawl the same route at once.
- [ ] Log every call with its cost. You need to see where quota went.

---

## Phase 5 — Alerting

- [ ] `DiffEngine.diff(previous, current)` → `List<AvailabilityChange>`.
      - [ ] Scope to `previous.dateRange().intersection(current.dateRange())` before
            keying anything. Dates only one snapshot fetched were never compared,
            and counting them reads a narrowed range as a mass **GONE**. An empty
            intersection means the pair cannot be diffed at all — not that
            everything changed.
      - [ ] Key on `(departureDate, program, cabin, nonstop)` — the nonstop and
            connecting awards in one cabin are separately priced products.
      - [ ] Classify NEW / CHEAPER / MORE_SEATS / GONE.
      - [ ] Unit tests, including: empty→populated, populated→empty,
            price drop, seat count change, identical snapshots (must yield zero),
            and a narrowed date range (must yield zero, not a wave of **GONE**).
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
