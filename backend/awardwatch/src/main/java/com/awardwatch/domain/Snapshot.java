package com.awardwatch.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

/**
 * Everything one Availability Source returned for one {@link RouteQuery}, taken as a
 * single reading.
 *
 * This is the unit the diff engine compares and the unit the append-only history stores.
 * A Snapshot is never edited: a Route re-fetched an hour later is a second Snapshot, and
 * the change between them is derived by comparison rather than recorded by mutation.
 *
 * An empty {@code entries} list is a legitimate answer, and means the source was asked
 * and priced nothing. It does not mean the fetch failed, and it does not mean the Route
 * was skipped &mdash; a source that cannot answer throws
 * {@link AvailabilitySourceException} instead, precisely so that this list can be trusted
 * to mean what it says. Reading a failure as an empty Snapshot would diff as every Award
 * on the Route vanishing.
 *
 * The Snapshot carries the {@link RouteQuery} that produced it, so the {@link DateRange}
 * actually fetched travels with the result. That is what makes
 * {@link DateRange#intersection(DateRange)} usable: two Snapshots taken over different
 * windows can be diffed over the dates both of them covered, instead of reading the dates
 * only one of them fetched as Awards added or removed.
 *
 * At most one entry per {@link AvailabilityEntry.AwardKey}. The diff engine indexes a
 * Snapshot by Award Key, so a duplicate key would silently drop an entry and make the
 * comparison depend on iteration order.
 *
 * @param query      the query this Snapshot answers. Its {@code departureDates} are the
 *                   dates that were actually fetched, which is not necessarily the window
 *                   a Watch asked about
 * @param fetchedAt  when the fetch completed, stamping the Snapshot as a whole. This is
 *                   not each entry's {@code observedAt}: a {@link DateRange} spanning many
 *                   dates may fan out into one upstream call per date against a source
 *                   that is not calendar-native, so the entries' observation times
 *                   legitimately spread across the duration of the fetch while this stays
 *                   a single instant
 * @param entries    every Award the source priced for the query, in no guaranteed order.
 *                   Copied on construction and unmodifiable thereafter. Empty means
 *                   nothing was available, never that nothing was asked
 */
public record Snapshot(RouteQuery query, Instant fetchedAt, List<AvailabilityEntry> entries)  {

    /**
     * Rejects any Snapshot that misreports what was fetched, so that a source's mapping
     * bug cannot reach the diff engine or the append-only history disguised as inventory
     * that moved.
     *
     * Each entry is checked against the query rather than against a rule of its own:
     * {@link AvailabilityEntry} already guarantees an entry is internally coherent, and
     * what remains is whether it belongs to <em>this</em> reading. An entry on another
     * Route matters because {@link AvailabilityEntry.AwardKey} omits origin and
     * destination on the assumption that a Snapshot spans exactly one Route; an entry on
     * a date outside the range matters because a date outside the range was never
     * fetched, and {@link DateRange#contains(java.time.LocalDate)} is what separates that
     * from a date fetched and found empty.
     *
     * The list is copied before it is inspected, so a caller mutating their own list
     * afterwards cannot leave a validated Snapshot holding entries that were never
     * checked.
     *
     * @throws NullPointerException     if any component is null, or if {@code entries}
     *                                  contains a null element
     * @throws IllegalArgumentException if any entry is on a different Route or Mileage
     *                                  Program than the query, falls outside the queried
     *                                  {@link DateRange}, was observed after the fetch
     *                                  completed, or shares an
     *                                  {@link AvailabilityEntry.AwardKey} with another
     *                                  entry
     */
    public Snapshot {

        Objects.requireNonNull(query, "query cannot be null");
        Objects.requireNonNull(fetchedAt, "fetchedAt cannot be null");
        Objects.requireNonNull(entries, "entries cannot be null");

        entries = List.copyOf(entries);

        Set<AvailabilityEntry.AwardKey> seen = new HashSet<>();

        for (AvailabilityEntry entry : entries) {
            if (!entry.route().equals(query.route())) {
                throw new IllegalArgumentException("entry route " + entry.route() + " does not match query route " + query.route());
            }

            if (entry.program() != query.program()) {
                throw new IllegalArgumentException("entry program " + entry.program() + " does not match query program " + query.program());
            }

            if (!query.departureDates().contains(entry.departureDate())) {
                throw new IllegalArgumentException("entry departureDate " + entry.departureDate() + " is outside " + query.departureDates());
            }

            if (fetchedAt.isBefore(entry.observedAt())) {
                throw new IllegalArgumentException("fetchedAt " + fetchedAt + " cannot be before entry observedAt " + entry.observedAt());
            }

            if (!seen.add(entry.awardKey())) {
                throw new IllegalArgumentException("duplicate award in snapshot: " + entry.awardKey());
            }
        }

    }

}
