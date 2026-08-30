package com.awardwatch.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class SnapshotTest {

    private static final Route ATL_NRT = Route.of("ATL", "NRT");
    private static final LocalDate FIRST = LocalDate.of(2026, 8, 9);
    private static final LocalDate LAST = LocalDate.of(2026, 8, 19);
    private static final DateRange WINDOW = new DateRange(FIRST, LAST);
    private static final RouteQuery QUERY = new RouteQuery(ATL_NRT, WINDOW, Program.AADVANTAGE);
    private static final Instant FETCHED = Instant.parse("2026-08-01T12:00:00Z");
    private static final Instant OBSERVED = FETCHED.minusSeconds(30);

    private static AvailabilityEntry entry(Route route, LocalDate departureDate, Program program, Cabin cabin, boolean nonstop, int mileageCost, Instant observedAt) {
        return new AvailabilityEntry(route, departureDate, program, cabin, mileageCost, 2, nonstop, observedAt, null);
    }

    private static AvailabilityEntry entry(LocalDate departureDate, Cabin cabin, boolean nonstop) {
        return entry(ATL_NRT, departureDate, Program.AADVANTAGE, cabin, nonstop, 60000, OBSERVED);
    }

    @Test
    void nullQueryRejected() {
        assertThatThrownBy(() -> new Snapshot(null, FETCHED, List.of())).isInstanceOf(NullPointerException.class).hasMessage("query cannot be null");
    }

    @Test
    void nullFetchedAtRejected() {
        assertThatThrownBy(() -> new Snapshot(QUERY, null, List.of())).isInstanceOf(NullPointerException.class).hasMessage("fetchedAt cannot be null");
    }

    @Test
    void nullEntriesRejected() {
        assertThatThrownBy(() -> new Snapshot(QUERY, FETCHED, null)).isInstanceOf(NullPointerException.class).hasMessage("entries cannot be null");
    }

    @Test
    void nullEntryInListRejected() {
        assertThatThrownBy(() -> new Snapshot(QUERY, FETCHED, Arrays.asList(entry(FIRST, Cabin.BUSINESS, true), null))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void emptyEntriesAccepted() {
        Snapshot snapshot = new Snapshot(QUERY, FETCHED, List.of());
        assertThat(snapshot.entries()).isEmpty();
    }

    @Test
    void queryIsCarriedUnchanged() {
        Snapshot snapshot = new Snapshot(QUERY, FETCHED, List.of());
        assertThat(snapshot.query()).isEqualTo(QUERY);
    }

    @Test
    void entryOnDifferentRouteRejected() {
        AvailabilityEntry foreign = entry(Route.of("LHR", "JFK"), FIRST, Program.AADVANTAGE, Cabin.BUSINESS, true, 60000, OBSERVED);
        assertThatThrownBy(() -> new Snapshot(QUERY, FETCHED, List.of(foreign))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match query route");
    }

    @Test
    void entryOnReversedRouteRejected() {
        AvailabilityEntry reversed = entry(Route.of("NRT", "ATL"), FIRST, Program.AADVANTAGE, Cabin.BUSINESS, true, 60000, OBSERVED);
        assertThatThrownBy(() -> new Snapshot(QUERY, FETCHED, List.of(reversed))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match query route");
    }

    @Test
    void entryOnDifferentProgramRejected() {
        AvailabilityEntry foreign = entry(ATL_NRT, FIRST, Program.AEROPLAN, Cabin.BUSINESS, true, 60000, OBSERVED);
        assertThatThrownBy(() -> new Snapshot(QUERY, FETCHED, List.of(foreign))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match query program");
    }

    @Test
    void entryBeforeRangeRejected() {
        AvailabilityEntry early = entry(FIRST.minusDays(1), Cabin.BUSINESS, true);
        assertThatThrownBy(() -> new Snapshot(QUERY, FETCHED, List.of(early))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is outside");
    }

    @Test
    void entryAfterRangeRejected() {
        AvailabilityEntry late = entry(LAST.plusDays(1), Cabin.BUSINESS, true);
        assertThatThrownBy(() -> new Snapshot(QUERY, FETCHED, List.of(late))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is outside");
    }

    @Test
    void entriesOnRangeBoundariesAccepted() {
        Snapshot snapshot = new Snapshot(QUERY, FETCHED, List.of(entry(FIRST, Cabin.BUSINESS, true), entry(LAST, Cabin.BUSINESS, true)));
        assertThat(snapshot.entries()).hasSize(2);
    }

    @Test
    void observedAfterFetchedRejected() {
        AvailabilityEntry future = entry(ATL_NRT, FIRST, Program.AADVANTAGE, Cabin.BUSINESS, true, 60000, FETCHED.plusSeconds(1));
        assertThatThrownBy(() -> new Snapshot(QUERY, FETCHED, List.of(future))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be before entry observedAt");
    }

    @Test
    void observedEqualToFetchedAccepted() {
        AvailabilityEntry simultaneous = entry(ATL_NRT, FIRST, Program.AADVANTAGE, Cabin.BUSINESS, true, 60000, FETCHED);
        assertThatCode(() -> new Snapshot(QUERY, FETCHED, List.of(simultaneous))).doesNotThrowAnyException();
    }

    @Test
    void duplicateAwardKeyRejected() {
        AvailabilityEntry cheap = entry(ATL_NRT, FIRST, Program.AADVANTAGE, Cabin.BUSINESS, true, 60000, OBSERVED);
        AvailabilityEntry expensive = entry(ATL_NRT, FIRST, Program.AADVANTAGE, Cabin.BUSINESS, true, 90000, OBSERVED);

        assertThatThrownBy(() -> new Snapshot(QUERY, FETCHED, List.of(cheap, expensive))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate award in snapshot");
    }

    @Test
    void entriesDifferingByCabinAccepted() {
        Snapshot snapshot = new Snapshot(QUERY, FETCHED, List.of(entry(FIRST, Cabin.BUSINESS, true), entry(FIRST, Cabin.FIRST, true)));
        assertThat(snapshot.entries()).hasSize(2);
    }

    @Test
    void entriesDifferingByNonstopAccepted() {
        Snapshot snapshot = new Snapshot(QUERY, FETCHED, List.of(entry(FIRST, Cabin.BUSINESS, true), entry(FIRST, Cabin.BUSINESS, false)));
        assertThat(snapshot.entries()).hasSize(2);
    }

    @Test
    void entriesDifferingByDateAccepted() {
        Snapshot snapshot = new Snapshot(QUERY, FETCHED, List.of(entry(FIRST, Cabin.BUSINESS, true), entry(FIRST.plusDays(1), Cabin.BUSINESS, true)));
        assertThat(snapshot.entries()).hasSize(2);
    }

    @Test
    void entriesAreUnmodifiable() {
        Snapshot snapshot = new Snapshot(QUERY, FETCHED, List.of(entry(FIRST, Cabin.BUSINESS, true)));
        assertThatThrownBy(() -> snapshot.entries().add(entry(LAST, Cabin.BUSINESS, true))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mutatingCallerListDoesNotAffectSnapshot() {
        List<AvailabilityEntry> mutable = new ArrayList<>(List.of(entry(FIRST, Cabin.BUSINESS, true)));
        Snapshot snapshot = new Snapshot(QUERY, FETCHED, mutable);

        mutable.add(entry(LAST, Cabin.BUSINESS, true));
        mutable.clear();

        assertThat(snapshot.entries()).hasSize(1);
    }

    @Test
    void equalSnapshotsAreEqual() {
        Snapshot first = new Snapshot(QUERY, FETCHED, List.of(entry(FIRST, Cabin.BUSINESS, true)));
        Snapshot second = new Snapshot(QUERY, FETCHED, List.of(entry(FIRST, Cabin.BUSINESS, true)));

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }
}
