# AwardWatch — Architecture

A self-hosted award flight availability tracker. Aggregates redemption
inventory across 20+ mileage programs via the seats.aero Partner API,
stores it as a time series, and alerts users when new seats appear on
routes they care about.

---

## 1. Goals and non-goals

**Goals**

- Search award availability across many programs from one interface.
- Notify a user when *new* inventory appears on a saved route — not on every poll.
- Operate correctly inside a hard quota of ~1,000 API calls per day.
- Keep availability history so release patterns can be analyzed later.

**Non-goals**

- Booking. AwardWatch links out to the airline; it never transacts.
- Commercial operation. The seats.aero Partner API is licensed for
  non-commercial personal use unless you have a written agreement with them.
  This project is built and deployed for a single user.
- Real-time (sub-minute) freshness. The quota makes that impossible, and
  designing around that constraint is the interesting part.

---

## 2. System overview

```
                        ┌──────────────────────────┐
                        │   seats.aero Partner API │
                        └────────────▲─────────────┘
                                     │ HTTPS (quota-limited)
                        ┌────────────┴─────────────┐
                        │      ingest module       │
                        │  ┌────────────────────┐  │
                        │  │  QuotaBudgeter     │  │
                        │  │  CrawlScheduler    │  │
                        │  │  SeatsAeroSource   │  │
                        │  └────────────────────┘  │
                        └────────────┬─────────────┘
                                     │ normalized AvailabilityEntry
              ┌──────────────────────┼──────────────────────┐
              │                      │                      │
     ┌────────▼────────┐   ┌─────────▼────────┐   ┌─────────▼─────────┐
     │   PostgreSQL    │   │      Redis       │   │  alerting module  │
     │  snapshots,     │   │  response cache, │   │   DiffEngine      │
     │  watches, users │   │  crawl locks     │   │   Notifier        │
     └────────▲────────┘   └──────────────────┘   └─────────┬─────────┘
              │                                             │
     ┌────────┴─────────────────────────────────────────────▼─────────┐
     │                    REST API (Spring Web)                       │
     └────────────────────────────▲───────────────────────────────────┘
                                  │ JSON
                     ┌────────────┴─────────────┐
                     │  React + Vite + Tailwind │
                     └──────────────────────────┘
```

---

## 3. Modules

Single Gradle/Maven project, packages under `com.awardwatch`.

### `domain`

Pure model classes and interfaces. No Spring annotations, no JPA, no HTTP.
Everything else depends on this; it depends on nothing.

```java
// A single bookable award option at a point in time.
public record AvailabilityEntry(
        String origin,          // IATA, e.g. "ATL"
        String destination,     // IATA, e.g. "NRT"
        LocalDate departureDate,
        Program program,        // AEROPLAN, UNITED, ...
        Cabin cabin,            // ECONOMY / PREMIUM_ECONOMY / BUSINESS / FIRST
        int mileageCost,        // miles required, one-way, per passenger
        int seatsRemaining,     // 0 means "shown but not bookable"
        boolean nonstop,        // this award on a nonstop; connecting is its own entry
        Instant observedAt,     // when we saw this, not when the source refreshed it
        Instant refreshedAt     // when the source last refreshed it; null if unreported
) {}
```

**`nonstop`, not `directOnly`.** "Only direct" is a constraint a caller imposes on
a search — it belongs on `RouteQuery` and on `watch`, not on an observation. What
this field records is a fact about the award itself: the itinerary has no
connection. The distinction is not cosmetic, because seats.aero prices the two as
separate products — `JDirectMileageCost` (nonstop) and `JMileageCost`
(nonstop-or-connecting) can differ on the same record. One `AvailabilityEntry` is
therefore one (cabin x nonstop) pair, which is why `nonstop` is part of the §6 diff
key.

**Two timestamps.** `observedAt` is when *we* pulled the data; `refreshedAt` is when
the upstream source last re-crawled that route/date/program. Both are needed because
`/search` and `/availability` are cache reads: their per-record `UpdatedAt` ages
ranged from ~3 hours to ~5.75 days in the sample pull (`docs/notes.md`), so
`observedAt` alone says nothing about how old the *inventory* is. Staleness is
`observedAt - refreshedAt`, and `CONTEXT.md` requires it be surfaced rather than
hidden.

`refreshedAt` is nullable, and the null means precisely *the source did not report
one* — never "assume fresh." Defaulting it to `observedAt` would compute a staleness
of zero and claim perfect freshness for data of unknown age, the single worst
default available for this field. `SeatsAeroSource` always has a real value to put
here (`UpdatedAt` is documented always-present); a future live-query source may
legitimately set it equal to `observedAt`, because there the data really is that
fresh.

**Cabin letters.** `Y` / `W` / `J` / `F` are IATA booking-class codes used as
cabin shorthand: `Y` economy, `W` premium economy, `J` business, `F` first.
(`W` is the odd one — historically a *discounted economy* fare bucket, but the
industry, and seats.aero with it, settled on `W` as the premium-economy
indicator.) seats.aero uses the letters as response field prefixes —
`YAvailable`, `JMileageCost`, `FRemainingSeats` — and they are what
`watch.cabins` and `availability_entry.cabin` store in §4.

The enum constants are spelled out rather than named after the letters:

```java
public enum Cabin {
    ECONOMY('Y'),
    PREMIUM_ECONOMY('W'),
    BUSINESS('J'),
    FIRST('F');
    // code() returns the IATA letter
}
```

`Cabin.J` reads as nothing to anyone who isn't already fluent in fare codes,
while the letter is still needed for field-prefix mapping and for the `CHAR(1)`
column. The letter belongs in `domain` — it's industry nomenclature, not one
vendor's. seats.aero's full-word query-param spelling (`cabin=economy` on
`/availability`) *is* vendor-specific and stays in `SeatsAeroSource`.

**Program naming.** seats.aero's concepts page lists each mileage partner with a
`Source` column (`aeroplan`, `virginatlantic`) and a `Mileage Program` column
("Air Canada Aeroplan", "Virgin Atlantic Flying Club"). These are not two
concepts — the docs state that a source *is* a single mileage program — so
`Program` models the one concept, and neither column dictates the constant names.

The constants are named after the program's own brand, and the seats.aero slug is
not on the enum:

```java
public enum Program {
    AEROPLAN("Air Canada Aeroplan"),
    AADVANTAGE("American Airlines AAdvantage"),
    MILEAGE_PLAN("Alaska Mileage Plan"),
    SKYMILES("Delta SkyMiles"),
    FLYING_CLUB("Virgin Atlantic Flying Club");
    // displayName() returns the human-readable program name
}
```

Same rule as `Cabin`. `"virginatlantic"` as one lowercase token is seats.aero's
spelling, not the industry's, so it is vendor-specific and lives in
`SeatsAeroSource` beside the `cabin=economy` mapping — an `EnumMap<Program,String>`
out and a `Map<String,Program>` back. A second `AvailabilitySource` would bring its
own identifiers and has no claim on a privileged `code()` on the domain enum.

Brand, not airline: seats.aero's slug is `delta`, but Delta the airline and
SkyMiles the program are different things, and watches filter on the program.

The reverse lookup returns `Optional<Program>`, and the mapper skips and logs slugs
it doesn't recognize — seats.aero adds programs. There is deliberately no `UNKNOWN`
constant: it would reach `availability_entry.program` and then the §6 diff key,
where two different unrecognized programs collapse into one row and produce false
`CHEAPER` alerts.

`Program.name()` is what `availability_entry.program` and `watch.programs` store,
so it is a persisted identifier — renaming a constant is a data migration over
append-only history, not a refactor. See `docs/adr/0001-program-naming.md`.

The key abstraction:

```java
// Anything that can answer "what award seats exist on this route?"
// SeatsAeroSource implements this today. A direct airline crawler could
// implement it later without the rest of the app changing.
public interface AvailabilitySource {

    List<AvailabilityEntry> searchRoute(RouteQuery query);

    List<AvailabilityEntry> bulkByProgram(Program program, Region region);

    // How many API calls the source expects the above to consume, so the
    // budgeter can reserve quota before the call is made.
    int estimatedCost(RouteQuery query);
}
```

### `ingest`

- **`SeatsAeroClient`** — thin HTTP wrapper. Base URL `https://seats.aero/partnerapi/`,
  auth via the `Partner-Authorization` header. Knows nothing about scheduling.
- **`SeatsAeroSource`** — implements `AvailabilitySource`. Maps seats.aero's
  response shape onto `AvailabilityEntry`. This is where per-program quirks
  get flattened (cabin code differences, taxes/fees in mixed currencies,
  mixed-cabin itineraries reported as a single cabin).
- **`QuotaBudgeter`** — see §5.
- **`CrawlScheduler`** — a Spring `@Scheduled` job that wakes every N minutes,
  asks the budgeter how many calls it may spend, pulls that many routes off
  the priority queue, and dispatches them.

### `persistence`

Spring Data JPA repositories. Postgres. Flyway for migrations.

### `alerting`

- **`DiffEngine`** — compares the newest snapshot for a watch against the
  previous one and emits `AvailabilityChange` events. See §6.
- **`Notifier`** — sends email (JavaMailSender / SMTP). Interface-backed so a
  Discord or push notifier can be added without touching the diff logic.

### `api`

Spring Web controllers. Thin — validation, DTO mapping, delegate to services.

### `web`

Separate React/Vite/Tailwind app. Talks to the REST API only.

---

## 4. Data model

```sql
-- A user's standing interest in a route. Drives crawl priority AND alerts.
CREATE TABLE watch (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES app_user(id),
    origin          CHAR(3) NOT NULL,
    destination     CHAR(3) NOT NULL,
    date_from       DATE NOT NULL,
    date_to         DATE NOT NULL,
    cabins          TEXT[] NOT NULL,          -- {'J','F'}
    programs        TEXT[],                   -- NULL = all programs
    max_mileage     INTEGER,                  -- alert only below this
    min_seats       SMALLINT DEFAULT 1,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One crawl of one route. Groups the entries that were seen together, so a
-- diff compares like against like.
CREATE TABLE snapshot (
    id              BIGSERIAL PRIMARY KEY,
    origin          CHAR(3) NOT NULL,
    destination     CHAR(3) NOT NULL,
    observed_at     TIMESTAMPTZ NOT NULL,
    api_calls_used  SMALLINT NOT NULL,
    source          TEXT NOT NULL             -- 'seats.aero'
);

-- The rows are append-only. Never UPDATE an entry; a changed price is a new
-- entry in a new snapshot. This is what makes history and diffing possible.
CREATE TABLE availability_entry (
    id              BIGSERIAL PRIMARY KEY,
    snapshot_id     BIGINT NOT NULL REFERENCES snapshot(id),
    departure_date  DATE NOT NULL,
    program         TEXT NOT NULL,
    cabin           CHAR(1) NOT NULL,
    mileage_cost    INTEGER NOT NULL,
    seats_remaining SMALLINT NOT NULL,
    nonstop         BOOLEAN NOT NULL,
    refreshed_at    TIMESTAMPTZ               -- source's own last-refresh; NULL = unreported
);

CREATE INDEX ON availability_entry (snapshot_id);
CREATE INDEX ON snapshot (origin, destination, observed_at DESC);

-- Crawl bookkeeping: when each route was last fetched and how urgent it is.
CREATE TABLE crawl_state (
    origin          CHAR(3) NOT NULL,
    destination     CHAR(3) NOT NULL,
    last_crawled_at TIMESTAMPTZ,
    consecutive_empty SMALLINT NOT NULL DEFAULT 0,
    PRIMARY KEY (origin, destination)
);

-- What we already told the user about, so we don't tell them twice.
CREATE TABLE alert_event (
    id              BIGSERIAL PRIMARY KEY,
    watch_id        BIGINT NOT NULL REFERENCES watch(id),
    entry_id        BIGINT NOT NULL REFERENCES availability_entry(id),
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    channel         TEXT NOT NULL
);
```

**Retention.** Append-only growth is fine at personal scale (a few thousand
rows/day), but add a monthly job that rolls snapshots older than 90 days into
a daily aggregate and drops the raw entries.

---

## 5. Quota budgeting

The constraint that shapes the whole system: **1,000 calls/day**, resetting at
midnight UTC. That is roughly one call every 86 seconds. Naive round-robin
over saved watches wastes most of it on routes nobody is waiting on.

**Approach: token bucket + priority queue.**

`QuotaBudgeter` holds a daily allowance in Redis (`quota:YYYY-MM-DD`), decremented
atomically per call so a crash mid-run can't double-spend. It refuses to hand out
the last ~10% so a user-triggered manual search always has room.

`CrawlScheduler` scores every route with an active watch:

```
score = urgency * staleness * hitRate
```

- `urgency` — days until the watch's date window opens. Departures inside 21
  days score higher; award inventory tends to move near departure.
- `staleness` — minutes since `last_crawled_at`, capped so an ignored route
  eventually rises to the top regardless.
- `hitRate` — decayed rate at which this route has produced non-empty results.
  Routes that return nothing 20 times running get demoted, not dropped.

Highest scores are crawled first until the budget for that tick is spent.

**Redis caching.** Identical route+date queries within a TTL (15 min) serve from
cache and cost zero quota. This mostly protects against a user hammering the
search box, and against overlapping watches on the same route.

---

## 6. Diff-based alerting

Polling gives you the *current state*. Users want the *change*. Firing on
current state means re-emailing the same seat every hour until it's gone.

For each active watch, after a new snapshot lands:

1. Load the newest snapshot for the route, and the one before it.
2. Build a key for every entry: `(departureDate, program, cabin, nonstop)`.
   `nonstop` is part of the key because the nonstop and connecting awards in one
   cabin are separately priced products. Without it the two collapse into one
   row and a connection replacing a nonstop reads as a **CHEAPER** alert.
3. Classify:
   - key in new, absent from old → **NEW**
   - key in both, `mileageCost` dropped by more than a threshold → **CHEAPER**
   - key in both, `seatsRemaining` increased → **MORE_SEATS**
   - key in old, absent from new → **GONE** (recorded, not alerted)
4. Filter by the watch's `cabins`, `programs`, `max_mileage`, `min_seats`.
5. Suppress anything already in `alert_event` for this watch within a cooldown
   window (default 24h), so a seat that flickers in and out doesn't spam.
6. Batch surviving changes into one email per watch per run.

**Edge case worth handling:** a snapshot that fails or returns empty because of
an API error must not be treated as "everything disappeared" — and then, on the
next successful run, as "everything is new." Mark failed snapshots and skip them
as a diff baseline.

---

## 7. REST surface

```
POST   /api/auth/login
GET    /api/search?origin=ATL&destination=NRT&from=2026-03-01&to=2026-03-31&cabin=J
GET    /api/watches
POST   /api/watches
PATCH  /api/watches/{id}          # pause/resume, edit filters
DELETE /api/watches/{id}
GET    /api/watches/{id}/history  # time series for charting
GET    /api/admin/quota           # calls used today, queue depth
```

`/api/search` serves from Postgres/Redis first and only spends quota if the
cached snapshot is older than the TTL.

---

## 8. Technology choices

| Concern | Choice | Why |
|---|---|---|
| Language / framework | Java 21, Spring Boot 3 | Existing strength; `@Scheduled` and Spring Data remove boilerplate |
| Database | PostgreSQL | Array columns for `cabins`/`programs`, good time-series indexing |
| Cache / counters | Redis | Atomic decrement for quota; TTL cache is free |
| Migrations | Flyway | Schema in version control |
| HTTP client | Spring `RestClient` | Built in, no extra dependency |
| Frontend | React + Vite + Tailwind | Existing stack |
| Deployment | Docker Compose on a small VPS | One user, three containers |

---

## 9. Failure modes

| Failure | Handling |
|---|---|
| Quota exhausted | Scheduler idles; search endpoints serve stale data with an `asOf` timestamp |
| seats.aero 429 | Exponential backoff, mark snapshot failed, do not consume diff baseline |
| seats.aero 5xx / down | Circuit breaker; alerts pause rather than fire on empty results |
| API key revoked | Health check fails loudly; app stays up in read-only historical mode |
| Duplicate alerts | `alert_event` cooldown check before every send |
| Clock/quota reset skew | Quota key derived from UTC date, matching the provider's reset |

---

## 10. Extension points

- **A second `AvailabilitySource`.** The interface exists precisely so a direct
  airline crawler can be added without the domain, alerting, or API layers
  knowing. Multiple sources would need a merge/dedup step in `ingest`.
- **Historical analysis.** The append-only table already supports asking which
  programs release seats on which weekdays, and how far out.
- **Point valuation.** Join mileage cost against cash fare to rank redemptions
  by cents-per-point rather than raw miles.
