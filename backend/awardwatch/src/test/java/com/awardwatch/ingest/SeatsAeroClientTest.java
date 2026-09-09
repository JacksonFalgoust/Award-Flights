package com.awardwatch.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class SeatsAeroClientTest {

    private static final String BASE_URL = "https://seats.aero/partnerapi/";
    private static final String FAKE_API_KEY = "test-key-not-a-secret";
    private static final String EMPTY_RESPONSE = """
            {
              "data": [],
              "count": 0,
              "hasMore": false,
              "cursor": 0
            }
            """;

    private MockRestServiceServer server;
    private SeatsAeroClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        SeatsAeroProperties properties = new SeatsAeroProperties(BASE_URL, FAKE_API_KEY);
        RestClient restClient = new SeatsAeroClientConfiguration()
                .seatsAeroRestClient(builder, properties);

        client = new SeatsAeroClient(restClient);
    }

    @Test
    void searchSendsExpectedRequestWithoutCursor() {
        server.expect(requestTo(
                        BASE_URL
                                + "search?origin_airport=ATL"
                                + "&destination_airport=NRT"
                                + "&start_date=2026-10-01"
                                + "&end_date=2026-10-07"
                                + "&sources=american"
                                + "&take=1000"
                                + "&skip=0"
                ))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Partner-Authorization", FAKE_API_KEY))
                .andRespond(withSuccess(EMPTY_RESPONSE, MediaType.APPLICATION_JSON));

        SeatsAeroSearchRequest request = new SeatsAeroSearchRequest(
                "ATL",
                "NRT",
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 7),
                "american",
                0,
                null
        );

        String response = client.search(request);

        assertThat(response).isEqualTo(EMPTY_RESPONSE);
        server.verify();
    }

    @Test
    void searchIncludesPaginationParametersWhenProvided() {
        server.expect(requestTo(
                        BASE_URL
                                + "search?origin_airport=ATL"
                                + "&destination_airport=NRT"
                                + "&start_date=2026-10-01"
                                + "&end_date=2026-10-07"
                                + "&sources=american"
                                + "&take=1000"
                                + "&skip=1000"
                                + "&cursor=1689009958"
                ))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Partner-Authorization", FAKE_API_KEY))
                .andRespond(withSuccess(EMPTY_RESPONSE, MediaType.APPLICATION_JSON));

        SeatsAeroSearchRequest request = new SeatsAeroSearchRequest(
                "ATL",
                "NRT",
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 7),
                "american",
                1_000,
                1_689_009_958L
        );

        String response = client.search(request);

        assertThat(response).isEqualTo(EMPTY_RESPONSE);
        server.verify();
    }

    @Test
    void nullRequestIsRejectedBeforeSendingAnything() {
        assertThatThrownBy(() -> client.search(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("request cannot be null");

        server.verify();
    }

    @Test
    void rateLimitedResponseRemainsAFailure() {
        server.expect(requestTo(
                        BASE_URL
                                + "search?origin_airport=ATL"
                                + "&destination_airport=NRT"
                                + "&start_date=2026-10-01"
                                + "&end_date=2026-10-07"
                                + "&sources=american"
                                + "&take=1000"
                                + "&skip=0"
                ))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Partner-Authorization", FAKE_API_KEY))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":"rate limit exceeded"}
                                """));

        SeatsAeroSearchRequest request = new SeatsAeroSearchRequest(
                "ATL",
                "NRT",
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 7),
                "american",
                0,
                null
        );

        assertThatThrownBy(() -> client.search(request))
                .isInstanceOfSatisfying(
                        RestClientResponseException.class,
                        exception -> {
                            assertThat(exception.getStatusCode())
                                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                            assertThat(exception.getResponseBodyAsString())
                                    .contains("rate limit exceeded");
                        }
                );

        server.verify();
    }

    @Test
    void fullSavedSearchResponseIsReturnedUnchanged() throws IOException {
        String savedResponse = Files.readString(Path.of(
                "..",
                "..",
                "docs",
                "samples",
                "searchEndpointSampleResponse.json"
        ));

        server.expect(requestTo(
                        BASE_URL
                                + "search?origin_airport=SFO"
                                + "&destination_airport=JFK"
                                + "&start_date=2023-08-11"
                                + "&end_date=2023-08-11"
                                + "&sources=american"
                                + "&take=1000"
                                + "&skip=0"
                ))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Partner-Authorization", FAKE_API_KEY))
                .andRespond(withSuccess(savedResponse, MediaType.APPLICATION_JSON));

        SeatsAeroSearchRequest request = new SeatsAeroSearchRequest(
                "SFO",
                "JFK",
                LocalDate.of(2023, 8, 11),
                LocalDate.of(2023, 8, 11),
                "american",
                0,
                null
        );

        String response = client.search(request);

        assertThat(response).isEqualTo(savedResponse);
        server.verify();
    }
}
