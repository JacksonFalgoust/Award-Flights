package com.awardwatch.alerting;

import com.awardwatch.domain.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static com.awardwatch.alerting.AvailabilityChange.Type.*;
import static org.assertj.core.api.Assertions.*;

class DiffEngineTest {
    private static final Route ROUTE = Route.of("ATL", "NRT");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final Instant TIME = Instant.parse("2026-09-23T12:00:00Z");
    private final DiffEngine engine = new DiffEngine();

    private AvailabilityEntry entry(int day, Cabin cabin, boolean nonstop, int miles, Integer seats) {
        return new AvailabilityEntry(ROUTE, DAY.plusDays(day), Program.AADVANTAGE,
                cabin, miles, seats, nonstop, TIME, null);
    }

    private AvailabilityEntry entry(int miles, Integer seats) {
        return entry(0, Cabin.BUSINESS, true, miles, seats);
    }

    private Snapshot snapshot(int from, int to, AvailabilityEntry... entries) {
        return new Snapshot(new RouteQuery(ROUTE, new DateRange(DAY.plusDays(from), DAY.plusDays(to)),
                Program.AADVANTAGE), TIME, List.of(entries));
    }

    @Test
    void emptyToPopulatedAndBack() {
        var award = entry(60000, 2);
        var empty = snapshot(0, 2);
        var populated = snapshot(0, 2, award);
        assertThat(engine.diff(empty, populated)).containsExactly(new AvailabilityChange(NEW, null, award));
        assertThat(engine.diff(populated, empty)).containsExactly(new AvailabilityChange(GONE, award, null));
        assertThat(engine.diff(empty, empty)).isEmpty();
    }

    @Test
    void emitsBothImprovementsWithTheirObservations() {
        var before = entry(60000, 1);
        var after = entry(50000, 3);
        assertThat(engine.diff(snapshot(0, 0, before), snapshot(0, 0, after))).containsExactly(
                new AvailabilityChange(CHEAPER, before, after), new AvailabilityChange(MORE_SEATS, before, after));
    }

    @Test
    void thresholdIsStrictAndMeasuredInMiles() {
        var previous = snapshot(0, 0, entry(60000, 2));
        var current = snapshot(0, 0, entry(59000, 2));
        assertThat(new DiffEngine(1000).diff(previous, current)).isEmpty();
        assertThat(new DiffEngine(999).diff(previous, current)).extracting(AvailabilityChange::type).containsExactly(CHEAPER);
        assertThatThrownBy(() -> new DiffEngine(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identicalOrWorseInventoryProducesNoChanges() {
        var previous = snapshot(0, 0, entry(60000, 2));
        assertThat(engine.diff(previous, previous)).isEmpty();
        assertThat(engine.diff(previous, snapshot(0, 0, entry(70000, 0)))).isEmpty();
    }

    @Test
    void unknownSeatCountsAreNotIncreases() {
        for (Integer[] pair : new Integer[][] {{null, 2}, {2, null}, {null, null}}) {
            assertThat(engine.diff(snapshot(0, 0, entry(60000, pair[0])),
                    snapshot(0, 0, entry(60000, pair[1])))).isEmpty();
        }
        assertThat(engine.diff(snapshot(0, 0, entry(60000, 0)), snapshot(0, 0, entry(60000, 1))))
                .extracting(AvailabilityChange::type).containsExactly(MORE_SEATS);
    }

    @Test
    void narrowedAndExpandedRangesIgnoreUncomparedDates() {
        var first = entry(60000, 2);
        var last = entry(2, Cabin.BUSINESS, true, 60000, 2);
        var wide = snapshot(0, 2, first, last);
        var narrow = snapshot(1, 2, last);
        assertThat(engine.diff(wide, narrow)).isEmpty();
        assertThat(engine.diff(narrow, wide)).isEmpty();
        assertThat(engine.diff(snapshot(0, 0, first), snapshot(2, 2, last))).isEmpty();
    }

    @Test
    void touchingRangesCompareTheirSharedEndpoint() {
        var shared = entry(1, Cabin.BUSINESS, true, 60000, 2);
        assertThat(engine.diff(snapshot(0, 1, shared), snapshot(1, 2)))
                .containsExactly(new AvailabilityChange(GONE, shared, null));
    }

    @Test
    void awardIdentitySeparatesConnectionsCabinsAndDates() {
        var nonstop = entry(60000, 2);
        var connection = entry(0, Cabin.BUSINESS, false, 30000, 2);
        var economy = entry(0, Cabin.ECONOMY, true, 20000, 2);
        var tomorrow = entry(1, Cabin.BUSINESS, true, 60000, 2);
        assertThat(engine.diff(snapshot(0, 1, nonstop), snapshot(0, 1, connection, economy, tomorrow)))
                .containsExactly(new AvailabilityChange(NEW, null, connection),
                        new AvailabilityChange(NEW, null, economy), new AvailabilityChange(NEW, null, tomorrow),
                        new AvailabilityChange(GONE, nonstop, null));
    }

    @Test
    void observationTimestampsAndEntryOrderDoNotTriggerChanges() {
        var first = entry(60000, 2);
        var second = entry(1, Cabin.BUSINESS, true, 50000, 1);
        var refreshed = new AvailabilityEntry(ROUTE, DAY, Program.AADVANTAGE, Cabin.BUSINESS,
                60000, 2, true, TIME.plusSeconds(10), TIME);
        var previous = snapshot(0, 1, first, second);
        var current = new Snapshot(previous.query(), TIME.plusSeconds(10), List.of(second, refreshed));
        assertThat(engine.diff(previous, current)).isEmpty();
    }

    @Test
    void rejectsUnrelatedSnapshotsMissingBaselinesAndReversedTime() {
        var previous = snapshot(0, 0);
        for (RouteQuery query : List.of(
                new RouteQuery(Route.of("NRT", "ATL"), new DateRange(DAY, DAY), Program.AADVANTAGE),
                new RouteQuery(ROUTE, new DateRange(DAY, DAY), Program.AEROPLAN))) {
            assertThatThrownBy(() -> engine.diff(previous, new Snapshot(query, TIME, List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> engine.diff(null, previous)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> engine.diff(previous, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> engine.diff(previous, new Snapshot(previous.query(), TIME.minusSeconds(1), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
