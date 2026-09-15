package com.awardwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.awardwatch.domain.AvailabilityEntry;
import com.awardwatch.domain.AvailabilitySource;
import com.awardwatch.domain.AvailabilitySourceException;
import com.awardwatch.domain.Cabin;
import com.awardwatch.domain.DateRange;
import com.awardwatch.domain.Program;
import com.awardwatch.domain.Route;
import com.awardwatch.domain.RouteQuery;
import com.awardwatch.domain.Snapshot;

class SeatsAeroSourceTest {

    private SeatsAeroClient client;
    private ObjectMapper objectMapper;
    private Clock clock;

    private RouteQuery query() {
        return new RouteQuery(
            Route.of("ATL", "NRT"),
            new DateRange(
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 7)
            ),
            Program.AADVANTAGE
        );
    }

    private String availability(String id) {
        return availability(
            id,
            "ATL",
            "NRT",
            "american",
            "american",
            "2026-10-01",
            "2026-09-10T11:00:00Z"
        );
    }

    private String availability(
        String id,
        String origin,
        String destination,
        String source,
        String routeSource,
        String date,
        String updatedAt
    ) {
        return """
            {
              "ID": "%s",
              "Route": {
                "OriginAirport": "%s",
                "DestinationAirport": "%s",
                "Source": "%s"
              },
              "Date": "%s",
              "Source": "%s",
              "UpdatedAt": "%s"
            }
            """.formatted(
                id,
                origin,
                destination,
                routeSource,
                date,
                source,
                updatedAt
            );
    }

    private String page(boolean hasMore, Long cursor, String... availabilityObjects) {
        return """
            {
              "data": [%s],
              "count": %d,
              "hasMore": %s,
              "cursor": %s
            }
            """.formatted(
                String.join(",", availabilityObjects),
                availabilityObjects.length,
                hasMore,
                cursor == null ? "null" : cursor.toString()
            );
    }

    private String availabilityWithAwards(
        String id,
        String summaryFields,
        String... trips
    ) {
        return """
            {
              "ID": "%s",
              "Route": {
                "OriginAirport": "ATL",
                "DestinationAirport": "NRT",
                "Source": "american"
              },
              "Date": "2026-10-01",
              "Source": "american",
              "UpdatedAt": "2026-09-10T11:00:00Z",
              %s,
              "AvailabilityTrips": [%s]
            }
            """.formatted(
                id,
                summaryFields,
                String.join(",", trips)
            );
    }

    private String trip(
        String id,
        String availabilityId,
        String cabin,
        Integer mileageCost,
        Integer remainingSeats,
        Integer stops,
        String source,
        Integer mixedCabinPercentage,
        String segmentsJson,
        String connectionsJson
    ) {
        return """
            {
              "ID": %s,
              "AvailabilityID": %s,
              "AvailabilitySegments": %s,
              "Connections": %s,
              "Stops": %s,
              "RemainingSeats": %s,
              "MileageCost": %s,
              "Cabin": %s,
              "MixedCabinPct": %s,
              "Source": %s
            }
            """.formatted(
                jsonString(id),
                jsonString(availabilityId),
                segmentsJson,
                connectionsJson,
                jsonNumber(stops),
                jsonNumber(remainingSeats),
                jsonNumber(mileageCost),
                jsonString(cabin),
                jsonNumber(mixedCabinPercentage),
                jsonString(source)
            );
    }

    private String jsonString(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private String jsonNumber(Integer value) {
        return value == null ? "null" : value.toString();
    }

    private void assertRetryableCandidateFailure(
        String availabilityJson,
        String expectedMessage
    ) {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(
            page(false, null, availabilityJson)
        );

        assertThatThrownBy(() -> source.fetchCandidates(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception.getMessage()).contains(expectedMessage);
                }
            );
    }

    @BeforeEach
    void setUp() {
        client = mock(SeatsAeroClient.class);
        objectMapper = JsonMapper.builder().build();
        clock = Clock.fixed(
            Instant.parse("2026-09-10T12:00:00Z"),
            ZoneOffset.UTC
        );
    }

    @Test 
    void constructorAcceptsRequiredDependencies() {
        new SeatsAeroSource(client, objectMapper, clock);
    }

    @Test 
    void constructorRejectsNullDependencies() {
        assertThatThrownBy(() -> new SeatsAeroSource(null, objectMapper, clock))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("client cannot be null");
        assertThatThrownBy(() -> new SeatsAeroSource(client, null, clock))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("objectMapper cannot be null");
        assertThatThrownBy(() -> new SeatsAeroSource(client, objectMapper, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("clock cannot be null");
    }

    @Test 
    void supportsMappedPrograms() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);

        for (Program program : Program.values()) {
            assertThat(source.supports(program)).isTrue();
        }
    }

    @Test 
    void supportsRejectsNullProgram() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);

        assertThatThrownBy(() -> source.supports(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("program cannot be null");
    }

    @Test
    void mapsProgramsToSeatsAeroSlug() {
        assertThat(SeatsAeroSource.sourceSlugFor(Program.AADVANTAGE)).contains("american");
        assertThat(SeatsAeroSource.sourceSlugFor(Program.FLYING_CLUB)).contains("virginatlantic");
        assertThat(SeatsAeroSource.sourceSlugFor(Program.TUDOAZUL)).contains("azul");
    }

    @Test 
    void programMappingsRoundTrip() {
        for (Program program : Program.values()) {
            String slug = SeatsAeroSource.sourceSlugFor(program)
                .orElseThrow(() ->
                    new AssertionError("Missing mapping for " + program)
                );

            assertThat(SeatsAeroSource.programForSourceSlug(slug)).contains(program);
        }
    }

    @Test 
    void mapsVendorCabinNames() {
        assertThat(SeatsAeroSource.cabinForVendorName("economy")).contains(Cabin.ECONOMY);
        assertThat(SeatsAeroSource.cabinForVendorName("premium")).contains(Cabin.PREMIUM_ECONOMY);
        assertThat(SeatsAeroSource.cabinForVendorName("BUSINESS")).contains(Cabin.BUSINESS);
    }

    @Test 
    void unknownVendorValuesReturnEmpty() {
        assertThat(SeatsAeroSource.programForSourceSlug("new-program")).isEmpty();
        assertThat(SeatsAeroSource.cabinForVendorName("suite")).isEmpty();
    }

    @Test 
    void nullDomainValuesAreRejected() {
        assertThatThrownBy(() -> SeatsAeroSource.sourceSlugFor(null))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> SeatsAeroSource.vendorNameFor(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test 
    void createsInitialSearchRequestFromRouteQuery() throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);

        RouteQuery query = new RouteQuery(
            Route.of("ATL", "NRT"),
            new DateRange(
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 7)
            ),
            Program.AADVANTAGE
        );

        SeatsAeroSearchRequest request = source.createInitialSearchRequest(query);

        assertThat(request.originAirport()).isEqualTo("ATL");
        assertThat(request.destinationAirport()).isEqualTo("NRT");
        assertThat(request.startDate())
            .isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(request.endDate())
            .isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(request.sourceSlug()).isEqualTo("american");
        assertThat(request.skip()).isZero();
        assertThat(request.includeTrips()).isTrue();
        assertThat(request.cursor()).isNull();
    }

    @Test 
    void creatingInitialRequestRejectsNullQuery() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);

        assertThatThrownBy(
            () -> source.createInitialSearchRequest(null)
        )
            .isInstanceOf(NullPointerException.class)
            .hasMessage("query cannot be null");
    }

    @Test 
    void fetchesSinglePage() throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);

        RouteQuery query = query();

        when(client.search(any())).thenReturn(
            page(false, null, availability("availability-1"))
        );

        List<SeatsAeroSource.ObservedAvailability> results =
            source.fetchAllPages(query);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().availability().id())
            .isEqualTo("availability-1");
        assertThat(results.getFirst().observedAt())
            .isEqualTo(Instant.parse("2026-09-10T12:00:00Z"));
    }

    @Test
    void paginatesWithFirstCursorAndDeduplicatesByAvailabilityId()
        throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();

        when(client.search(any())).thenReturn(
            page(true, 123L, availability("A")),
            page(true, 999L, availability("A"), availability("B")),
            page(false, null, availability("C"))
        );

        List<SeatsAeroSource.ObservedAvailability> results =
            source.fetchAllPages(query);

        assertThat(results)
            .extracting(result -> result.availability().id())
            .containsExactly("A", "B", "C");

        ArgumentCaptor<SeatsAeroSearchRequest> requestCaptor =
            ArgumentCaptor.forClass(SeatsAeroSearchRequest.class);
        verify(client, times(3)).search(requestCaptor.capture());

        List<SeatsAeroSearchRequest> requests = requestCaptor.getAllValues();
        assertThat(requests)
            .extracting(SeatsAeroSearchRequest::skip)
            .containsExactly(0, 1, 3);
        assertThat(requests.get(0).cursor()).isNull();
        assertThat(requests.get(1).cursor()).isEqualTo(123L);
        assertThat(requests.get(2).cursor()).isEqualTo(123L);
    }

    @Test
    void nullResponseBodyIsRetryableFailure() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(null);

        assertThatThrownBy(() -> source.fetchAllPages(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception.getMessage())
                        .isEqualTo("Seats.aero returned an empty response body");
                }
            );
    }

    @Test
    void malformedJsonIsRetryableFailure() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn("{not-json");

        assertThatThrownBy(() -> source.fetchAllPages(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception.getCause()).isNotNull();
                }
            );
    }

    @Test
    void hasMoreWithEmptyDataIsRetryableFailure() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(page(true, 123L));

        assertThatThrownBy(() -> source.fetchAllPages(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception.getCause()).isNotNull();
                }
            );
    }

    @Test
    void failureOnLaterPageDoesNotReturnPartialResults() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(
            page(true, 123L, availability("A")),
            "{not-json"
        );

        assertThatThrownBy(() -> source.fetchAllPages(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.query()).isSameAs(query);
                }
            );

        verify(client, times(2)).search(any());
    }

    @Test
    void validAvailabilityIsRetained() throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(
            page(false, null, availability("A"))
        );

        List<SeatsAeroSource.ObservedAvailability> results =
            source.fetchValidatedAvailabilities(query);

        assertThat(results)
            .extracting(result -> result.availability().id())
            .containsExactly("A");
    }

    @Test
    void lowercaseAirportCodesMatchCanonicalQueryRoute()
        throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availability(
                    "A",
                    "atl",
                    "nrt",
                    "american",
                    "american",
                    "2026-10-01",
                    "2026-09-10T11:00:00Z"
                )
            )
        );

        List<SeatsAeroSource.ObservedAvailability> results =
            source.fetchValidatedAvailabilities(query);

        assertThat(results).hasSize(1);
    }

    @Test
    void differentRouteIsRetryableFailure() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availability(
                    "A",
                    "JFK",
                    "LHR",
                    "american",
                    "american",
                    "2026-10-01",
                    "2026-09-10T11:00:00Z"
                )
            )
        );

        assertThatThrownBy(() -> source.fetchValidatedAvailabilities(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception.getMessage())
                        .contains("belongs to route JFK-LHR");
                }
            );
    }

    @Test
    void invalidAirportCodeIsRetryableFailure() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availability(
                    "A",
                    "ATLX",
                    "NRT",
                    "american",
                    "american",
                    "2026-10-01",
                    "2026-09-10T11:00:00Z"
                )
            )
        );

        assertThatThrownBy(() -> source.fetchValidatedAvailabilities(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception.getCause())
                        .isInstanceOf(IllegalArgumentException.class);
                }
            );
    }

    @Test
    void departureDateOutsideQueryIsRetryableFailure() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availability(
                    "A",
                    "ATL",
                    "NRT",
                    "american",
                    "american",
                    "2026-10-08",
                    "2026-09-10T11:00:00Z"
                )
            )
        );

        assertThatThrownBy(() -> source.fetchValidatedAvailabilities(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception.getMessage()).contains("outside");
                }
            );
    }

    @Test
    void knownDifferentProgramIsRetryableFailure() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availability(
                    "A",
                    "ATL",
                    "NRT",
                    "delta",
                    "delta",
                    "2026-10-01",
                    "2026-09-10T11:00:00Z"
                )
            )
        );

        assertThatThrownBy(() -> source.fetchValidatedAvailabilities(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception.getMessage())
                        .contains("belongs to program SKYMILES");
                }
            );
    }

    @Test
    void conflictingSourceSlugsAreRetryableFailure() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availability(
                    "A",
                    "ATL",
                    "NRT",
                    "american",
                    "delta",
                    "2026-10-01",
                    "2026-09-10T11:00:00Z"
                )
            )
        );

        assertThatThrownBy(() -> source.fetchValidatedAvailabilities(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception.getMessage())
                        .contains("conflicting source slugs");
                }
            );
    }

    @Test
    void futureUpdatedAtIsRetryableFailure() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availability(
                    "A",
                    "ATL",
                    "NRT",
                    "american",
                    "american",
                    "2026-10-01",
                    "2026-09-10T12:00:01Z"
                )
            )
        );

        assertThatThrownBy(() -> source.fetchValidatedAvailabilities(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception.getMessage())
                        .contains("updated after it was observed");
                }
            );
    }

    @Test
    void unknownProgramSlugIsSkipped() throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availability(
                    "A",
                    "ATL",
                    "NRT",
                    "new-program",
                    "new-program",
                    "2026-10-01",
                    "2026-09-10T11:00:00Z"
                )
            )
        );

        assertThat(source.fetchValidatedAvailabilities(query)).isEmpty();
    }

    @Test
    void validatedAvailabilityListIsImmutable()
        throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(
            page(false, null, availability("A"))
        );

        List<SeatsAeroSource.ObservedAvailability> results =
            source.fetchValidatedAvailabilities(query);

        assertThatThrownBy(results::clear)
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void savedFixtureMapsConnectingAndNonstopCandidates() throws Exception {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = new RouteQuery(
            Route.of("LAX", "HND"),
            DateRange.single(LocalDate.of(2026, 12, 12)),
            Program.ALASKA_MILEAGE_PLAN
        );
        String response = Files.readString(Path.of(
            "..",
            "..",
            "docs",
            "samples",
            "searchEndpointSampleResponse.json"
        ));
        when(client.search(any())).thenReturn(response);

        List<SeatsAeroSource.AwardCandidate> candidates =
            source.fetchCandidates(query);

        assertThat(candidates).anySatisfy(candidate -> {
            assertThat(candidate.entry().cabin()).isEqualTo(Cabin.ECONOMY);
            assertThat(candidate.entry().mileageCost()).isEqualTo(35_000);
            assertThat(candidate.entry().seatsRemaining()).isEqualTo(9);
            assertThat(candidate.entry().nonstop()).isFalse();
        });
        assertThat(candidates).anySatisfy(candidate -> {
            assertThat(candidate.entry().cabin()).isEqualTo(Cabin.BUSINESS);
            assertThat(candidate.entry().mileageCost()).isEqualTo(150_000);
            assertThat(candidate.entry().seatsRemaining()).isEqualTo(3);
            assertThat(candidate.entry().nonstop()).isFalse();
        });
        assertThat(candidates).anySatisfy(candidate -> {
            assertThat(candidate.entry().cabin()).isEqualTo(Cabin.ECONOMY);
            assertThat(candidate.entry().mileageCost()).isEqualTo(87_500);
            assertThat(candidate.entry().seatsRemaining()).isEqualTo(9);
            assertThat(candidate.entry().nonstop()).isTrue();
        });
        assertThat(candidates)
            .noneMatch(candidate ->
                "3FQhhVsOvoyNltv9lGrH43aGf8d".equals(candidate.vendorId())
            );
    }

    @Test
    void summaryCandidatePreservesZeroSeats() throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availabilityWithAwards(
                    "A",
                    """
                    "YAvailable": true,
                    "YMileageCost": "50000",
                    "YRemainingSeats": 0,
                    "YDirect": false
                    """
                )
            )
        );

        List<SeatsAeroSource.AwardCandidate> candidates =
            source.fetchCandidates(query);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.entry().mileageCost()).isEqualTo(50_000);
            assertThat(candidate.entry().seatsRemaining()).isZero();
            assertThat(candidate.entry().nonstop()).isFalse();
        });
    }

    @Test
    void availableSummaryWithMissingDirectIndicatorIsRetryableFailure() {
        assertRetryableCandidateFailure(
            availabilityWithAwards(
                "A",
                """
                "YAvailable": true,
                "YMileageCost": "50000",
                "YRemainingSeats": 1,
                "YDirect": null
                """
            ),
            "direct indicator is missing"
        );
    }

    @Test
    void availableDirectSummaryRequiresPositiveMileageCost() {
        List<Integer> invalidMileageCosts = Arrays.asList(null, 0, -1);

        for (Integer invalidMileageCost : invalidMileageCosts) {
            assertRetryableCandidateFailure(
                availabilityWithAwards(
                    "A",
                    """
                    "YAvailable": true,
                    "YDirect": true,
                    "YDirectMileageCost": %s,
                    "YDirectRemainingSeats": 1
                    """.formatted(jsonNumber(invalidMileageCost))
                ),
                "direct mileage cost must be positive"
            );
        }
    }

    @Test
    void zeroStopsMapsTripToNonstop() throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        String trip = trip(
            "trip-1", "A", "economy", 45_000, 2, 0,
            "american", null, "[]", "[]"
        );
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availabilityWithAwards("A", "\"YAvailable\": false", trip)
            )
        );

        List<SeatsAeroSource.AwardCandidate> candidates =
            source.fetchCandidates(query);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.entry().nonstop()).isTrue();
            assertThat(candidate.entry().cabin()).isEqualTo(Cabin.ECONOMY);
            assertThat(candidate.entry().mileageCost()).isEqualTo(45_000);
            assertThat(candidate.entry().seatsRemaining()).isEqualTo(2);
        });
    }

    @Test
    void positiveStopsMapsTripToConnecting() throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        String trip = trip(
            "trip-1", "A", "business", 75_000, 1, 1,
            "american", null, "[]", "[\"DFW\"]"
        );
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availabilityWithAwards("A", "\"YAvailable\": false", trip)
            )
        );

        List<SeatsAeroSource.AwardCandidate> candidates =
            source.fetchCandidates(query);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.entry().nonstop()).isFalse();
            assertThat(candidate.entry().cabin()).isEqualTo(Cabin.BUSINESS);
        });
    }

    @Test
    void missingStopsFallsBackToSegmentCount() throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        String trip = trip(
            "trip-1", "A", "economy", 45_000, 2, null,
            "american", null, "[{},{}]", "null"
        );
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availabilityWithAwards("A", "\"YAvailable\": false", trip)
            )
        );

        List<SeatsAeroSource.AwardCandidate> candidates =
            source.fetchCandidates(query);

        assertThat(candidates).singleElement().satisfies(candidate ->
            assertThat(candidate.entry().nonstop()).isFalse()
        );
    }

    @Test
    void missingStopsFallsBackToConnections() throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        String trip = trip(
            "trip-1", "A", "economy", 45_000, 2, null,
            "american", null, "[]", "[]"
        );
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availabilityWithAwards("A", "\"YAvailable\": false", trip)
            )
        );

        List<SeatsAeroSource.AwardCandidate> candidates =
            source.fetchCandidates(query);

        assertThat(candidates).singleElement().satisfies(candidate ->
            assertThat(candidate.entry().nonstop()).isTrue()
        );
    }

    @Test
    void unknownAndMissingTripCabinsAreSkipped() throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        String missingCabin = trip(
            "trip-1", "A", null, 45_000, 2, 0,
            "american", null, "[]", "[]"
        );
        String unknownCabin = trip(
            "trip-2", "A", "suite", 45_000, 2, 0,
            "american", null, "[]", "[]"
        );
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availabilityWithAwards(
                    "A",
                    "\"YAvailable\": false",
                    missingCabin,
                    unknownCabin
                )
            )
        );

        assertThat(source.fetchCandidates(query)).isEmpty();
    }

    @Test
    void mixedCabinTripUsesReportedCabin() throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        String trip = trip(
            "trip-1", "A", "business", 75_000, 1, 1,
            "american", 25, "[]", "[\"DFW\"]"
        );
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availabilityWithAwards("A", "\"YAvailable\": false", trip)
            )
        );

        List<SeatsAeroSource.AwardCandidate> candidates =
            source.fetchCandidates(query);

        assertThat(candidates).singleElement().satisfies(candidate ->
            assertThat(candidate.entry().cabin()).isEqualTo(Cabin.BUSINESS)
        );
    }

    @Test
    void missingTripMileageIsRetryableFailure() {
        assertRetryableCandidateFailure(
            availabilityWithAwards(
                "A",
                "\"YAvailable\": false",
                trip(
                    "trip-1", "A", "economy", null, 2, 0,
                    "american", null, "[]", "[]"
                )
            ),
            "mileage cost is missing"
        );
    }

    @Test
    void missingTripSeatsIsRetryableFailure() {
        assertRetryableCandidateFailure(
            availabilityWithAwards(
                "A",
                "\"YAvailable\": false",
                trip(
                    "trip-1", "A", "economy", 45_000, null, 0,
                    "american", null, "[]", "[]"
                )
            ),
            "remaining seats is missing"
        );
    }

    @Test
    void negativeTripValuesAreRetryableFailures() {
        List<String> invalidTrips = List.of(
            trip(
                "negative-mileage", "A", "economy", -1, 2, 0,
                "american", null, "[]", "[]"
            ),
            trip(
                "negative-seats", "A", "economy", 45_000, -1, 0,
                "american", null, "[]", "[]"
            ),
            trip(
                "negative-stops", "A", "economy", 45_000, 2, -1,
                "american", null, "[]", "[]"
            )
        );

        for (String invalidTrip : invalidTrips) {
            SeatsAeroClient iterationClient = mock(SeatsAeroClient.class);
            SeatsAeroSource source = new SeatsAeroSource(
                iterationClient,
                objectMapper,
                clock
            );
            RouteQuery query = query();
            when(iterationClient.search(any())).thenReturn(
                page(
                    false,
                    null,
                    availabilityWithAwards(
                        "A",
                        "\"YAvailable\": false",
                        invalidTrip
                    )
                )
            );

            assertThatThrownBy(() -> source.fetchCandidates(query))
                .isInstanceOfSatisfying(
                    AvailabilitySourceException.class,
                    exception -> {
                        assertThat(exception.retryable()).isTrue();
                        assertThat(exception.query()).isSameAs(query);
                    }
                );
        }
    }

    @Test
    void mismatchedAvailabilityIdIsRetryableFailure() {
        assertRetryableCandidateFailure(
            availabilityWithAwards(
                "A",
                "\"YAvailable\": false",
                trip(
                    "trip-1", "B", "economy", 45_000, 2, 0,
                    "american", null, "[]", "[]"
                )
            ),
            "different availability object"
        );
    }

    @Test
    void conflictingTripSourceIsRetryableFailure() {
        assertRetryableCandidateFailure(
            availabilityWithAwards(
                "A",
                "\"YAvailable\": false",
                trip(
                    "trip-1", "A", "economy", 45_000, 2, 0,
                    "delta", null, "[]", "[]"
                )
            ),
            "trip source does not match"
        );
    }

    @Test
    void invalidMixedCabinPercentageIsRetryableFailure() {
        for (int invalidPercentage : List.of(0, 101)) {
            assertRetryableCandidateFailure(
                availabilityWithAwards(
                    "A",
                    "\"YAvailable\": false",
                    trip(
                        "trip-1", "A", "business", 75_000, 1, 1,
                        "american", invalidPercentage, "[]", "[\"DFW\"]"
                    )
                ),
                "mixed-cabin percentage is outside 1-100"
            );
        }
    }

    @Test
    void undeterminableStopCountIsRetryableFailure() {
        assertRetryableCandidateFailure(
            availabilityWithAwards(
                "A",
                "\"YAvailable\": false",
                trip(
                    "trip-1", "A", "economy", 45_000, 2, null,
                    "american", null, "[]", "null"
                )
            ),
            "stop count cannot be determined"
        );
    }

    @Test
    void candidateListIsImmutable() throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(
            page(
                false,
                null,
                availabilityWithAwards(
                    "A",
                    """
                    "YAvailable": true,
                    "YMileageCost": "50000",
                    "YRemainingSeats": 1,
                    "YDirect": false
                    """
                )
            )
        );

        List<SeatsAeroSource.AwardCandidate> candidates =
            source.fetchCandidates(query);

        assertThatThrownBy(candidates::clear)
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void implementsAvailabilitySource() {
        AvailabilitySource source = new SeatsAeroSource(client, objectMapper, clock);

        assertThat(source).isInstanceOf(SeatsAeroSource.class);
    }

    @Test
    void reconcilesCandidatesByLowestMileageThenMostSeats() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        Instant observedAt = clock.instant();
        Instant refreshedAt = observedAt.minusSeconds(3_600);

        List<SeatsAeroSource.AwardCandidate> candidates = List.of(
            candidate(query, 50_000, 9, false, observedAt, refreshedAt, "expensive"),
            candidate(query, 45_000, 1, false, observedAt, refreshedAt, "cheap-few"),
            candidate(query, 45_000, 3, false, observedAt, refreshedAt, "cheap-many"),
            candidate(query, 60_000, 2, true, observedAt, refreshedAt, "nonstop")
        );

        List<AvailabilityEntry> entries = source.reconcileCandidates(candidates);

        assertThat(entries).hasSize(2);
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.nonstop()).isFalse();
            assertThat(entry.mileageCost()).isEqualTo(45_000);
            assertThat(entry.seatsRemaining()).isEqualTo(3);
        });
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.nonstop()).isTrue();
            assertThat(entry.mileageCost()).isEqualTo(60_000);
            assertThat(entry.seatsRemaining()).isEqualTo(2);
        });
        assertThatThrownBy(entries::clear)
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fetchReturnsReconciledSnapshotFromSavedFixture() throws Exception {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = new RouteQuery(
            Route.of("LAX", "HND"),
            DateRange.single(LocalDate.of(2026, 12, 12)),
            Program.ALASKA_MILEAGE_PLAN
        );
        String response = Files.readString(Path.of(
            "..",
            "..",
            "docs",
            "samples",
            "searchEndpointSampleResponse.json"
        ));
        when(client.search(any())).thenReturn(response);

        Snapshot snapshot = source.fetch(query);

        assertThat(snapshot.query()).isSameAs(query);
        assertThat(snapshot.fetchedAt()).isEqualTo(clock.instant());
        assertThat(snapshot.entries()).hasSize(3);
        assertThat(snapshot.entries()).anySatisfy(entry -> {
            assertThat(entry.cabin()).isEqualTo(Cabin.ECONOMY);
            assertThat(entry.nonstop()).isFalse();
            assertThat(entry.mileageCost()).isEqualTo(35_000);
            assertThat(entry.seatsRemaining()).isEqualTo(9);
        });
        assertThat(snapshot.entries()).anySatisfy(entry -> {
            assertThat(entry.cabin()).isEqualTo(Cabin.BUSINESS);
            assertThat(entry.nonstop()).isFalse();
            assertThat(entry.mileageCost()).isEqualTo(150_000);
            assertThat(entry.seatsRemaining()).isEqualTo(3);
        });
        assertThat(snapshot.entries()).anySatisfy(entry -> {
            assertThat(entry.cabin()).isEqualTo(Cabin.ECONOMY);
            assertThat(entry.nonstop()).isTrue();
            assertThat(entry.mileageCost()).isEqualTo(87_500);
            assertThat(entry.seatsRemaining()).isEqualTo(9);
        });
    }

    @Test
    void fetchReturnsEmptySnapshotWhenNothingIsAvailable()
        throws AvailabilitySourceException {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        when(client.search(any())).thenReturn(page(false, null));

        Snapshot snapshot = source.fetch(query);

        assertThat(snapshot.query()).isSameAs(query);
        assertThat(snapshot.fetchedAt()).isEqualTo(clock.instant());
        assertThat(snapshot.entries()).isEmpty();
    }

    @Test
    void unauthorizedResponseIsAPermanentFailure() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        RestClientResponseException cause = responseFailure(HttpStatus.UNAUTHORIZED);
        when(client.search(any())).thenThrow(cause);

        assertThatThrownBy(() -> source.fetch(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception).hasMessageContaining("HTTP 401");
                    assertThat(exception).hasCause(cause);
                }
            );
    }

    @Test
    void rateLimitedResponseIsARetryableFailure() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        RestClientResponseException cause = responseFailure(HttpStatus.TOO_MANY_REQUESTS);
        when(client.search(any())).thenThrow(cause);

        assertThatThrownBy(() -> source.fetch(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception).hasMessageContaining("HTTP 429");
                    assertThat(exception).hasCause(cause);
                }
            );
    }

    @Test
    void serverErrorResponseIsARetryableFailure() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        RestClientResponseException cause = responseFailure(HttpStatus.SERVICE_UNAVAILABLE);
        when(client.search(any())).thenThrow(cause);

        assertThatThrownBy(() -> source.fetch(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception).hasMessageContaining("HTTP 503");
                    assertThat(exception).hasCause(cause);
                }
            );
    }

    @Test
    void otherClientErrorResponseIsAPermanentFailure() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        RestClientResponseException cause = responseFailure(HttpStatus.BAD_REQUEST);
        when(client.search(any())).thenThrow(cause);

        assertThatThrownBy(() -> source.fetch(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception).hasMessageContaining("HTTP 400");
                    assertThat(exception).hasCause(cause);
                }
            );
    }

    @Test
    void transportFailureIsRetryable() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);
        RouteQuery query = query();
        ResourceAccessException cause = new ResourceAccessException("connection reset");
        when(client.search(any())).thenThrow(cause);

        assertThatThrownBy(() -> source.fetch(query))
            .isInstanceOfSatisfying(
                AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.query()).isSameAs(query);
                    assertThat(exception).hasMessageContaining("before a response");
                    assertThat(exception).hasCause(cause);
                }
            );
    }

    @Test
    void fetchRejectsNullQueryBeforeCallingClient() {
        SeatsAeroSource source = new SeatsAeroSource(client, objectMapper, clock);

        assertThatThrownBy(() -> source.fetch(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("query cannot be null");

        verify(client, times(0)).search(any());
    }

    private SeatsAeroSource.AwardCandidate candidate(
        RouteQuery query,
        int mileageCost,
        int seatsRemaining,
        boolean nonstop,
        Instant observedAt,
        Instant refreshedAt,
        String vendorId
    ) {
        return new SeatsAeroSource.AwardCandidate(
            new AvailabilityEntry(
                query.route(),
                query.departureDates().start(),
                query.program(),
                Cabin.ECONOMY,
                mileageCost,
                seatsRemaining,
                nonstop,
                observedAt,
                refreshedAt
            ),
            vendorId
        );
    }

    private RestClientResponseException responseFailure(HttpStatus status) {
        return new RestClientResponseException(
            status.getReasonPhrase(),
            status,
            status.getReasonPhrase(),
            HttpHeaders.EMPTY,
            new byte[0],
            StandardCharsets.UTF_8
        );
    }
}
