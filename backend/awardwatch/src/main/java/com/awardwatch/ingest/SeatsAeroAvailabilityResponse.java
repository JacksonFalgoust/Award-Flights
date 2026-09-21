package com.awardwatch.ingest;

import java.util.List;
import java.util.Objects;
import java.time.Instant;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record SeatsAeroAvailabilityResponse(
    @JsonProperty("ID")
    String id,

    @JsonProperty("Route")
    SeatsAeroRouteResponse route,

    @JsonProperty("Date")
    LocalDate date,

    @JsonProperty("Source")
    String source,

    @JsonProperty("UpdatedAt")
    Instant updatedAt,

    @JsonProperty("YAvailable")
    Boolean yAvailable,

    @JsonProperty("WAvailable")
    Boolean wAvailable,

    @JsonProperty("JAvailable")
    Boolean jAvailable,

    @JsonProperty("FAvailable")
    Boolean fAvailable,

    @JsonProperty("YMileageCost")
    String yMileageCost,

    @JsonProperty("WMileageCost")
    String wMileageCost,

    @JsonProperty("JMileageCost")
    String jMileageCost,

    @JsonProperty("FMileageCost")
    String fMileageCost,

    @JsonProperty("YRemainingSeats")
    Integer yRemainingSeats,

    @JsonProperty("WRemainingSeats")
    Integer wRemainingSeats,

    @JsonProperty("JRemainingSeats")
    Integer jRemainingSeats,

    @JsonProperty("FRemainingSeats")
    Integer fRemainingSeats,

    @JsonProperty("YDirect")
    Boolean yDirect,

    @JsonProperty("WDirect")
    Boolean wDirect,

    @JsonProperty("JDirect")
    Boolean jDirect,

    @JsonProperty("FDirect")
    Boolean fDirect,

    @JsonProperty("YDirectMileageCost")
    Integer yDirectMileageCost,

    @JsonProperty("WDirectMileageCost")
    Integer wDirectMileageCost,

    @JsonProperty("JDirectMileageCost")
    Integer jDirectMileageCost,

    @JsonProperty("FDirectMileageCost")
    Integer fDirectMileageCost,

    @JsonProperty("YDirectRemainingSeats")
    Integer yDirectRemainingSeats,

    @JsonProperty("WDirectRemainingSeats")
    Integer wDirectRemainingSeats,

    @JsonProperty("JDirectRemainingSeats")
    Integer jDirectRemainingSeats,

    @JsonProperty("FDirectRemainingSeats")
    Integer fDirectRemainingSeats,

    @JsonProperty("AvailabilityTrips")
    List<SeatsAeroTripResponse> availabilityTrips
) {
    
    boolean hasAnyAvailableCabin() {
        return Boolean.TRUE.equals(yAvailable) ||
               Boolean.TRUE.equals(wAvailable) ||
               Boolean.TRUE.equals(jAvailable) ||
               Boolean.TRUE.equals(fAvailable);
    }

    SeatsAeroAvailabilityResponse {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(route, "route cannot be null");
        Objects.requireNonNull(date, "date cannot be null");
        Objects.requireNonNull(source, "source cannot be null");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null");

        if (id.isBlank()) {
            throw new IllegalArgumentException("id cannot be blank");
        }

        if (source.isBlank()) {
            throw new IllegalArgumentException("source cannot be blank");
        }

        if (availabilityTrips != null) {
            availabilityTrips = List.copyOf(availabilityTrips);
        }

    }

}
