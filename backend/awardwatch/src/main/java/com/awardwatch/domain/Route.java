package com.awardwatch.domain;

import java.util.Objects;

/**
 * A directional origin/destination airport pair.
 *
 * Direction is part of the identity: {@code ATL-NRT} and {@code NRT-ATL} are different
 * Routes, priced independently, and are never interchangeable. Award inventory is
 * routinely wide open one way and empty the other, so a Route that compared equal to its
 * reverse would merge two unrelated Snapshots.
 *
 * A Route is the scope a Snapshot and a diff are taken within. {@link
 * AvailabilityEntry.AwardKey} omits origin and destination precisely because it is only
 * ever compared against keys from the same Route; that guarantee is this type's job to
 * carry.
 *
 * @param origin      the airport the award departs from
 * @param destination the airport the award arrives at; never the same as {@code origin}
 */
public record Route(AirportCode origin, AirportCode destination) {

    /**
     * Rejects a Route that no award could fly, so that an unusable Route cannot reach a
     * source or the append-only history.
     *
     * @throws NullPointerException     if either endpoint is null
     * @throws IllegalArgumentException if origin and destination are the same airport
     */
    public Route {

        Objects.requireNonNull(origin, "origin cannot be null");
        Objects.requireNonNull(destination, "destination cannot be null");

        if (origin.equals(destination)) {
            throw new IllegalArgumentException("origin and destination cannot be the same airport");
        }

    }

    /**
     * Builds a Route from raw codes, for callers that hold strings rather than
     * {@link AirportCode}s: request parsing, source response mapping, and tests.
     *
     * @param origin      3-letter IATA code of the origin airport, in any case
     * @param destination 3-letter IATA code of the destination airport, in any case
     * @return the Route between the two airports
     * @throws NullPointerException     if either code is null
     * @throws IllegalArgumentException if either code is not three letters, or if the two
     *                                  are the same airport
     */
    public static Route of(String origin, String destination) {
        return new Route(new AirportCode(origin), new AirportCode(destination));
    }

    /**
     * The Route in {@code ATL-NRT} form, for logs, cache keys and crawl bookkeeping.
     *
     * @return the origin and destination codes joined by a hyphen
     */
    @Override
    public String toString() {
        return origin + "-" + destination;
    }

}