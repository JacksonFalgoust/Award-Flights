package com.awardwatch.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A closed span of local departure dates, inclusive of both endpoints.
 *
 * A single date is {@link #single(LocalDate)}, a range whose start and end are equal,
 * so a one-date query is an ordinary range rather than a special case any caller has
 * to branch on.
 *
 * These are departure dates as an airline publishes them: local to the origin airport,
 * with no time and no zone. They are not instants and are never compared against
 * {@code observedAt} or the wall clock.
 *
 * @param start the first departure date in the span, included
 * @param end   the last departure date in the span, included; never before {@code start}
 */
public record DateRange(LocalDate start, LocalDate end) {

    /**
     * The widest span a single range may cover.
     *
     * A range is fanned out into one upstream call per date against sources that are not
     * calendar-native, so an unbounded range is an unbounded burst of requests at a
     * rate-limited API. Airlines publish schedules roughly 330 days out, and a watch
     * spanning that whole window is a fan-out of 330 calls that no source will tolerate.
     * Capping here turns that into a construction-time error at the edge of the system
     * rather than a throttle or a ban partway through a fetch.
     */
    public static final int MAX_DAYS = 90;

    /**
     * Rejects any span that could not be fetched as one unit, so that an unusable range
     * cannot reach a source.
     *
     * @throws NullPointerException     if either endpoint is null
     * @throws IllegalArgumentException if {@code end} is before {@code start}, or if the
     *                                  span exceeds {@link #MAX_DAYS} days
     */
    public DateRange {

        Objects.requireNonNull(start, "start cannot be null");
        Objects.requireNonNull(end, "end cannot be null");

        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end cannot be before start");
        }

        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw new IllegalArgumentException("range cannot span more than " + MAX_DAYS + " days");
        }

    }

    /**
     * The range covering exactly one departure date.
     *
     * @param date the single departure date
     * @return a range whose start and end are both {@code date}, of length 1
     * @throws NullPointerException if {@code date} is null
     */
    public static DateRange single(LocalDate date) {
        return new DateRange(date, date);
    }

    /**
     * Whether a departure date falls inside this span.
     *
     * The distinction this exists to serve: a date outside the range was never fetched,
     * which is not the same as a date that was fetched and had no award. A diff that
     * treats the two alike will read a narrowed range as every award vanishing.
     *
     * @param date the departure date to test
     * @return {@code true} if {@code date} is between the endpoints, either included
     * @throws NullPointerException if {@code date} is null
     */
    public boolean contains(LocalDate date) {
        Objects.requireNonNull(date, "date cannot be null");
        return !date.isBefore(start) && !date.isAfter(end);
    }

    /**
     * Every departure date in the span, ascending.
     *
     * @return a stream of {@link #length()} dates, from {@code start} through {@code end}
     */
    public Stream<LocalDate> dates() {
        return start.datesUntil(end.plusDays(1));
    }

    /**
     * How many departure dates the span covers.
     *
     * @return the count of dates, at least 1 and at most {@link #MAX_DAYS}
     */
    public int length() {
        return (int) ChronoUnit.DAYS.between(start, end) + 1;
    }

    /**
     * The span shared by this range and another.
     *
     * Used to scope a diff to the dates both Snapshots actually covered, so that dates
     * only one of them fetched are excluded from the comparison instead of reading as
     * awards added or removed.
     *
     * @param other the range to intersect with
     * @return the overlapping range, or empty if the two do not overlap. Empty means the
     *         two Snapshots share no fetched date and cannot be diffed at all
     * @throws NullPointerException if {@code other} is null
     */
    public Optional<DateRange> intersection(DateRange other) {
        Objects.requireNonNull(other, "other cannot be null");

        LocalDate latestStart = start.isAfter(other.start) ? start : other.start;
        LocalDate earliestEnd = end.isBefore(other.end) ? end : other.end;

        if (earliestEnd.isBefore(latestStart)) {
            return Optional.empty();
        }

        return Optional.of(new DateRange(latestStart, earliestEnd));
    }

}