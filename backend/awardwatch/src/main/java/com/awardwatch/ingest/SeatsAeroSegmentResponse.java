package com.awardwatch.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record SeatsAeroSegmentResponse(
    @JsonProperty("OriginAirport")
    String originAirport,

    @JsonProperty("DestinationAirport")
    String destinationAirport,

    @JsonProperty("Order")
    Integer order
) {
    
}
