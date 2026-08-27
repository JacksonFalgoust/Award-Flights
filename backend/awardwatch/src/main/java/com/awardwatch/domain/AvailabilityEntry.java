package com.awardwatch.domain;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Objects;
import java.util.Locale;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One Award, in one Cabin, on one Route and date, in one Mileage Program, as
 * observed at one moment.
 *
 * Entries are immutable and append-only: a changed price is a new entry in a new
 * Snapshot, never an update to this one. Consequently every component is fixed at
 * construction, and no accessor here reads the wall clock.
 *
 * {@code (departureDate, program, cabin, nonstop)} identifies the award this entry
 * observed, and is the key the diff engine compares snapshots on.
 *
 * @param origin         3-letter IATA code of the origin airport, e.g. {@code "ATL"};
 *                       canonicalized to upper case on construction
 * @param destination    3-letter IATA code of the destination airport, e.g. {@code "NRT"};
 *                       canonicalized to upper case on construction
 * @param departureDate  the local date of departure this award is priced for
 * @param program        the Mileage Program the award is priced in
 * @param cabin          the class of service the award is priced in
 * @param mileageCost    miles required, one-way, per passenger
 * @param seatsRemaining seats the source reports bookable; {@code 0} means the award was
 *                       shown but is not bookable, which is different from absent
 * @param nonstop        whether this award is on a nonstop itinerary. The connecting award
 *                       in the same cabin is a separate, separately priced entry
 * @param observedAt     when <em>we</em> pulled this from the source. Not when the source
 *                       itself last refreshed it &mdash; that is {@code refreshedAt}
 * @param refreshedAt    when the Availability Source last refreshed this route/date/program,
 *                       or {@code null} if it reported none. Null means <em>unknown</em>;
 *                       never substitute {@code observedAt}, which would claim the data is
 *                       perfectly fresh when its age is in fact unknown
 */

public record AvailabilityEntry(String origin, String destination, LocalDate departureDate, Program program, Cabin cabin, int mileageCost, int seatsRemaining, boolean nonstop, Instant observedAt, Instant refreshedAt) {

    private static final Pattern IATA = Pattern.compile("[A-Z]{3}");

    /**
     * Canonicalizes the airport codes and rejects any entry that could not have been
     * observed, so that an invalid entry cannot exist to reach the diff engine or the
     * append-only history.
     *
     * @throws NullPointerException     if any component other than {@code refreshedAt} is null
     * @throws IllegalArgumentException if either airport code is not three letters, if the two
     *                                  are the same airport, or if {@code mileageCost} or
     *                                  {@code seatsRemaining} is negative
     */
    public AvailabilityEntry {

        Objects.requireNonNull(origin, "origin cannot be null");
        Objects.requireNonNull(destination, "destination cannot be null");
        Objects.requireNonNull(departureDate, "departureDate cannot be null");
        Objects.requireNonNull(program, "program cannot be null");
        Objects.requireNonNull(cabin, "cabin cannot be null");
        Objects.requireNonNull(observedAt, "observedAt cannot be null");

        origin = origin.toUpperCase(Locale.ROOT);
        destination = destination.toUpperCase(Locale.ROOT);

        if (!IATA.matcher(origin).matches()) {
            throw new IllegalArgumentException("origin must be a 3-letter code");
        }

        if (!IATA.matcher(destination).matches()) {
            throw new IllegalArgumentException("destination must be a 3-letter code");
        }

        if (mileageCost < 0) {
            throw new IllegalArgumentException("mileageCost cannot be negative");
        }

        if (seatsRemaining < 0) {
            throw new IllegalArgumentException("seatsRemaining cannot be negative");
        }

        if (origin.equals(destination)) {
            throw new IllegalArgumentException("origin and destination cannot be the same");
        }
    
        if (refreshedAt != null && refreshedAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("refreshedAt cannot be after observedAt");
        }

    }

    /**
     * How stale the source's data already was at the moment this entry was observed.
     *
     * Fixed for the life of the entry: it does not grow as the row ages in history.
     * "How old is this data right now" is a different quantity, computed against
     * {@code observedAt} at request time by the layer that renders it.
     *
     * @return {@code observedAt - refreshedAt}, or empty if the source reported no refresh
     *         time. Empty means the age is unknown, it never means zero
     */
    public Optional<Duration> staleness() {
        if(refreshedAt == null) {
            return Optional.empty();
        } else {
            return Optional.of(Duration.between(refreshedAt, observedAt));
        }
    }
    
    /**
     * The identity of the Award an entry observed, stripped of everything about the
     * observation itself.
     *
     * Two entries share an Award Key when they are two sightings of the same Award, so
     * this &mdash; not {@link AvailabilityEntry} equality; is what pairs an entry in
     * one Snapshot with its predecessor in the last one. The record's own generated
     * {@code equals} spans every component including {@code mileageCost} and
     * {@code observedAt}, which makes an Award whose price moved unequal to itself and
     * useless for that pairing.
     *
     * Scoped to one Route. Origin and destination are deliberately
     * absent: a diff compares two Snapshots of the same Route, so they are constant across
     * the comparison. Keys from different Routes are therefore not comparable, and
     * collecting them into one map will merge unrelated Awards.
     *
     * @param departureDate the local date of departure the Award is priced for
     * @param program       the Mileage Program the Award is priced in
     * @param cabin         the class of service the Award is priced in
     * @param nonstop       whether the itinerary has no connection. Part of the identity,
     *                      not an attribute of it &mdash; the nonstop and connecting Awards
     *                      in one Cabin are separate products at separate prices, and
     *                      collapsing them lets a connection replacing a nonstop read as a
     *                      price drop
     */
    public record AwardKey(LocalDate departureDate, Program program, Cabin cabin, boolean nonstop) {

    }

    /**
     * The Award this entry observed.
     *
     * Allocates a new key per call; a caller diffing two Snapshots should index each one
     * into a map once rather than re-deriving keys inside a loop.
     *
     * @return the identity of the observed Award, valid for comparison only against keys
     *         from the same Route
     */
    public AwardKey awardKey() {
        return new AwardKey(departureDate, program, cabin, nonstop);
    }

}