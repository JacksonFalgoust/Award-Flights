package com.awardwatch.ingest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import java.util.Objects;
import java.util.Optional;

@Component
public class SeatsAeroClient {
    private static final int MIN_CABIN_PERCENTAGE = 0;
    private static final Logger LOGGER = LoggerFactory.getLogger(SeatsAeroClient.class);

    private final RestClient restClient;

    public SeatsAeroClient(
        @Qualifier("seatsAeroRestClient") RestClient restClient
    ) {
        this.restClient = restClient;
    }

    public String search(SeatsAeroSearchRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        String callId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        int cost = 1;
        String status = "unknown";
        String outcome = "client_error";
        try {
            ResponseEntity<String> response = execute(request);
            status = Integer.toString(response.getStatusCode().value());
            outcome = "response";
            return response.getBody();
        } catch (RestClientResponseException exception) {
            status = Integer.toString(exception.getStatusCode().value());
            outcome = "http_error";
            throw exception;
        } catch (ResourceAccessException exception) {
            cost = 0;
            outcome = "transport_error";
            throw exception;
        } finally {
            // Cost follows our quota policy: every HTTP response costs one call;
            // transport failures cost zero. Redis refund success is logged separately.
            // Never include credentials, headers, response bodies or exception text.
            LOGGER.info("upstream_call call_id={} source=seats.aero endpoint=search origin={} destination={} "
                    + "program={} date_from={} date_to={} skip={} outcome={} status={} cost={} duration_ms={}",
                callId, request.originAirport(), request.destinationAirport(), request.sourceSlug(),
                request.startDate(), request.endDate(), request.skip(), outcome, status, cost,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    private ResponseEntity<String> execute(SeatsAeroSearchRequest request) {
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
                    .queryParam("min_cabin_pct", MIN_CABIN_PERCENTAGE)
                    .queryParamIfPresent("cursor", Optional.ofNullable(request.cursor()))
                    .build())
                .retrieve()
                .toEntity(String.class);
    }
}
