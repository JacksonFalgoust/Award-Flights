# seats.aero Partner API — reconnaissance notes

## API call cost per endpoint

Not documented anywhere in the published reference (checked every endpoint
page: `/search`, `/live`, `/availability`, `/availability/bulk`, `/trips`,
`/userinfo`, concepts, overview, getting-started). Confirmed empirically
instead, by tracking the `x-ratelimit-remaining` response header (see below)
across live calls made 2026-08-25:

| Call | Remaining before → after | Delta |
|---|---|---|
| `GET /search` (5-day range, `take=10`) | 982 → 981 | **-1** |
| `GET /search` (4-month range, `take=1000`, `hasMore: true`) | 981 → 980 | **-1** |
| `GET /availability` (bulk, `source=american`) | 980 → 979 | **-1** |
| `POST /live` (rejected, 401 — key not entitled) | 979 → 978 | **-1** |

**Conclusions:**

- `/search` costs exactly **1 call per request**, independent of date-range
  width or `take` (tested up to the max page size, 1000 rows).
- `/availability` (bulk) also costs exactly **1 call per request**.
- If a response has `hasMore: true`, fetching the next page via `cursor` is a
  **separate request and costs another call**. A search that needs 3 pages to
  exhaust a wide date range costs 3 calls, not 1. This — pagination fan-out,
  not a per-endpoint multiplier — is what "some endpoints cost more than one"
  actually refers to.
- `POST /live` decremented the quota even though it returned 401 ("not
  enabled for live search API — requires a commercial agreement"). This
  contradicts the docs' claim that "failed live searches don't count against
  your quota." Possibly that clause only covers failures from airline-system
  outages, not entitlement rejections — untested, since this key has no
  `/live` access at all, so a genuine success/failure distinction couldn't be
  checked.
- **Design implication for `QuotaBudgeter`:** cost = number of pages actually
  fetched, not a fixed per-endpoint constant. `AvailabilitySource.estimatedCost()`
  should account for expected page count (⌈expected rows / take⌉), and the
  budgeter should trust `x-ratelimit-remaining` off the response as ground
  truth rather than relying solely on its own client-side decrement.

## Rate-limit response headers (undocumented)

Every response — success or failure — carries these headers, none of which
appear in the published reference:

```
x-ratelimit-limit: 1000
x-ratelimit-remaining: <int>
x-ratelimit-reset: <seconds until reset>
```

`x-ratelimit-reset` counts down in seconds (observed ~24888 → ~24862 across
calls made seconds apart), consistent with a rolling/fixed daily window reset
independent of wall-clock midnight — worth confirming against UTC midnight
before relying on it for quota-key derivation.

## Caveat on the saved samples

`searchEndpointSampleResponse.json` is **not a genuine live capture** — its
records (SFO→JFK American, SFO→LHR Virgin Atlantic, `CreatedAt` ~2022-12,
`UpdatedAt` ~2023-07) exactly match the canned example payload published on
the `/reference/cached-search` docs page, right down to the record IDs
(`2QSaUXJ0ZuSVqgrRWqkSlXhnVbS`, `2IzwzHKFl5zkDTwHedjfDL3aOwG`). It looks like
the docs' example was saved instead of a real curl response. Treat
field-shape conclusions from it as reliable (the schema is real) but treat
"current" data (dates, staleness) from it as not representative.

`availabilityEndpointSampleResponse.json` **is genuine** — its `moreURL`
carries real query params (`source=delta`, `origin_region=North+America`,
`destination_region=Asia`, `start_date=2026-09-16`, `cabin=economy`) and its
`UpdatedAt` timestamps are hours-old relative to today, consistent with an
actual live pull.

## Field presence: always-present vs. sometimes null

**`/search` (cached search) response, per entry in `data[]`:**

Always present, never null: `ID`, `RouteID`, `Route` (see below), `Date`,
`ParsedDate`, `Source`, `CreatedAt`, `UpdatedAt`, and the `Y*`/`W*`/`J*`
group (`YAvailable`, `YMileageCost`, `YRemainingSeats`, `YAirlines`,
`YDirect`, and the `W` equivalents) — every record in the sample had
economy and premium economy fields populated, even when unavailable (then
`Available: false`, cost `"0"`, `RemainingSeats: 0`, `Airlines: ""`).

Sometimes null, **as a group**: the `F*` fields (`FAvailable`,
`FMileageCost`, `FRemainingSeats`, `FAirlines`, `FDirect`) were null on
100% of `virginatlantic` records (6/6) and non-null on every `american`,
`alaska`, and `delta` record. `J*` was never null in this sample, but the
same mechanism presumably applies to any cabin a given program's fare chart
doesn't define — **null means "this program has no such cabin," not
"nothing available."** `false` + zero cost means the cabin exists but has
no bookable award space on that date. The mapper needs to keep these
distinct rather than collapsing null to false.

`AvailabilityTrips` is always `null` in the `/search` sample — trip-level
detail isn't returned unless requested (see `include_trips` param).

**`Route` sub-object**, `/search`: `ID`, `OriginAirport`, `OriginRegion`,
`DestinationAirport`, `DestinationRegion`, `Distance`, `Source`,
**and `NumDaysOut`**. All non-null in the sample.

**`/availability` (bulk) response** is a materially richer shape than
`/search` — same base fields plus, per cabin (`Y`/`W`/`J`/`F`):

- A **`*Raw` twin of every filtered field** (`YAvailableRaw`,
  `YMileageCostRaw`, `YRemainingSeatsRaw`, `YAirlinesRaw`, `YDirectRaw`, …).
  The non-`Raw` field is the dynamically-price-filtered view (expensive/
  junk fares hidden); `Raw` is unfiltered. They can disagree — e.g. one
  record had `WAvailable: false` (filtered) but `WAvailableRaw: true`,
  `WMileageCostRaw: 276900` (an absurd points price that got filtered out).
- A **`*Direct*` family** (`YDirectMileageCost`, `YDirectRemainingSeats`,
  `YDirectAirlines`, `YDirectTotalTaxes`, each with a `Raw` twin too) —
  nonstop-only figures, separate from the cabin's overall (nonstop-or-connect)
  figures.
- **`*TotalTaxes`** (and `Raw` twin) plus a shared `TaxesCurrency` — not
  present at all in the `/search` shape.
- **`OptionalPricing`** — a dict keyed by transfer-partner program (e.g.
  `"DLAMEX"` = Delta via Amex Membership Rewards transfer), giving that
  partner's own price/tax for `Y`/`J`. Present on 24/25 records in the
  sample; **absent as a key entirely (not null) on 1/25** — appears to be
  omitted per-route when no alternate transfer-partner chart applies,
  rather than being a fixed per-program field. Map this to a
  possibly-empty/absent collection, not a required field.
- `AvailabilityTrips` was present as a key but an **empty list `[]`** on
  every record in this sample (not populated). Since `include_trips` wasn't
  passed, this endpoint's default also appears to omit trip detail —
  confirm by re-pulling with `include_trips=true` before assuming trips are
  always empty here.
- **No `NumDaysOut`** in `Route` for `/availability` — that field is
  `/search`-only in this data.
- Top-level pagination differs too: `/availability` responses include a
  `moreURL` (a ready-to-use next-page URL) alongside `cursor`; `/search`
  responses only had `cursor`.

## Mixed-cabin itineraries

There's no dedicated "mixed cabin" flag. The API represents this by keeping
cabin data **independent per cabin letter** rather than describing an
itinerary as belonging to one cabin: `YAvailable`/`WAvailable`/`JAvailable`/
`FAvailable` (and their cost/seats/airlines) are reported separately, so an
itinerary where the outbound segment is business and the connection is
economy would simply show up as `JAvailable: true` with its own price,
independently of `YAvailable`. The `*Direct` vs. non-`Direct` split in the
`/availability` shape reinforces this: `JDirectMileageCost` (nonstop only)
vs `JMileageCost` (nonstop-or-connecting) can differ, which is the API's way
of surfacing "the direct flight in this cabin isn't the same product as the
connecting one."

**Caveat:** true segment-by-segment cabin detail (which would show a literal
mixed-cabin trip, e.g. segment 1 = J, segment 2 = Y) only shows up in
`AvailabilityTrips`, and every record in both saved samples has that either
`null` or `[]` — so this file describes the *summary-level* representation
of mixed cabins, not the segment-level one. To see actual per-segment cabin
codes, re-pull `/search` or `/availability` with `include_trips=true`, or
use `/live`, whose docs describe each `AvailabilitySegments[]` entry as
carrying its own `Cabin` — that's presumably where a genuinely mixed-cabin
trip (differing `Cabin` across segments of one result) would be visible.

## Cached vs. live signaling, and cache staleness

There's no boolean field like `isCached` or `isLive` in the response body —
the signal is **structural**, by endpoint:

- `/search` and `/availability` are both documented as cache reads (the
  former literally named "Cached Search"). Their only freshness signal is
  the per-record `UpdatedAt` timestamp — how long ago seats.aero's own
  crawler last refreshed that specific route/date/program combination.
  `CreatedAt` is when the record first entered their DB, not a freshness
  signal.
- `/live` (`POST /partnerapi/live`) is a structurally different endpoint —
  it bypasses the cache and queries the airline in real time (5–15s
  response time per its docs page), returning a `results[]` shape with
  `AvailabilitySegments`, not the `data[]`/`Y*`/`W*`/`J*`/`F*` shape above.
  Its docs note identifiers it returns are temporary and not usable against
  other endpoints — another sign it isn't reading from the same cache.

**Observed staleness**, from `UpdatedAt` vs. now:

- `/availability` sample (genuine live pull, all `delta`, today): ages
  ranged **~3 hours to ~138 hours (~5.75 days)** across 25 records, median
  ~5.7 hours. So for an actively-tracked program, most cached entries are
  same-day fresh, but a minority of routes can lag the better part of a
  week before being re-crawled.
- `/search` sample: not usable for staleness (it's the docs' canned
  example, ~3 years old — see caveat above). Re-pull a genuine `/search`
  response before drawing conclusions about that endpoint's staleness
  specifically.

## Rate-limit (429) response shape

**Not yet captured** — neither sample folder file is a 429, and the
official docs don't show one either. The only rate-limit-related evidence
so far is the `x-ratelimit-limit` / `x-ratelimit-remaining` / `x-ratelimit-reset`
response *headers* documented above, observed on ordinary 200s (and on the
one 401 seen). Getting an actual 429 body would mean deliberately exceeding
the per-second/per-minute rate (not the daily quota — burning through 1,000
calls just to see this isn't worth it) by firing a rapid burst of requests,
which risks tripping abuse detection on the account. Hold off unless you
want to spend a burst of calls specifically to capture this — flag if so
and it can be done deliberately rather than as a side effect of something
else.

## Open items from Phase 0 checklist

- What a rate-limit (429) response actually looks like — see above; needs a
  deliberate decision to spend calls on triggering one.
