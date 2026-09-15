package com.awardwatch.ingest;

import com.awardwatch.domain.AvailabilityEntry;
import com.awardwatch.domain.AvailabilitySource;
import com.awardwatch.domain.AvailabilitySourceException;
import com.awardwatch.domain.Cabin;
import com.awardwatch.domain.Program;
import com.awardwatch.domain.Route;
import com.awardwatch.domain.RouteQuery;
import com.awardwatch.domain.Snapshot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public final class SeatsAeroSource implements AvailabilitySource {

    private final SeatsAeroClient client;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Sleeper sleeper;

    static final int MAX_ATTEMPTS = 3;
    static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Map<Program, String> PROGRAM_TO_SOURCE_SLUG = createProgramToSourceSlug();
    private static final Map<String, Program> SOURCE_SLUG_TO_PROGRAM = reverse(PROGRAM_TO_SOURCE_SLUG);
    private static final Map<Cabin, String> CABIN_TO_VENDOR_NAME = createCabinToVendorName();
    private static final Map<String, Cabin> VENDOR_NAME_TO_CABIN = reverse(CABIN_TO_VENDOR_NAME);
    private static final Comparator<AwardCandidate> PREFERRED_CANDIDATE =
        Comparator.comparingInt((AwardCandidate candidate) -> candidate.entry().mileageCost())
            .thenComparing(
                Comparator.comparingInt(
                    (AwardCandidate candidate) -> candidate.entry().seatsRemaining()
                ).reversed()
            )
            .thenComparing(AwardCandidate::vendorId);
    private static final Logger LOGGER = LoggerFactory.getLogger(SeatsAeroSource.class);

    @Autowired
    public SeatsAeroSource(
        SeatsAeroClient client,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this(client, objectMapper, clock, Thread::sleep);
    }

    SeatsAeroSource(
        SeatsAeroClient client,
        ObjectMapper objectMapper,
        Clock clock,
        Sleeper sleeper
    ) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper cannot be null");
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    record ObservedAvailability(
        SeatsAeroAvailabilityResponse availability,
        Instant observedAt
    ) {
        ObservedAvailability {
            Objects.requireNonNull(availability, "availability cannot be null");
            Objects.requireNonNull(observedAt, "observedAt cannot be null");
        }
    }

    record AwardCandidate(
        AvailabilityEntry entry,
        String vendorId
    ) {
        AwardCandidate {
            Objects.requireNonNull(entry, "entry cannot be null");
            Objects.requireNonNull(vendorId, "vendorId cannot be null");

            if (vendorId.isBlank()) {
                throw new IllegalArgumentException("vendorId cannot be blank");
            }
        }
    }

    SeatsAeroSearchRequest createInitialSearchRequest(
        RouteQuery query
    ) throws AvailabilitySourceException {
        Objects.requireNonNull(query, "query cannot be null");

        String sourceSlug = sourceSlugFor(query.program())
            .orElseThrow(() ->
                AvailabilitySourceException.permanent(
                    query,
                    "Program is not supported by Seats.aero: " + query.program()
                )
            );

        return new SeatsAeroSearchRequest(
            query.route().origin().value(),
            query.route().destination().value(),
            query.departureDates().start(),
            query.departureDates().end(),
            sourceSlug,
            0,
            true,
            null
        );
    }

    private SeatsAeroSearchResponse parseSearchResponse(
        RouteQuery query,
        String responseBody
    ) throws AvailabilitySourceException {
        if (responseBody == null) {
            throw AvailabilitySourceException.retryable(query, "Seats.aero returned an empty response body");
        }

        try {
            SeatsAeroSearchResponse response = objectMapper.readValue(
                responseBody,
                SeatsAeroSearchResponse.class
            );

            if (response == null) {
                throw AvailabilitySourceException.retryable(query, "Seats.aero returned a null search response");
            }

            return response;
        } catch (JacksonException exception) {
            throw AvailabilitySourceException.retryable(query, "Seats.aero returned an invalid search response", exception);
        }
    }

    private void validateRouteSource(
        RouteQuery query,
        SeatsAeroAvailabilityResponse availability
    ) throws AvailabilitySourceException {
        String routeSource = availability.route().source();

        if (routeSource != null && !routeSource.equalsIgnoreCase(availability.source())) {
            throw AvailabilitySourceException.retryable(
                query,
                "Seats.aero availability "
                    + availability.id()
                    + " has conflicting source slugs: "
                    + availability.source()
                    + " and "
                    + routeSource
            );
        }
    }

    private void validateRoute(
        RouteQuery query,
        SeatsAeroAvailabilityResponse availability
    ) throws AvailabilitySourceException {
        Route responseRoute;

        try {
            responseRoute = Route.of(
                availability.route().originAirport(),
                availability.route().destinationAirport()
            );
        } catch (IllegalArgumentException exception) {
            throw AvailabilitySourceException.retryable(
                query,
                "Seats.aero availability "
                    + availability.id()
                    + " contains an invalid route",
                exception
            );
        }

        if (!responseRoute.equals(query.route())) {
            throw AvailabilitySourceException.retryable(
                query,
                "Seats.aero availability "
                    + availability.id()
                    + " belongs to route "
                    + responseRoute
                    + ", expected "
                    + query.route()
            );
        }
    }

    private void validateDepartureDate(
        RouteQuery query,
        SeatsAeroAvailabilityResponse availability
    ) throws AvailabilitySourceException {
        if (!query.departureDates().contains(availability.date())) {
            throw AvailabilitySourceException.retryable(
                query,
                "Seats.aero availability "
                    + availability.id()
                    + " has departure date "
                    + availability.date()
                    + " outside "
                    + query.departureDates()
            );
        }
    }

    private void validateUpdatedAt(
        RouteQuery query,
        ObservedAvailability observation
    ) throws AvailabilitySourceException {
        SeatsAeroAvailabilityResponse availability = observation.availability();

        if (availability.updatedAt().isAfter(observation.observedAt())) {
            throw AvailabilitySourceException.retryable(
                query,
                "Seats.aero availability "
                    + availability.id()
                    + " was updated after it was observed"
            );
        }
    }

    private void addSummaryCandidates(
        RouteQuery query,
        ObservedAvailability observation,
        List<AwardCandidate> candidates
    ) throws AvailabilitySourceException {
        SeatsAeroAvailabilityResponse availability = observation.availability();

        addSummaryCabinCandidate(
            query,
            observation,
            candidates,
            Cabin.ECONOMY,
            availability.yAvailable(),
            availability.yMileageCost(),
            availability.yRemainingSeats(),
            availability.yDirect(),
            availability.yDirectMileageCost(),
            availability.yDirectRemainingSeats()
        );

        addSummaryCabinCandidate(
            query,
            observation,
            candidates,
            Cabin.PREMIUM_ECONOMY,
            availability.wAvailable(),
            availability.wMileageCost(),
            availability.wRemainingSeats(),
            availability.wDirect(),
            availability.wDirectMileageCost(),
            availability.wDirectRemainingSeats()
        );

        addSummaryCabinCandidate(
            query,
            observation,
            candidates,
            Cabin.BUSINESS,
            availability.jAvailable(),
            availability.jMileageCost(),
            availability.jRemainingSeats(),
            availability.jDirect(),
            availability.jDirectMileageCost(),
            availability.jDirectRemainingSeats()
        );

        addSummaryCabinCandidate(
            query,
            observation,
            candidates,
            Cabin.FIRST,
            availability.fAvailable(),
            availability.fMileageCost(),
            availability.fRemainingSeats(),
            availability.fDirect(),
            availability.fDirectMileageCost(),
            availability.fDirectRemainingSeats()
        );
    }

    private void addSummaryCabinCandidate(
        RouteQuery query,
        ObservedAvailability observation,
        List<AwardCandidate> candidates,
        Cabin cabin,
        Boolean available,
        String mileageCost,
        Integer seatsRemaining,
        Boolean direct,
        Integer directMileageCost,
        Integer directSeatsRemaining
    ) throws AvailabilitySourceException {
        if (!Boolean.TRUE.equals(available)) {
            return;
        }

        SeatsAeroAvailabilityResponse response = observation.availability();

        if (direct == null) {
            throw invalidCandidate(query, response.id(), "direct indicator is missing");
        }

        if (direct) {
            if (directMileageCost == null || directMileageCost <= 0) {
                throw invalidCandidate(query, response.id(), "direct mileage cost must be positive");
            }

            if (directSeatsRemaining == null) {
                throw invalidCandidate(query, response.id(), "direct seat count is missing");
            }

            candidates.add(createCandidate(
                query,
                observation,
                cabin,
                directMileageCost,
                directSeatsRemaining,
                true,
                "summary:"
                    + response.id()
                    + ":"
                    + cabin.name()
                    + ":nonstop"
            ));
        }

        if (!direct) {
            int parseMileage = parseMileageCost(query, response.id(), mileageCost);

            if (seatsRemaining == null) {
                throw invalidCandidate(query, response.id(), "seat count is missing");
            }

            candidates.add(createCandidate(
                query,
                observation,
                cabin,
                parseMileage,
                seatsRemaining,
                false,
                "summary:"
                    + response.id()
                    + ":"
                    + cabin.name()
                    + ":connecting"
            ));
        }
    }

    private int parseMileageCost(
        RouteQuery query,
        String vendorId,
        String mileageCost
    ) throws AvailabilitySourceException {
        if (mileageCost == null) {
            throw invalidCandidate(query, vendorId, "mileage cost is missing");
        }

        try {
            int parsed = Integer.parseInt(mileageCost);

            if (parsed < 0) {
                throw new NumberFormatException("negative mielage cost");
            }

            return parsed;
        } catch (NumberFormatException exception) {
            throw AvailabilitySourceException.retryable(
                query,
                "Seats.aero availability "
                    + vendorId
                    + " has an invalid mileage cost: "
                    + mileageCost,
                exception
            );
        }
    }

    private void addTripCandidates(
        RouteQuery query,
        ObservedAvailability observation,
        List<AwardCandidate> candidates
    ) throws AvailabilitySourceException {
        SeatsAeroAvailabilityResponse availability = observation.availability();

        if (availability.availabilityTrips() == null) {
            return;
        }

        for (SeatsAeroTripResponse trip : availability.availabilityTrips()) {
            Optional<Cabin> cabin = cabinForVendorName(trip.cabin());

            if (cabin.isEmpty()) {
                LOGGER.warn(
                    "Skipping Seats.aero trip {} with unknown or missing cabin: {}",
                    trip.id(),
                    trip.cabin()
                );
                continue;
            }

            validateTripIdentity(query, availability, trip);

            if (trip.mileageCost() == null) {
                throw invalidCandidate(
                    query,
                    trip.id(),
                    "mileage cost is missing"
                );
            }

            if (trip.remainingSeats() == null) {
                throw invalidCandidate(
                    query,
                    trip.id(),
                    "remaining seats is missing"
                );
            }

            int stops = resolveStops(query, trip);

            String vendorId = trip.id();

            if (vendorId == null || vendorId.isBlank()) {
                throw invalidCandidate(
                    query,
                    availability.id(),
                    "trip ID is missing"
                );
            }

            candidates.add(createCandidate(
                query,
                observation,
                cabin.get(),
                trip.mileageCost(),
                trip.remainingSeats(),
                stops == 0,
                vendorId
            ));
        }
    }

    private void validateTripIdentity(
        RouteQuery query,
        SeatsAeroAvailabilityResponse availability,
        SeatsAeroTripResponse trip
    ) throws AvailabilitySourceException {
        if (trip.availabilityId() != null && !trip.availabilityId().equals(availability.id())) {
            throw invalidCandidate(
                query,
                trip.id(),
                "trip belongs to a different availability object"
            );
        }

        if (trip.source() != null && !trip.source().equalsIgnoreCase(availability.source())) {
            throw invalidCandidate(
                query,
                trip.id(),
                "trip source does not match its availability object"
            );
        }

        Integer mixedCabinPercentage = trip.mixedCabinPercentage();

        if (mixedCabinPercentage != null && (mixedCabinPercentage <= 0 || mixedCabinPercentage > 100)) {
            throw invalidCandidate(
                query,
                trip.id(),
                "mixed-cabin percentage is outside 1-100"
            );
        }
    }

    private int resolveStops(
        RouteQuery query,
        SeatsAeroTripResponse trip
    ) throws AvailabilitySourceException {
        if (trip.stops() != null) {
            if (trip.stops() < 0) {
                throw invalidCandidate(
                    query,
                    trip.id(),
                    "trip stops is negative"
                );
            }

            return trip.stops();
        }

        if (trip.availabilitySegments() != null && !trip.availabilitySegments().isEmpty()) {
            return trip.availabilitySegments().size() - 1;
        }

        if (trip.connections() != null) {
            return trip.connections().size();
        }

        throw invalidCandidate(
            query,
            trip.id(),
            "stop count cannot be determined"
        );
    }

    private AwardCandidate createCandidate(
        RouteQuery query,
        ObservedAvailability observation,
        Cabin cabin,
        int mileageCost,
        int seatsRemaining,
        boolean nonstop,
        String vendorId
    ) throws AvailabilitySourceException {
        SeatsAeroAvailabilityResponse availability = observation.availability();

        try {
            AvailabilityEntry entry = new AvailabilityEntry(
                query.route(),
                availability.date(),
                query.program(),
                cabin,
                mileageCost,
                seatsRemaining,
                nonstop,
                observation.observedAt(),
                availability.updatedAt()
            );

            return new AwardCandidate(entry, vendorId);
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw AvailabilitySourceException.retryable(
                query,
                "Seats.aero candidate "
                    + vendorId
                    + " contains invalid award data",
                exception
            );
        }
    }

    private AvailabilitySourceException invalidCandidate(
        RouteQuery query,
        String vendorId,
        String reason
    ) {
        return AvailabilitySourceException.retryable(
            query,
            "Seats.aero candidate "
                + vendorId
                + " : "
                + reason
        );
    }

    List<AwardCandidate> fetchCandidates(
        RouteQuery query
    ) throws AvailabilitySourceException {
        return mapCandidates(
            query,
            fetchValidatedAvailabilities(query)
        );
    }

    List<AvailabilityEntry> reconcileCandidates(
        List<AwardCandidate> candidates
    ) {
        Objects.requireNonNull(candidates, "candidates cannot be null");

        Map<AvailabilityEntry.AwardKey, AwardCandidate> preferredByAward =
            new LinkedHashMap<>();

        for (AwardCandidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidates cannot contain null");

            preferredByAward.merge(
                candidate.entry().awardKey(),
                candidate,
                (current, replacement) ->
                    PREFERRED_CANDIDATE.compare(current, replacement) <= 0
                        ? current
                        : replacement
            );
        }

        return preferredByAward.values().stream()
            .map(AwardCandidate::entry)
            .toList();
    }

    @Override
    public Snapshot fetch(RouteQuery query) throws AvailabilitySourceException {
        Objects.requireNonNull(query, "query cannot be null");

        List<AvailabilityEntry> entries = reconcileCandidates(fetchCandidates(query));
        Instant fetchedAt = clock.instant();

        try {
            return new Snapshot(query, fetchedAt, entries);
        } catch (IllegalArgumentException exception) {
            throw AvailabilitySourceException.retryable(
                query,
                "Seats.aero produced an invalid snapshot",
                exception
            );
        }
    }

    List<ObservedAvailability> fetchValidatedAvailabilities(
        RouteQuery query
    ) throws AvailabilitySourceException {
        return validateAvailabilities(query, fetchAllPages(query));
    }

    List<ObservedAvailability> fetchAllPages(
        RouteQuery query
    ) throws AvailabilitySourceException {
        SeatsAeroSearchRequest request = createInitialSearchRequest(query);

        Map<String, ObservedAvailability> availabilityById = new LinkedHashMap<>();

        Long paginationCursor = null;

        while (true) {
            String responseBody = search(query, request);
            Instant observedAt = clock.instant();

            SeatsAeroSearchResponse response = parseSearchResponse(query, responseBody);

            if (request.skip() == 0) {
                paginationCursor = response.cursor();
            }

            for (SeatsAeroAvailabilityResponse availability : response.data()) {
                availabilityById.putIfAbsent(
                    availability.id(),
                    new ObservedAvailability(
                        availability,
                        observedAt
                    )
                );
            }

            if (!response.hasMore()) {
                break;
            }

            int nextSkip;

            try {
                nextSkip = Math.addExact(
                    request.skip(),
                    response.data().size()
                );
            } catch (ArithmeticException exception) {
                throw AvailabilitySourceException.retryable(query, "Seats.aero pagination offset overflowed", exception);
            }

            request = new SeatsAeroSearchRequest(
                request.originAirport(),
                request.destinationAirport(),
                request.startDate(),
                request.endDate(),
                request.sourceSlug(),
                nextSkip,
                request.includeTrips(),
                paginationCursor
            );
        }

        return List.copyOf(availabilityById.values());
    }

    private String search(
        RouteQuery query,
        SeatsAeroSearchRequest request
    ) throws AvailabilitySourceException {
        Duration retryDelay = INITIAL_RETRY_DELAY;
        int attempt = 1;

        while (true) {
            try {
                return client.search(request);
            } catch (RestClientResponseException exception) {
                if (isRetryableHttpStatus(exception) && attempt < MAX_ATTEMPTS) {
                    waitBeforeRetry(query, exception, attempt, retryDelay);
                    retryDelay = retryDelay.multipliedBy(2);
                    attempt++;
                    continue;
                }

                throw mapResponseFailure(query, exception);
            } catch (RestClientException exception) {
                throw AvailabilitySourceException.retryable(
                    query,
                    "Seats.aero request failed before a response was received",
                    exception
                );
            }
        }
    }

    private boolean isRetryableHttpStatus(RestClientResponseException exception) {
        return exception.getStatusCode().value() == 429
            || exception.getStatusCode().is5xxServerError();
    }

    private void waitBeforeRetry(
        RouteQuery query,
        RestClientResponseException responseFailure,
        int attempt,
        Duration retryDelay
    ) throws AvailabilitySourceException {
        LOGGER.warn(
            "Seats.aero returned HTTP {}; retrying after {} (attempt {} of {})",
            responseFailure.getStatusCode().value(),
            retryDelay,
            attempt + 1,
            MAX_ATTEMPTS
        );

        try {
            sleeper.sleep(retryDelay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw AvailabilitySourceException.retryable(
                query,
                "Interrupted while waiting to retry Seats.aero",
                exception
            );
        }
    }

    private AvailabilitySourceException mapResponseFailure(
        RouteQuery query,
        RestClientResponseException exception
    ) {
        int statusCode = exception.getStatusCode().value();

        if (statusCode == 401) {
            return AvailabilitySourceException.permanent(
                query,
                "Seats.aero rejected the API credentials (HTTP 401)",
                exception
            );
        }

        if (statusCode == 429) {
            return AvailabilitySourceException.retryable(
                query,
                "Seats.aero rate limit exceeded (HTTP 429)",
                exception
            );
        }

        if (exception.getStatusCode().is5xxServerError()) {
            return AvailabilitySourceException.retryable(
                query,
                "Seats.aero server error (HTTP " + statusCode + ")",
                exception
            );
        }

        return AvailabilitySourceException.permanent(
            query,
            "Seats.aero rejected the request (HTTP " + statusCode + ")",
            exception
        );
    }

    List<ObservedAvailability> validateAvailabilities(
        RouteQuery query,
        List<ObservedAvailability> observations
    ) throws AvailabilitySourceException {
        Objects.requireNonNull(query, "query cannot be null");
        Objects.requireNonNull(observations, "observations cannot be null");

        List<ObservedAvailability> validated = new ArrayList<>();

        for (ObservedAvailability observation : observations) {
            SeatsAeroAvailabilityResponse availability = observation.availability();

            Optional<Program> responseProgram = programForSourceSlug(availability.source());

            if (responseProgram.isEmpty()) {
                LOGGER.warn(
                    "Skipping Seats.aero availability {} with unknown source slug: {}",
                    availability.id(),
                    availability.source()
                );
                continue;
            }

            if (responseProgram.get() != query.program()) {
                throw AvailabilitySourceException.retryable(
                    query,
                    "Seats.aero availability "
                        + availability.id()
                        + " belongs to program "
                        + responseProgram.get()
                        + ", expected "
                        + query.program()
                );
            }

            validateRouteSource(query, availability);
            validateRoute(query, availability);
            validateDepartureDate(query, availability);
            validateUpdatedAt(query, observation);

            validated.add(observation);
        }

        return List.copyOf(validated);
    }

    List<AwardCandidate> mapCandidates(
        RouteQuery query,
        List<ObservedAvailability> observations
    ) throws AvailabilitySourceException {
        Objects.requireNonNull(query, "query cannot be null");
        Objects.requireNonNull(observations, "observations cannot be null");

        List<AwardCandidate> candidates = new ArrayList<>();

        for (ObservedAvailability observation : observations) {
            addSummaryCandidates(query, observation, candidates);
            addTripCandidates(query, observation, candidates);
        }

        return List.copyOf(candidates);
    }

    private static  Map<Program, String> createProgramToSourceSlug() {
        EnumMap<Program, String> mappings = new EnumMap<>(Program.class);

        mappings.put(Program.EUROBONUS, "eurobonus");
        mappings.put(Program.FLYING_CLUB, "virginatlantic");
        mappings.put(Program.CLUB_PREMIER, "aeromexico");
        mappings.put(Program.AADVANTAGE, "american");
        mappings.put(Program.SKYMILES, "delta");
        mappings.put(Program.ETIHAD_GUEST, "etihad");
        mappings.put(Program.UNITED_MILEAGEPLUS, "united");
        mappings.put(Program.SKYWARDS, "emirates");
        mappings.put(Program.AEROPLAN, "aeroplan");
        mappings.put(Program.ALASKA_MILEAGE_PLAN, "alaska");
        mappings.put(Program.VELOCITY, "velocity");
        mappings.put(Program.QANTAS_FREQUENT_FLYER, "qantas");
        mappings.put(Program.CONNECTMILES, "connectmiles");
        mappings.put(Program.TUDOAZUL, "azul");
        mappings.put(Program.GOL_SMILES, "smiles");
        mappings.put(Program.FLYING_BLUE, "flyingblue");
        mappings.put(Program.TRUEBLUE, "jetblue");
        mappings.put(Program.PRIVILEGE_CLUB, "qatar");
        mappings.put(Program.MILES_AND_SMILES, "turkish");
        mappings.put(Program.KRISFLYER, "singapore");
        mappings.put(Program.SHEBAMILES, "ethiopian");
        mappings.put(Program.ALFURSAN, "saudia");
        mappings.put(Program.FINNAIR_PLUS, "finnair");
        mappings.put(Program.MILES_AND_MORE, "lufthansa");
        mappings.put(Program.FRONTIER_MILES, "frontier");
        mappings.put(Program.FREE_SPIRIT, "spirit");

        return Collections.unmodifiableMap(mappings);
    }

    private static Map<Cabin, String> createCabinToVendorName() {
        EnumMap<Cabin, String> mappings = new EnumMap<>(Cabin.class);

        mappings.put(Cabin.ECONOMY, "economy");
        mappings.put(Cabin.PREMIUM_ECONOMY, "premium");
        mappings.put(Cabin.BUSINESS, "business");
        mappings.put(Cabin.FIRST, "first");

        return Collections.unmodifiableMap(mappings);
    }

    private static <T> Map<String, T> reverse(Map<T, String> forward) {
        Map<String, T> reverse = new HashMap<>();

        forward.forEach((domainValue, vendorValue) -> {
            T previous = reverse.put(vendorValue, domainValue);

            if (previous != null) {
                throw new IllegalStateException("Duplicate Seats.aero identifier: " + vendorValue);
            }
        });

        return Map.copyOf(reverse);
    }

    @Override
    public boolean supports(Program program) {
        Objects.requireNonNull(program, "program cannot be null");

        return PROGRAM_TO_SOURCE_SLUG.containsKey(program);
    }

    static Optional<String> sourceSlugFor(Program program) {
        Objects.requireNonNull(program, "program cannot be null");

        return Optional.ofNullable(PROGRAM_TO_SOURCE_SLUG.get(program));
    }

    static Optional<Program> programForSourceSlug(String sourceSlug) {
        if (sourceSlug == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(SOURCE_SLUG_TO_PROGRAM.get(sourceSlug.toLowerCase(Locale.ROOT)));
    }

    static Optional<String> vendorNameFor(Cabin cabin) {
        Objects.requireNonNull(cabin, "cabin cannot be null");

        return Optional.ofNullable(CABIN_TO_VENDOR_NAME.get(cabin));
    }

    static Optional<Cabin> cabinForVendorName(String vendorName) {
        if (vendorName == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(VENDOR_NAME_TO_CABIN.get(vendorName.toLowerCase(Locale.ROOT)));
    }
}
