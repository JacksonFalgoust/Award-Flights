package com.awardwatch.ingest;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record SeatsAeroTripResponse(
    @JsonProperty("ID")
    String id,

    @JsonProperty("AvailabilityID")
    String availabilityId,

    @JsonProperty("AvailabilitySegments")
    List<SeatsAeroSegmentResponse> availabilitySegments,

    @JsonProperty("Connections")
    List<String> connections,

    @JsonProperty("Stops")
    Integer stops,

    @JsonProperty("RemainingSeats")
    Integer remainingSeats,

    @JsonProperty("MileageCost")
    Integer mileageCost,

    @JsonProperty("Cabin")
    String cabin,

    @JsonProperty("MixedCabinPct")
    Integer mixedCabinPercentage,

    @JsonProperty("Source")
    String source
) {
    
    SeatsAeroTripResponse {
        if (availabilitySegments != null) {
            availabilitySegments = List.copyOf(availabilitySegments);
        }

        if (connections != null) {
            connections = List.copyOf(connections);
        }
    }
}
