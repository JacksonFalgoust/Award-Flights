package com.awardwatch.domain;

import java.util.Objects;

/**
 * One request to an Availability Source: a Route, a span of departure dates, and the
 * Mileage Program to price them in.
 *
 * This is the unit of fetching, not the unit of watching. A user watching "Atlanta to
 * Tokyo in March, business or first, on any of my programs" is a Watch that fans out
 * into several of these; a RouteQuery is one call's worth of work and nothing more. A
 * Watch whose window is longer than {@link DateRange#MAX_DAYS} fans out into several
 * queries on date alone.
 *
 * Cabin is deliberately absent. Sources return every cabin for a given route, date and
 * program in a single response, so querying per cabin multiplies the request count
 * against a rate-limited API without narrowing the payload. Cabin is a dimension of the
 * result, and lives on {@link AvailabilityEntry} and in its
 * {@link AvailabilityEntry.AwardKey}.
 *
 * Program is present for the opposite reason: it selects which source is called and how
 * the request is addressed, so it is fixed for the whole query rather than varying
 * across the response.
 *
 * @param route          the directional origin/destination pair to price
 * @param departureDates the span of local departure dates to price, inclusive of both
 *                       endpoints. A single date is
 *                       {@link DateRange#single(java.time.LocalDate)}
 * @param program        the Mileage Program to price the awards in. Constant for the
 *                       whole query, since it selects which source is called
 */
public record RouteQuery(Route route, DateRange departureDates, Program program) {

    /**
     * Rejects an incomplete query, so that it cannot reach an Availability Source.
     *
     * Airport-code and date-span validity are already guaranteed by {@link Route} and
     * {@link DateRange}; there is deliberately nothing to re-check here.
     *
     * @throws NullPointerException if any component is null
     */
    public RouteQuery {

        Objects.requireNonNull(route, "route cannot be null");
        Objects.requireNonNull(departureDates, "departureDates cannot be null");
        Objects.requireNonNull(program, "program cannot be null");

    }

}