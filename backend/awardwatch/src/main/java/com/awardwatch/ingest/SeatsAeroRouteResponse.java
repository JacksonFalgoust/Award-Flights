package com.awardwatch.ingest;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record SeatsAeroRouteResponse (
    @JsonProperty("ID")
    String id,

    @JsonProperty("OriginAirport")
    String originAirport,

    @JsonProperty("DestinationAirport")
    String destinationAirport,

    @JsonProperty("Source")
    String source
) {
    
    SeatsAeroRouteResponse {

        Objects.requireNonNull(originAirport, "originAirport cannot be null");
        Objects.requireNonNull(destinationAirport, "destinationAirport cannot be null");

        if (originAirport.isBlank()) {
            throw new IllegalArgumentException("originAirport cannot be blank");
        }

        if (destinationAirport.isBlank()) {
            throw new IllegalArgumentException("destinationAirport cannot be blank");
        }

        if (source != null && source.isBlank()) {
            throw new IllegalArgumentException("source cannot be blank when provided");
        }

    }

}
