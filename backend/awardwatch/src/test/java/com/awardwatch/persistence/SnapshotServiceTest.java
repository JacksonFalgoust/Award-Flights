package com.awardwatch.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.awardwatch.domain.AvailabilityEntry;
import com.awardwatch.domain.Cabin;
import com.awardwatch.domain.DateRange;
import com.awardwatch.domain.Program;
import com.awardwatch.domain.Route;
import com.awardwatch.domain.RouteQuery;
import com.awardwatch.domain.Snapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SnapshotServiceTest {

    private static final Route ROUTE = Route.of("ATL", "NRT");
    private static final DateRange DATES = new DateRange(
        LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 10, 7)
    );
    private static final RouteQuery QUERY = new RouteQuery(
        ROUTE,
        DATES,
        Program.AADVANTAGE
    );
    private static final Instant FETCHED_AT = Instant.parse("2026-09-21T18:00:10Z");

    private SnapshotRepository repository;
    private SnapshotService service;

    @BeforeEach
    void setUp() {
        repository = mock(SnapshotRepository.class);
        service = new SnapshotService(repository);
    }

    @Test
    void recordsSuccessfulSnapshotAndEntries() {
        Instant firstObservedAt = Instant.parse("2026-09-21T18:00:01Z");
        Instant secondObservedAt = Instant.parse("2026-09-21T18:00:07Z");
        Snapshot snapshot = new Snapshot(
            QUERY,
            FETCHED_AT,
            List.of(
                entry(LocalDate.of(2026, 10, 2), Cabin.BUSINESS, 60_000, 2, true, firstObservedAt),
                entry(LocalDate.of(2026, 10, 3), Cabin.FIRST, 90_000, null, false, secondObservedAt)
            )
        );
        SnapshotEntity persisted = mock(SnapshotEntity.class);
        when(persisted.getId()).thenReturn(41L);
        when(repository.save(any(SnapshotEntity.class))).thenReturn(persisted);

        long id = service.record(snapshot, 3, " seats.aero ");

        assertThat(id).isEqualTo(41L);
        ArgumentCaptor<SnapshotEntity> captor = ArgumentCaptor.forClass(SnapshotEntity.class);
        verify(repository).save(captor.capture());
        SnapshotEntity saved = captor.getValue();
        assertThat(saved.getOrigin()).isEqualTo("ATL");
        assertThat(saved.getDestination()).isEqualTo("NRT");
        assertThat(saved.getDateFrom()).isEqualTo(DATES.start());
        assertThat(saved.getDateTo()).isEqualTo(DATES.end());
        assertThat(saved.getProgram()).isEqualTo(Program.AADVANTAGE);
        assertThat(saved.getObservedAt()).isEqualTo(FETCHED_AT);
        assertThat(saved.getApiCallsUsed()).isEqualTo(3);
        assertThat(saved.isSucceeded()).isTrue();
        assertThat(saved.getSource()).isEqualTo("seats.aero");
        assertThat(saved.getEntries()).hasSize(2);
        assertThat(saved.getEntries().get(0).getSnapshot()).isSameAs(saved);
        assertThat(saved.getEntries().get(0).getObservedAt()).isEqualTo(firstObservedAt);
        assertThat(saved.getEntries().get(1).getObservedAt()).isEqualTo(secondObservedAt);
        assertThat(saved.getEntries().get(1).getSeatsRemaining()).isNull();
    }

    @Test
    void recordsSuccessfulEmptySnapshot() {
        Snapshot snapshot = new Snapshot(QUERY, FETCHED_AT, List.of());
        SnapshotEntity persisted = mock(SnapshotEntity.class);
        when(persisted.getId()).thenReturn(42L);
        when(repository.save(any(SnapshotEntity.class))).thenReturn(persisted);

        assertThat(service.record(snapshot, 1, "seats.aero")).isEqualTo(42L);

        ArgumentCaptor<SnapshotEntity> captor = ArgumentCaptor.forClass(SnapshotEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isSucceeded()).isTrue();
        assertThat(captor.getValue().getEntries()).isEmpty();
    }

    @Test
    void recordsFailureWithoutManufacturingEntries() {
        SnapshotEntity persisted = mock(SnapshotEntity.class);
        when(persisted.getId()).thenReturn(43L);
        when(repository.save(any(SnapshotEntity.class))).thenReturn(persisted);

        long id = service.recordFailure(QUERY, FETCHED_AT, 3, "seats.aero");

        assertThat(id).isEqualTo(43L);
        ArgumentCaptor<SnapshotEntity> captor = ArgumentCaptor.forClass(SnapshotEntity.class);
        verify(repository).save(captor.capture());
        SnapshotEntity saved = captor.getValue();
        assertThat(saved.isSucceeded()).isFalse();
        assertThat(saved.getObservedAt()).isEqualTo(FETCHED_AT);
        assertThat(saved.getEntries()).isEmpty();
    }

    @Test
    void rejectsBookkeepingThatCannotFitTheSchema() {
        Snapshot snapshot = new Snapshot(QUERY, FETCHED_AT, List.of());

        assertThatThrownBy(() -> service.record(snapshot, -1, "seats.aero"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("apiCallsUsed");
        assertThatThrownBy(() -> service.record(snapshot, Short.MAX_VALUE + 1, "seats.aero"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("apiCallsUsed");
        assertThatThrownBy(() -> service.record(snapshot, 1, " "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("source cannot be blank");
        verifyNoInteractions(repository);
    }

    private AvailabilityEntry entry(
        LocalDate departureDate,
        Cabin cabin,
        int mileageCost,
        Integer seatsRemaining,
        boolean nonstop,
        Instant observedAt
    ) {
        return new AvailabilityEntry(
            ROUTE,
            departureDate,
            Program.AADVANTAGE,
            cabin,
            mileageCost,
            seatsRemaining,
            nonstop,
            observedAt,
            observedAt.minusSeconds(600)
        );
    }
}
