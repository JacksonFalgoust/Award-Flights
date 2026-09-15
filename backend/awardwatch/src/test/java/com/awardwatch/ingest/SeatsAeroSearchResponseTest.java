package com.awardwatch.ingest;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SeatsAeroSearchResponseTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void savedResponseDeserializesIncludingIncompleteTrip() throws Exception {
        String json = Files.readString(Path.of(
                "..",
                "..",
                "docs",
                "samples",
                "searchEndpointSampleResponse.json"
        ));

        SeatsAeroSearchResponse response = jsonMapper.readValue(
                json,
                SeatsAeroSearchResponse.class
        );

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.hasMore()).isFalse();
        assertThat(response.cursor()).isNull();
        assertThat(response.data()).hasSize(1);

        SeatsAeroAvailabilityResponse availability = response.data().getFirst();

        assertThat(availability.id()).isEqualTo("37K1kkxOhQ8ELU2JGSnEmPAAA5y");
        assertThat(availability.route().originAirport()).isEqualTo("LAX");
        assertThat(availability.route().destinationAirport()).isEqualTo("HND");
        assertThat(availability.date()).isEqualTo(LocalDate.of(2026, 12, 12));
        assertThat(availability.source()).isEqualTo("alaska");
        assertThat(availability.updatedAt())
                .isEqualTo(Instant.parse("2026-09-09T14:41:50.056054Z"));
        assertThat(availability.availabilityTrips()).hasSize(12);

        assertThat(availability.yDirect()).isTrue();
        assertThat(availability.yDirectMileageCost()).isEqualTo(87_500);
        assertThat(availability.yDirectRemainingSeats()).isEqualTo(9);

        SeatsAeroTripResponse incompleteTrip = availability.availabilityTrips().stream()
                .filter(trip -> "3FQhhVsOvoyNltv9lGrH43aGf8d".equals(trip.id()))
                .findFirst()
                .orElseThrow();

        assertThat(incompleteTrip.cabin()).isNull();
        assertThat(incompleteTrip.source()).isNull();
        assertThat(incompleteTrip.stops()).isEqualTo(1);
        assertThat(incompleteTrip.connections()).containsExactly("HNL");
    }
}
