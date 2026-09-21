package com.awardwatch.ingest;

import java.time.LocalDate;
import java.util.Objects;

public record SeatsAeroSearchRequest(
        String originAirport,
        String destinationAirport,
        LocalDate startDate,
        LocalDate endDate,
        String sourceSlug,
        int skip,
        boolean includeTrips,
        Long cursor
) {

    public SeatsAeroSearchRequest(
            String originAirport,
            String destinationAirport,
            LocalDate startDate,
            LocalDate endDate,
            String sourceSlug,
            int skip,
            Long cursor
    ) {
        this(
            originAirport,
            destinationAirport,
            startDate,
            endDate,
            sourceSlug,
            skip,
            true,
            cursor
        );
    }

    public SeatsAeroSearchRequest {
        Objects.requireNonNull(originAirport, "originAirport cannot be null");
        Objects.requireNonNull(destinationAirport, "destinationAirport cannot be null");
        Objects.requireNonNull(startDate, "startDate cannot be null");
        Objects.requireNonNull(endDate, "endDate cannot be null");
        Objects.requireNonNull(sourceSlug, "sourceSlug cannot be null");

        if (originAirport.isBlank()) {
            throw new IllegalArgumentException("originAirport cannot be blank");
        }

        if (destinationAirport.isBlank()) {
            throw new IllegalArgumentException("destinationAirport cannot be blank");
        }

        if (sourceSlug.isBlank()) {
            throw new IllegalArgumentException("sourceSlug cannot be blank");
        }

        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate cannot be before startDate");
        }

        if (skip < 0) {
            throw new IllegalArgumentException("skip cannot be negative");
        }
    }
}
