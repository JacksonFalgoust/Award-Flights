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
                                     │ Snapshot of AvailabilityEntry
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
// A 3-letter IATA airport code. Canonicalized upper case; cannot be invalid.
public record AirportCode(String value) {}

// A directional origin/destination pair. ATL-NRT is not NRT-ATL.
public record Route(AirportCode origin, AirportCode destination) {}

// A closed span of departure dates, inclusive of both endpoints. Capped at 90 days.
public record DateRange(LocalDate start, LocalDate end) {}

// A single bookable award option at a point in time.
public record AvailabilityEntry(
        Route route,            // ATL-NRT, directional
        LocalDate departureDate,
        Program program,        // AEROPLAN, UNITED, ...
        Cabin cabin,            // ECONOMY / PREMIUM_ECONOMY / BUSINESS / FIRST
        int mileageCost,        // miles required, one-way, per passenger
        Integer seatsRemaining, // null means available with an unknown count; 0 means none
        boolean nonstop,        // this award on a nonstop; connecting is its own entry
        Instant observedAt,     // when we saw this, not when the source refreshed it
        Instant refreshedAt     // when the source last refreshed it; null if unreported
) {}

// One call's worth of work against an Availability Source.
public record RouteQuery(Route route, DateRange departureDates, Program program) {}

// Everything one source returned for one RouteQuery, taken as a single reading.
// The unit the diff engine compares and the append-only history stores.
public record Snapshot(RouteQuery query, Instant fetchedAt, List<AvailabilityEntry> entries) {}
```

**Value types, not strings.** `AirportCode` and `Route` exist so that "this is a valid
airport code" and "origin differs from destination" are proved once, at construction,
instead of re-checked by every record that holds a pair of strings. They were extracted
after exactly that drift appeared: `AvailabilityEntry` rejected `ATL to ATL` and
`RouteQuery` did not, because the check had been copy-pasted into one and not the other.

Canonicalizing to upper case inside `AirportCode` is what makes `equals` a correct
identity test, so `atl` from a query string and `ATL` from a source response compare
equal. Nothing outside `AirportCode` may compare airport codes as raw strings.

These stay out of `persistence` and `api`: the entities and DTOs in those layers hold
`String`/`CHAR(3)` and convert at the mapping boundary they already have. No JPA
`AttributeConverter` and no Jackson serializer is required, which is what keeps the
`domain` package free of Spring.

**`RouteQuery` carries a date range, not a single date, and no cabin.** A source returns
every cabin for a given route/date/program in one response, so querying per cabin
multiplies calls against a quota without narrowing the payload. Cabin is a dimension of
the *result*: it lives on `AvailabilityEntry` and in the §6 diff key. `Program` is the
opposite case and stays on the query, because it selects which source is called.

`DateRange` is capped at `MAX_DAYS = 90`. Against a source that is not calendar-native, a
range fans out into one upstream call per date, so an uncapped range is an uncapped burst
at a rate-limited API — airlines publish ~330 days out, and a 330-call fan-out gets the
key throttled or banned partway through. The cap turns that into a construction-time
error at the edge of the system. A `watch` window longer than 90 days is therefore split
into several `RouteQuery` objects by the scheduler.

`DateRange.intersection` exists for §6: a diff must be scoped to the dates *both*
snapshots actually covered, or a narrowed range reads as every award vanishing.

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
    ALASKA_MILEAGE_PLAN("Alaska Mileage Plan"),
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

**`AwardKey`.** The identity of the *award* an entry observed, stripped of the
observation: `(departureDate, program, cabin, nonstop)`, nested inside
`AvailabilityEntry`. It exists because the record's generated `equals` spans
`mileageCost` and `observedAt`, which makes an award whose price moved unequal to
itself and therefore useless for pairing one snapshot's entries against the last
one's. Origin and destination are deliberately absent: a diff compares two snapshots
of the same route, so they are constant across the comparison. Keys from different
routes are not comparable, and collecting them into one map merges unrelated awards.
This is the §6 diff key.

`AvailabilityEntry.staleness()` returns `Optional<Duration>` — `observedAt -
refreshedAt`, empty when the source reported no refresh time. Empty means *unknown*,
never zero. The value is fixed for the life of the entry and does not grow as the row
ages; "how old is this data right now" is a different quantity, computed against
`observedAt` at request time by the layer that renders it.

The key abstraction:

```java
// Anything that can answer "what award seats exist on this route?"
// SeatsAeroSource implements this today. A direct airline crawler could
// implement it later without the rest of the app changing.
public interface AvailabilitySource {

    // One query, one reading.
    Snapshot fetch(RouteQuery query) throws AvailabilitySourceException;

    // Which mileage programs this source can price, so a registry can route a
    // query to it. Answered from a constant; never calls upstream.
    boolean supports(Program program);
}
```

**`fetch` returns a `Snapshot`, not a `List`.** The snapshot carries the `RouteQuery`
that produced it, so the `DateRange` *actually fetched* travels with the result. That
is what makes `DateRange.intersection` usable in §6: two snapshots taken over
different windows can be diffed over the dates both covered. A bare list loses the
distinction between "no award on 14 March" and "14 March was never fetched," and the
diff engine reads the second as the first.

The returned snapshot must carry the query it was given, unchanged. Nothing
downstream can detect a substitution — `Snapshot` validates its entries against
whatever query it holds, so a source that quietly narrows the range and reports the
narrowed one produces a snapshot that is internally consistent and wrong. It then
diffs over the intersection of the two ranges, the dropped dates simply leave the
comparison, and inventory that was never checked reads as inventory that never
changed. **A range that cannot be priced in full is a failure, not a smaller
success.**

**One query, one call, one `Snapshot`.** A source that cannot price a whole
`DateRange` in a single upstream request fans it out internally and merges the
responses. That is why `MAX_DAYS` is capped where it is, and why the cap is the
implementation's problem rather than the caller's. `fetchedAt` stamps the reading as
a whole; the entries' individual `observedAt` values legitimately spread across the
duration of a fan-out.

**Failure is an exception, never an empty result.** `AvailabilitySourceException` is
checked, because an unchecked failure can be ignored by a caller who never considered
it — and the shape of that mistake is a fetch loop that appends whatever it got back.
An empty `entries` list is already a legitimate answer meaning *we asked and the
source priced nothing*; a source that swallowed a 503 and returned one would fire
**GONE** on inventory that never moved and write that fiction into append-only
history, where nothing later can tell it from the truth.

Instances come from named factories rather than a constructor —
`AvailabilitySourceException.retryable(query, msg)` and `.permanent(query, msg)` —
because the alternative is a boolean in an argument list, and getting that backwards
means either hammering a rate-limited API forever or abandoning a route on a blip.
The distinction is a claim about the *failure*, not a retry policy; how long to wait
and when to give up belong to §5. The failed `RouteQuery` rides along on the
exception, since the layer that fans a watch out into many queries collects failures
with nothing else to attribute them to.

**`supports(Program)` is the dispatch seam.** One source may cover many programs — an
aggregator prices dozens, a scraper pointed at a single airline covers one — so a
registry asks each source before routing a query. It is consulted once per query and
must answer from what the implementation already knows; a source that reached the
network here would turn dispatch into a second round of requests against the same
quota the fetch has to live within. Calling `fetch` with an unsupported program is a
`permanent` failure, named on the interface so two implementations cannot disagree
about it.

`Snapshot`'s own constructor rejects any entry that does not belong to the query it
holds: wrong route, wrong program, a departure date outside the range, an
`observedAt` after the fetch completed, or a duplicate `AwardKey`. A source's mapping
bug is caught at the edge rather than reaching the diff engine disguised as inventory
that moved.

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
-- The locally authenticated owner of the watches.
CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(320) NOT NULL UNIQUE,
    password_hash   TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

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
    date_from       DATE NOT NULL,            -- the RouteQuery's DateRange, so a diff
    date_to         DATE NOT NULL,            -- can be scoped to dates both snapshots saw
    program         TEXT NOT NULL,            -- the RouteQuery's Program
    observed_at     TIMESTAMPTZ NOT NULL,
    api_calls_used  SMALLINT NOT NULL,
    succeeded       BOOLEAN NOT NULL,         -- §6: a failed snapshot is never a baseline
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
    seats_remaining SMALLINT,                  -- NULL = available, count unknown
    nonstop         BOOLEAN NOT NULL,
    observed_at     TIMESTAMPTZ NOT NULL,      -- when this individual entry was read
    refreshed_at    TIMESTAMPTZ               -- source's own last-refresh; NULL = unreported
);

CREATE INDEX ON availability_entry (snapshot_id);
CREATE INDEX ON snapshot (origin, destination, program, observed_at DESC);

-- Crawl bookkeeping: when each route was last fetched and how urgent it is.
CREATE TABLE crawl_state (
    origin          CHAR(3) NOT NULL,
    destination     CHAR(3) NOT NULL,
    program         TEXT NOT NULL,
    date_from       DATE NOT NULL,
    date_to         DATE NOT NULL,
    last_crawled_at TIMESTAMPTZ,
    consecutive_empty SMALLINT NOT NULL DEFAULT 0,
    PRIMARY KEY (origin, destination, program, date_from, date_to)
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

**Domain `Snapshot` vs. the `snapshot` table.** The record is
`(query, fetchedAt, entries)`; the table flattens the query into
`origin`/`destination`/`date_from`/`date_to`/`program`, maps `fetchedAt` onto
`observed_at`, and adds three columns the domain deliberately has no field for.
`api_calls_used` and `source` are ingest bookkeeping, not facts about the awards.
`succeeded` is the interesting one: **there is no failed `Snapshot` in the domain**,
because a source that cannot answer throws instead of constructing one. The `FALSE`
row is written by `persistence` from the catch block, so the column records something
the record could never hold — which is exactly why §6 can trust that a snapshot it
loads is a real reading.

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

The initial implementation scores each deduplicated `RouteQuery` using its own
chunk start date and full crawl-state key (route, program and date range):
`urgency = 1 + max(0, 21 - max(0, daysUntilStart)) / 21`, using UTC dates;
`staleness = clamp(minutesSinceLastCrawl, 0, 1440)`, with unseen queries at 1440;
`hitRate = max(0.1, 0.9 ^ consecutiveEmpty)`. This hit-rate proxy uses the existing
empty streak, rather than a historical moving average. A non-empty success resets
it to 1; failures leave history unchanged. The floor prevents empty routes from
receiving zero priority. The staleness cap limits priority but does not guarantee
starvation freedom under sustained quota pressure.

Highest scores are crawled first until the budget for that tick is spent. Ties
retain watch creation and query expansion order. History is loaded before ranking
and all scores use the same clock instant.

**Redis caching.** Identical route+date queries within a TTL (15 min) serve from
cache and cost zero quota. This mostly protects against a user hammering the
search box, and against overlapping watches on the same route.
`ResponseCache` stores successful snapshots as JSON under a versioned seats.aero
namespace, keyed by canonical airport codes, program and both date endpoints.
Redis sets the value and 15-minute expiry atomically; reads never extend it.
`CrawlRunner` checks it before reserving quota and fills it after persistence
commits. Hits preserve original timestamps and do not append history or change
crawl scoring. Successful empty responses are cached; failures and partial
responses are not. Malformed or mismatched cached values are misses. Redis
outages propagate, stopping the tick. Direct source fetches bypass the cache;
the future manual search path can read the same `ResponseCache`.

**Distributed crawl locks.** `RouteLock` acquires a Redis `SET NX` lease using
canonical origin/destination, deliberately spanning programs and date ranges.
`CrawlRunner` skips a busy route without spending quota or stopping the tick,
rechecks the cache under the lock, and holds ownership through persistence and
cache writes. The lease expires after two minutes and renews every 30 seconds;
Lua renewal and release compare the unique ownership token atomically. Every page
and retry, and each persistence/cache write, checks ownership before proceeding.
Redis errors or lease loss stop the worker; try-with-resources releases the lease
on every exit. A crashed worker's lease expires automatically. Direct source
callers bypass this coordination. This is a single-Redis lease, not a fencing
protocol: Redis failover or a process pause exceeding the lease can allow overlap
with an operation already in flight. Ownership checks prevent subsequent work
once loss is detected.

**Call cost logging.** Each completed `SeatsAeroClient.search` attempt emits an
INFO `upstream_call` event with a unique call ID, route, program slug, date range,
pagination offset, outcome, HTTP status, `cost` in quota calls and `duration_ms`.
This includes retries, pages and direct client calls. HTTP responses (including
errors and malformed payloads) cost 1; transport errors cost 0 under the existing
quota policy. Other client errors conservatively retain cost 1. These are policy
costs, not upstream billing measurements. Cache hits and rejected reservations
make no HTTP attempt and emit no call-cost event. `quota_refund` events separately
report the original UTC reservation date and whether the Redis refund applied or
failed; a failed refund leaves the local counter debited despite transport cost 0.
Credentials, headers, bodies and exception messages are omitted from these events.
An in-flight request interrupted by process termination may have no completion log.

---

## 6. Diff-based alerting

Polling gives you the *current state*. Users want the *change*. Firing on
current state means re-emailing the same seat every hour until it's gone.

For each active watch, after a new snapshot lands:

1. Load the two newest *successful* snapshots for the same `(route, program)`.
   Snapshots for different programs are different fetches and never diff against
   each other.
2. Intersect the two snapshots' `[date_from, date_to]` spans and discard entries
   outside the overlap. Only one snapshot's dates were never compared, and treating
   a narrowed range as data would read every award on the dropped dates as **GONE**.
   `DateRange.intersection` returns empty when the two share no date at all, which
   means the pair cannot be diffed rather than that everything changed.
3. Build an `AvailabilityEntry.AwardKey` for every surviving entry:
   `(departureDate, program, cabin, nonstop)`. `nonstop` is part of the key because
   the nonstop and connecting awards in one cabin are separately priced products.
   Without it the two collapse into one row and a connection replacing a nonstop
   reads as a **CHEAPER** alert. Indexing a snapshot by key is safe because
   `Snapshot` already rejects duplicate keys at construction — otherwise a
   duplicate would silently drop an entry and make the comparison depend on
   iteration order.
4. Classify:
   - key in new, absent from old → **NEW**
   - key in both, `mileageCost` dropped by more than a threshold → **CHEAPER**
   - key in both, `seatsRemaining` increased → **MORE_SEATS**
   - key in old, absent from new → **GONE** (recorded, not alerted)
5. Filter by the watch's `cabins`, `programs`, `max_mileage`, `min_seats`.
6. Suppress anything already in `alert_event` for this watch within a cooldown
   window (default 24h), so a seat that flickers in and out doesn't spam.
7. Batch surviving changes into one email per watch per run.

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
| Language / framework | Java 21, Spring Boot 4 | Existing strength; `@Scheduled` and Spring Data remove boilerplate |
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
  knowing. `supports(Program)` is the seam: a registry routes each `RouteQuery` to
  a source that claims its program, and a new source is a new bean rather than an
  edit anywhere else. Two sources claiming the *same* program is the case that
  needs real work — a merge/dedup step in `ingest`, and a precedence rule for
  which reading wins when they disagree on price.
- **Historical analysis.** The append-only table already supports asking which
  programs release seats on which weekdays, and how far out.
- **Point valuation.** Join mileage cost against cash fare to rank redemptions
  by cents-per-point rather than raw miles.
