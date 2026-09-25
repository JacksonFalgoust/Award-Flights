package com.awardwatch.alerting;

import com.awardwatch.domain.Cabin;
import com.awardwatch.domain.Program;
import com.awardwatch.persistence.AvailabilityEntryEntity;
import com.awardwatch.persistence.SnapshotEntity;
import com.awardwatch.persistence.SnapshotRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.awardwatch.alerting.AvailabilityChange.Type.*;

class SnapshotDiffServiceTest {
    private final SnapshotRepository repository = mock(SnapshotRepository.class);
    private final SnapshotDiffService service = new SnapshotDiffService(repository);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private SnapshotEntity reading(long id, boolean succeeded, Integer miles) {
        var entity = new SnapshotEntity("ATL", "NRT", DAY, DAY, Program.AADVANTAGE,
            Instant.parse("2026-09-24T12:00:00Z").plusSeconds(id), 1, succeeded, "seats.aero");
        ReflectionTestUtils.setField(entity, "id", id);
        if (miles != null) entity.addEntry(award(entity, miles));
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        return entity;
    }

    private AvailabilityEntryEntity award(SnapshotEntity entity, int miles) {
        return new AvailabilityEntryEntity(entity, DAY, Cabin.BUSINESS, miles, 2, true,
            entity.getObservedAt(), null);
    }

    private void latest(SnapshotEntity... readings) {
        when(repository.findTop2ByOriginAndDestinationAndProgramAndSucceededTrueOrderByObservedAtDescIdDesc(
            "ATL", "NRT", Program.AADVANTAGE)).thenReturn(List.of(readings));
    }

    @Test
    void failureDoesNotDiffOrReplayLastSuccessfulPair() {
        reading(3, false, null);
        assertThat(service.diff(3)).isEmpty();
        verify(repository).findById(3L);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void recoverySkipsFailedEmptyReadingWithoutGoneOrNew() {
        var before = reading(1, true, 60000);
        reading(2, false, null);
        var recovered = reading(3, true, 60000);
        latest(recovered, before);
        assertThat(service.diff(2)).isEmpty();
        assertThat(service.diff(3)).isEmpty();
    }

    @Test
    void recoveryStillReportsRealImprovements() {
        var before = reading(1, true, 60000);
        reading(2, false, null);
        var recovered = reading(3, true, 50000);
        latest(recovered, before);
        assertThat(service.diff(3)).extracting(AvailabilityChange::type).containsExactly(CHEAPER);
    }

    @Test
    void firstSuccessAfterFailuresOnlyEstablishesBaseline() {
        reading(1, false, null);
        var current = reading(2, true, 60000);
        latest(current);
        assertThat(service.diff(2)).isEmpty();
    }

    @Test
    void genuinelyEmptySuccessProducesGoneAndLaterNew() {
        var before = reading(1, true, 60000);
        var empty = reading(2, true, null);
        latest(empty, before);
        assertThat(service.diff(2)).extracting(AvailabilityChange::type).containsExactly(GONE);
        var recovered = reading(3, true, 60000);
        latest(recovered, empty);
        assertThat(service.diff(3)).extracting(AvailabilityChange::type).containsExactly(NEW);
    }

    @Test
    void staleTriggerDoesNotReplayNewerChanges() {
        var before = reading(1, true, 60000);
        var current = reading(2, true, 50000);
        latest(current, before);
        assertThat(service.diff(1)).isEmpty();
    }

    @Test
    void failedRowsCannotBeConvertedEvenIfReturnedByBaselineQuery() {
        var failed = reading(1, false, null);
        var current = reading(2, true, 60000);
        latest(current, failed);
        assertThatThrownBy(() -> service.diff(2)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("failed snapshots");
    }

    @Test
    void failedRowsCannotCarryInventory() {
        var failed = reading(1, false, null);
        assertThatThrownBy(() -> failed.addEntry(award(failed, 60000)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownIdIsRejected() {
        assertThatThrownBy(() -> service.diff(99)).isInstanceOf(IllegalArgumentException.class);
    }
}
