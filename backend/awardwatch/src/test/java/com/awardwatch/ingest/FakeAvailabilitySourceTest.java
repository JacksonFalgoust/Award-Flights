package com.awardwatch.ingest;

import com.awardwatch.domain.Cabin;
import com.awardwatch.domain.DateRange;
import com.awardwatch.domain.Program;
import com.awardwatch.domain.Route;
import com.awardwatch.domain.RouteQuery;
import com.awardwatch.domain.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


public class FakeAvailabilitySourceTest {
    
    private static final Instant FETCHED_AT = Instant.parse("2026-08-01T12:00:00Z");
    private static final Route ATL_NRT = Route.of("ATL", "NRT");
    private static final LocalDate AUGUST_9 = LocalDate.of(2026, 8, 9);
    private static final LocalDate AUGUST_10 = LocalDate.of(2026, 8, 10);
    private FakeAvailabilitySource source() {
        var key = new FakeAvailabilitySource.CatalogKey(ATL_NRT, Program.AADVANTAGE);

        var awards = List.of(
            new FakeAvailabilitySource.AwardTemplate(
                AUGUST_9,
                Cabin.BUSINESS,
                true,
                60_000,
                2,
                Duration.ofMinutes(30)
            ),
            new FakeAvailabilitySource.AwardTemplate(
                AUGUST_9,
                Cabin.BUSINESS,
                false,
                50_000,
                4,
                null
            ),
            new FakeAvailabilitySource.AwardTemplate(
                AUGUST_10,
                Cabin.FIRST,
                true,
                80_000,
                1,
                Duration.ofHours(2)
            )
        );

        return new FakeAvailabilitySource(
            Set.of(Program.AADVANTAGE, Program.AEROPLAN),
            Map.of(key, awards),
            Clock.fixed(FETCHED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void configuredRouteReturnsCannedAwards() throws Exception {
        RouteQuery query = new RouteQuery(
            ATL_NRT, 
            new DateRange(AUGUST_9, AUGUST_10), 
            Program.AADVANTAGE
        );

        Snapshot snapshot = source().fetch(query);

        assertThat(snapshot.query()).isSameAs(query);
        assertThat(snapshot.fetchedAt()).isEqualTo(FETCHED_AT);
        assertThat(snapshot.entries()).hasSize(3);
    }

    @Test 
    void unconfiguredRouteReturnsEmptySnapshot() throws Exception {
        RouteQuery query = new RouteQuery(
            Route.of("JFK", "LHR"),
            DateRange.single(AUGUST_9), 
            Program.AADVANTAGE
        );

        Snapshot snapshot = source().fetch(query);

        assertThat(snapshot.query()).isSameAs(query);
        assertThat(snapshot.entries()).isEmpty();
    }

    @Test 
    void unsupportedProgramIsPermanentFailure() {
        RouteQuery query = new RouteQuery(
            ATL_NRT,
            DateRange.single(AUGUST_9),
            Program.SKYMILES
        );

        assertThatThrownBy(() -> source().fetch(query))
            .isInstanceOfSatisfying(
                com.awardwatch.domain
                    .AvailabilitySourceException.class,
                exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.query()).isSameAs(query);
                }
            );
    }

    @Test 
    void entriesOutsideRequestedRangeAreExcluded() throws Exception {
        RouteQuery query = new RouteQuery(
            ATL_NRT,
            DateRange.single(AUGUST_9),
            Program.AADVANTAGE
        );

        Snapshot snapshot = source().fetch(query);

        assertThat(snapshot.entries()).hasSize(2);
        assertThat(snapshot.entries())
            .allMatch(entry ->
                entry.departureDate().equals(AUGUST_9)
            );
    }

    @Test 
    void supportIsReportedFromConfiguredSet() {
        FakeAvailabilitySource source = source();

        assertThat(source.supports(Program.AADVANTAGE)).isTrue();
        assertThat(source.supports(Program.AEROPLAN)).isTrue();
        assertThat(source.supports(Program.SKYMILES)).isFalse();
    }
}
