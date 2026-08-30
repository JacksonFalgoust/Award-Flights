# AwardWatch

AwardWatch tracks award-flight redemption inventory across mileage programs and
tells one user when seats they care about appear. This file is the project's
glossary. The design behind it lives in `ARCHITECTURE.md`.

## Language

### Inventory

**Award**:
A flight seat bookable with miles rather than cash.
_Avoid_: Redemption (that is the act of booking one), reward, points ticket

**Mileage Program**:
The loyalty currency and award chart an Award is priced in — Air Canada Aeroplan,
Virgin Atlantic Flying Club. Modelled as `Program`. seats.aero's own `Source` field
carries this meaning; that spelling does not follow the data into this codebase.
_Avoid_: Source, airline, carrier, partner

**Cabin**:
The class of service an Award is priced in — economy, premium economy, business,
first. Carries its IATA letter (Y / W / J / F).
_Avoid_: Class, fare class (that is the segment-level booking letter, a different thing)

**Availability Entry**:
One Award, in one Cabin, on one Route and date, in one Mileage Program, nonstop or
connecting, as observed at one moment. The atom of the model.
_Avoid_: Result, offer, listing, seat, row

**Award Key**:
The identity of an Award within a Route — its date, Mileage Program, Cabin, and
whether it is nonstop. Two Availability Entries carry the same Award Key when they
observed the same Award, which is what makes a Change detectable between Snapshots.
_Avoid_: Diff key, entry key, dedup key, fingerprint

**Nonstop**:
An Award whose itinerary has no connection. It is a distinct Award from the
connecting one in the same Cabin on the same date, and separately priced — not a
filter over a single Award.
_Avoid_: Direct, direct only, non-stop

**Airport Code**:
A 3-letter IATA airport identifier, canonicalized to upper case so a code compares
equal regardless of how it was typed or returned. What a Route pairs, directionally,
into an origin and a destination.
_Avoid_: IATA code, airport, station

**Route**:
An origin and destination airport pair, directional. ATL→NRT is not NRT→ATL.
_Avoid_: Trip, itinerary, leg, city pair

**Date Range**:
A closed span of departure dates, inclusive of both endpoints, capped at 90 days.
_Avoid_: Window, date window, span, period

**Snapshot**:
One crawl of one Route — the set of Availability Entries seen together, so that a
comparison holds everything else constant.
_Avoid_: Poll, fetch, scrape, batch

### Watching

**Watch**:
A user's standing interest in a Route, a date window and a set of filters. Drives
both crawl priority and alerting.
_Avoid_: Alert (that is what a Watch produces), subscription, saved search

**Change**:
A difference between a Route's two newest successful Snapshots — an Award that is
new, cheaper, has more seats, or is gone.
_Avoid_: Update, delta, diff, event

**Alert**:
One message sent to the user about the Changes on one Watch.
_Avoid_: Notification (reserve that for the delivery channel), email, ping

### Upstream

**Availability Source**:
An upstream system that can answer what Awards exist on a Route. seats.aero today;
a direct airline crawler is conceivable. This is the only meaning of *source* in the
codebase — it never means a Mileage Program.
_Avoid_: Provider, vendor, feed, backend, API

**Quota**:
The hard daily ceiling on Availability Source calls, reset by the provider. A budget
to be allocated deliberately, not an error condition.
_Avoid_: Rate limit (that is the separate per-second burst ceiling), throttle, cap

**Staleness**:
How long ago the Availability Source last refreshed a given Availability Entry.
Surfaced to the user rather than hidden.
_Avoid_: Age, lag, freshness (it is measured in the unflattering direction on purpose)
