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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
public final class FakeAvailabilitySource implements AvailabilitySource{
    
    private final Set<Program> supportedPrograms;
    private final Map<CatalogKey, List<AwardTemplate>> catalog;
    private final Clock clock;

    public FakeAvailabilitySource(
        Set<Program> supportedPrograms, 
        Map<CatalogKey, List<AwardTemplate>> catalog, 
        Clock clock
    ) {
        this.supportedPrograms = Set.copyOf(
            Objects.requireNonNull(supportedPrograms)
        );
        this.clock = Objects.requireNonNull(clock);
        this.catalog = Objects.requireNonNull(catalog).entrySet().stream().collect(Collectors.toUnmodifiableMap(
            Map.Entry::getKey,
            entry -> List.copyOf(entry.getValue())
        ));
    }

    @Override
    public boolean supports(Program program) {
        Objects.requireNonNull(program, "program cannot be null");
        return supportedPrograms.contains(program);
    }

    @Override
    public Snapshot fetch(RouteQuery query) throws AvailabilitySourceException {
        Objects.requireNonNull(query, "query cannot be null");

        if (!supports(query.program())) {
            throw AvailabilitySourceException.permanent(query, "Program is not supported by this source: " + query.program());
        }

        Instant fetchedAt = clock.instant();

        CatalogKey key = new CatalogKey(query.route(), query.program());

        List<AvailabilityEntry> entries = catalog
            .getOrDefault(key, List.of())
            .stream()
            .filter(template ->
                    query.departureDates()
                        .contains(template.departureDate())
            ).map(template ->
                template.toEntry(query, fetchedAt)
            ).toList();

        return new Snapshot(query, fetchedAt, entries);
    }

    public record CatalogKey(Route route, Program program) {

        public CatalogKey {
            Objects.requireNonNull(route, "route cannot be null");
            Objects.requireNonNull(program, "program cannot be null");
        }
    }

    public record AwardTemplate(
        LocalDate departureDate,
        Cabin cabin,
        boolean nonstop,
        int mileageCost,
        int seatsRemaining,
        Duration sourceAge
    ) {
        public AwardTemplate {
            Objects.requireNonNull(departureDate, "departureDate cannot be null");
            Objects.requireNonNull(cabin, "cabin cannot be null");

            if (mileageCost < 0) {
                throw new IllegalArgumentException("mileageCost cannot be negative");
            }

            if (seatsRemaining < 0) {
                throw new IllegalArgumentException("seatsRemaining cannot be negative");
            }

            if (sourceAge != null && sourceAge.isNegative()) {
                throw new IllegalArgumentException("sourceAge cannot be negative");
            }
        }

        private AvailabilityEntry toEntry(RouteQuery query, Instant observedAt) {
            Instant refreshedAt = sourceAge == null ? null : observedAt.minus(sourceAge);

            return new AvailabilityEntry(
                query.route(),
                departureDate,
                query.program(),
                cabin,
                mileageCost,
                seatsRemaining,
                nonstop,
                observedAt,
                refreshedAt
            );
        }
    }
}
