package com.awardwatch.ingest;

import java.util.Objects;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record SeatsAeroSearchResponse(
    List<SeatsAeroAvailabilityResponse> data,
    Integer count,
    Boolean hasMore,
    Long cursor
) {
    
    SeatsAeroSearchResponse {
        Objects.requireNonNull(data, "data cannot be null");
        Objects.requireNonNull(count, "count cannot be null");
        Objects.requireNonNull(hasMore, "hasMore cannot be null");

        data = List.copyOf(data);

        if (count < 0) {
            throw new IllegalArgumentException("count cannot be negative");
        }

        if (Boolean.TRUE.equals(hasMore)) {
            Objects.requireNonNull(cursor, "cursor cannot be null when hasMore is true");

            if (data.isEmpty()) {
                throw new IllegalArgumentException("data cannot be empty when hasMore is true");
            }
        }
    }

}
