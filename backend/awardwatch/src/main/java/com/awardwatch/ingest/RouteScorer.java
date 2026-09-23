package com.awardwatch.ingest;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Pure crawl priority calculation; higher scores run first. */
public final class RouteScorer {
    private RouteScorer() {}

    /**
     * Uses UTC departure dates. Unknown history receives maximum staleness.
     * Hit rate is a proxy based on consecutive successful empty crawls; a hit
     * resets it to one. Failed requests must not update this history.
     */
    public static double score(LocalDate departureStart, Instant lastCrawledAt,
                               int consecutiveEmpty, Instant now) {
        Objects.requireNonNull(departureStart, "departureStart cannot be null");
        Objects.requireNonNull(now, "now cannot be null");
        if (consecutiveEmpty < 0) throw new IllegalArgumentException("consecutiveEmpty cannot be negative");
        long days = Math.max(0, ChronoUnit.DAYS.between(now.atZone(ZoneOffset.UTC).toLocalDate(), departureStart));
        double urgency = 1.0 + Math.max(0, 21 - days) / 21.0;
        double staleness = lastCrawledAt == null ? 1440.0
            : Math.clamp(Duration.between(lastCrawledAt, now).toSeconds() / 60.0, 0.0, 1440.0);
        double hitRate = Math.max(0.1, Math.pow(0.9, consecutiveEmpty));
        return urgency * staleness * hitRate;
    }
}
