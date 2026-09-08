package com.awardwatch.domain;

/**
 * One place award inventory can be read from: an aggregator, an airline's own award
 * search, a scraper.
 *
 * This is the boundary the system learns anything through. Everything on the domain side
 * of it &mdash; {@link Snapshot}, the diff engine, the append-only history &mdash; reasons
 * about Awards as facts; everything on the far side is HTTP, JSON, retries, cookies and
 * rate limits. Implementations therefore live in {@code ingest}, never here, which is what
 * keeps the promise in this package's {@code package-info}: the domain depends on nothing.
 *
 * A call is one {@link RouteQuery} and yields one {@link Snapshot}. A source that cannot
 * price a whole {@link DateRange} in a single upstream request fans the range out
 * internally and merges the responses; that is a detail of the implementation and is
 * invisible here. {@link DateRange#MAX_DAYS} is capped precisely so that this fan-out
 * stays survivable, and it is the implementation, not the caller, that owns it. Callers
 * hand over a range and receive a reading of it.
 *
 * A source that cannot answer must throw. Returning an empty {@link Snapshot} is not a way
 * to report failure, because it is already a way to report something else: that the source
 * was asked and priced nothing. The diff engine reads the difference between those two as
 * every Award on the Route disappearing, and once it is written to the append-only history
 * nothing later can tell the fiction from the fact. {@link AvailabilitySourceException}
 * exists so that "I do not know" has somewhere to go.
 *
 * Implementations must be safe for concurrent use. A scheduler fans a Watch out into many
 * queries and runs them in parallel; per-call state belongs in locals, and anything shared
 * &mdash; an HTTP client, a token cache, a rate limiter &mdash; has to tolerate that.
 */
public interface AvailabilitySource {

    /**
     * Prices one query against this source.
     *
     * The returned Snapshot must carry the query it was given, unchanged. Nothing
     * downstream can detect a substitution: {@link Snapshot} validates its entries against
     * whatever query it holds, so a source that quietly narrows the
     * {@link DateRange} &mdash; because the upstream API refused half of it, because a
     * fan-out partially failed &mdash; and reports the narrowed range produces a Snapshot
     * that is internally consistent and wrong. It then diffs against its predecessor over
     * the intersection of the two ranges, so the dates that were dropped simply leave the
     * comparison, and inventory that was never checked reads as inventory that never
     * changed. A range that cannot be priced in full is a failure, not a smaller success.
     *
     * @param query the Route, span of departure dates and Mileage Program to price. The
     *              source is responsible for however many upstream requests that takes
     * @return the Awards the source priced, as a single reading. Carries {@code query}
     *         itself. May hold no entries, which means the source was asked and priced
     *         nothing &mdash; a real answer, and the one the diff engine will believe
     * @throws AvailabilitySourceException if the query could not be answered at all: the
     *                                     source was unreachable, rate-limited, returned
     *                                     something unparseable, or structurally cannot
     *                                     price this query &mdash; including when
     *                                     {@link #supports(Program)} is false for the
     *                                     query's Mileage Program, which is
     *                                     {@link AvailabilitySourceException#permanent}
     * @throws NullPointerException        if {@code query} is null
     */
    Snapshot fetch(RouteQuery query) throws AvailabilitySourceException;

    /**
     * Whether this source can price Awards in a given Mileage Program.
     *
     * The question a registry asks to route a query, so it is answered from what the
     * implementation already knows &mdash; a constant set, a field &mdash; and never by
     * calling upstream. It is consulted once per query, before any work is done, and a
     * source that reached the network here would turn dispatch into a second round of
     * requests against the same rate limit the fetch has to live within.
     *
     * One source may cover many programs: an aggregator prices dozens, while a scraper
     * pointed at a single airline covers one.
     *
     * @param program the Mileage Program a query wants priced
     * @return true if {@link #fetch(RouteQuery)} can meaningfully attempt a query in this
     *         program. False is a statement about this source only, not about the program
     *         &mdash; another source may well cover it
     * @throws NullPointerException if {@code program} is null
     */
    boolean supports(Program program);

}
