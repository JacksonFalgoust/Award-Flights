package com.awardwatch.ingest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Objects;
import java.util.Optional;

@Component
public class SeatsAeroClient {
    private final RestClient restClient;

    public SeatsAeroClient(
        @Qualifier("seatsAeroRestClient") RestClient restClient
    ) {
        this.restClient = restClient;
    }

    public String search(SeatsAeroSearchRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("search")
                    .queryParam("origin_airport", request.originAirport())
                    .queryParam("destination_airport", request.destinationAirport())
                    .queryParam("start_date", request.startDate())
                    .queryParam("end_date", request.endDate())
                    .queryParam("sources", request.sourceSlug())
                    .queryParam("take", 1000)
                    .queryParam("skip", request.skip())
                    .queryParam("include_trips", request.includeTrips())
                    .queryParamIfPresent("cursor", Optional.ofNullable(request.cursor()))
                    .build())
                .retrieve()
                .body(String.class);
    }
}
