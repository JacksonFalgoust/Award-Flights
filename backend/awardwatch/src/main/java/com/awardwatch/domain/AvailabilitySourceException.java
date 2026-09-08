package com.awardwatch.domain;

import java.util.Objects;

/**
 * A {@link RouteQuery} could not be answered by its Availability Source.
 *
 * Exists so that failure has somewhere to go other than an empty result. A Snapshot
 * carrying no entries is a legitimate answer &mdash; we asked, and the source priced
 * nothing &mdash; and the diff engine reads it as every Award on the Route disappearing.
 * A source that swallowed a 503 and returned an empty Snapshot would therefore fire an
 * alert on inventory that never moved, and write that fiction into the append-only
 * history where nothing later can distinguish it from the truth. Throwing is the only
 * way for a source to say it does not know.
 *
 * Checked deliberately. An unchecked failure can be ignored by a caller who never
 * considered it, and the shape of that mistake is exactly the one above: a fetch loop
 * that appends whatever it got back. The signature on
 * {@link AvailabilitySource#fetch(RouteQuery)} makes the failure path a thing the
 * compiler insists on.
 *
 * Instances come from the named factories, never from a constructor. {@link #retryable}
 * is a boolean, and a boolean in an argument list is easy to write backwards; getting it
 * backwards means either hammering a rate-limited API forever or abandoning a Route on a
 * blip. {@code retryable(query, "429")} and {@code permanent(query, "no such route")}
 * cannot be confused for one another at a glance.
 *
 * Lives in the domain, though every implementation that throws it lives in
 * {@code ingest}, because it is half of the {@link AvailabilitySource} contract: the
 * interface is not usable without the failure it declares.
 */
public class AvailabilitySourceException extends Exception {

    /**
     * The query that failed.
     *
     * {@code transient} because {@link RouteQuery} is not {@link java.io.Serializable}
     * and {@link Exception} is. Nothing here serializes exceptions; this keeps a future
     * log appender or remoting layer that does from failing on the attempt.
     */
    private final transient RouteQuery query;
    private static final long serialVersionUID = 1L;
    private final boolean retryable;

    private AvailabilitySourceException(RouteQuery query, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.query = Objects.requireNonNull(query, "query cannot be null");
        this.retryable = retryable;
    }

    /**
     * A failure that re-issuing the same query could plausibly get past: a rate limit, a
     * timeout, a 5xx, a response that did not parse.
     *
     * @param query   the query that failed
     * @param message what went wrong, for a log. The query is not interpolated into it;
     *                it is available separately from {@link #query()}
     * @return the failure, ready to throw
     * @throws NullPointerException if {@code query} is null
     */
    public static AvailabilitySourceException retryable(RouteQuery query, String message) {
        return new AvailabilitySourceException(query, true, message, null);
    }

    /**
     * A transient failure that wraps the exception the source layer caught, so the
     * {@code IOException} or parse error underneath survives into the log.
     *
     * @param query   the query that failed
     * @param message what went wrong, for a log
     * @param cause   the underlying exception, or null if there was none
     * @return the failure, ready to throw
     * @throws NullPointerException if {@code query} is null
     */
    public static AvailabilitySourceException retryable(RouteQuery query, String message, Throwable cause) {
        return new AvailabilitySourceException(query, true, message, cause);
    }

    /**
     * A failure that re-issuing the same query cannot get past: the Mileage Program does
     * not price this Route, the source rejected our credentials, the query asks for
     * something the source structurally cannot answer.
     *
     * Distinct from a transient failure because the caller's correct response is the
     * opposite one. Retrying these consumes the rate-limit budget that the genuinely
     * transient failures on other Routes need.
     *
     * @param query   the query that failed
     * @param message what went wrong, for a log
     * @return the failure, ready to throw
     * @throws NullPointerException if {@code query} is null
     */
    public static AvailabilitySourceException permanent(RouteQuery query, String message) {
        return new AvailabilitySourceException(query, false, message, null);
    }

    /**
     * A permanent failure that wraps the exception the source layer caught.
     *
     * @param query   the query that failed
     * @param message what went wrong, for a log
     * @param cause   the underlying exception, or null if there was none
     * @return the failure, ready to throw
     * @throws NullPointerException if {@code query} is null
     */
    public static AvailabilitySourceException permanent(RouteQuery query, String message, Throwable cause) {
        return new AvailabilitySourceException(query, false, message, cause);
    }

    /**
     * The query that failed.
     *
     * The immediate caller of {@code fetch} already holds it; this is for the layer above,
     * which fans a Watch out into many queries and collects the failures. Without it, a
     * batch of failures is a batch of messages with nothing to attribute them to.
     *
     * @return the failed query, never null
     */
    public RouteQuery query() {
        return query;
    }

    /**
     * Whether re-issuing the same query could plausibly succeed.
     *
     * A claim about this failure, not a retry policy. How long to wait, how many attempts
     * to make and when to give up belong to the caller doing the scheduling; all this
     * says is whether trying again is pointless.
     *
     * @return true if the failure was transient, false if the query will fail the same way
     *         every time
     */
    public boolean retryable() {
        return retryable;
    }
}
