package com.awardwatch.alerting;

import com.awardwatch.domain.*;
import com.awardwatch.persistence.AppUserEntity;
import com.awardwatch.persistence.WatchEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static com.awardwatch.alerting.AvailabilityChange.Type.*;
import static org.assertj.core.api.Assertions.*;

class WatchFilterTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final Route ROUTE = Route.of("ATL", "NRT");
    private final WatchFilter filter = new WatchFilter();

    private WatchEntity watch(String[] cabins, String[] programs, Integer max, int min) {
        return new WatchEntity(new AppUserEntity("test@example.com", "hash"), "ATL", "NRT",
            DAY, DAY.plusDays(120), cabins, programs, max, min);
    }

    private AvailabilityEntry entry(Route route, LocalDate day, Program program, Cabin cabin,
                                    int miles, Integer seats) {
        return new AvailabilityEntry(route, day, program, cabin, miles, seats, true,
            Instant.parse("2026-09-24T12:00:00Z"), null);
    }

    private AvailabilityEntry entry(int miles, Integer seats) {
        return entry(ROUTE, DAY, Program.AADVANTAGE, Cabin.BUSINESS, miles, seats);
    }

    private AvailabilityChange fresh(AvailabilityEntry entry) {
        return new AvailabilityChange(NEW, null, entry);
    }

    @Test
    void cabinCodesAndProgramNamesAreMatchedTogether() {
        var watch = watch(new String[]{"J", "F"}, new String[]{"AADVANTAGE"}, null, 1);
        var business = fresh(entry(60000, 2));
        var first = fresh(entry(ROUTE, DAY, Program.AADVANTAGE, Cabin.FIRST, 80000, 1));
        var economy = fresh(entry(ROUTE, DAY, Program.AADVANTAGE, Cabin.ECONOMY, 20000, 2));
        var otherProgram = fresh(entry(ROUTE, DAY, Program.AEROPLAN, Cabin.BUSINESS, 60000, 2));
        assertThat(filter.filter(watch, List.of(business, economy, first, otherProgram)))
            .containsExactly(business, first);
    }

    @Test
    void nullProgramsAndMileageAreUnrestrictedButEmptySelectionsMatchNothing() {
        var change = fresh(entry(ROUTE, DAY, Program.AEROPLAN, Cabin.BUSINESS, 500000, 1));
        assertThat(filter.filter(watch(new String[]{"J"}, null, null, 1), List.of(change)))
            .containsExactly(change);
        assertThat(filter.filter(watch(new String[]{"J"}, new String[]{}, null, 1), List.of(change))).isEmpty();
        assertThat(filter.filter(watch(new String[]{}, null, null, 1), List.of(change))).isEmpty();
    }

    @Test
    void mileageCeilingAndSeatFloorAreInclusive() {
        var watch = watch(new String[]{"J"}, null, 60000, 2);
        var exact = fresh(entry(60000, 2));
        var better = fresh(entry(59999, 3));
        assertThat(filter.filter(watch, List.of(exact, fresh(entry(60001, 2)),
            fresh(entry(60000, 1)), better))).containsExactly(exact, better);
    }

    @Test
    void unknownSeatsSatisfyOnlyOneSeatAndZeroNeverQualifies() {
        var unknown = fresh(entry(60000, null));
        var zero = fresh(entry(60000, 0));
        assertThat(filter.filter(watch(new String[]{"J"}, null, null, 1), List.of(unknown, zero)))
            .containsExactly(unknown);
        assertThat(filter.filter(watch(new String[]{"J"}, null, null, 2), List.of(unknown, zero))).isEmpty();
    }

    @Test
    void currentValuesDetermineEligibilityAndGoneIsExcluded() {
        var before = entry(70000, 1);
        var after = entry(60000, 2);
        var cheaper = new AvailabilityChange(CHEAPER, before, after);
        var more = new AvailabilityChange(MORE_SEATS, before, after);
        var gone = new AvailabilityChange(GONE, after, null);
        var watch = watch(new String[]{"J"}, null, 60000, 2);
        assertThat(filter.filter(watch, List.of(cheaper, more, gone))).containsExactly(cheaper, more);
        var expensiveIncrease = new AvailabilityChange(MORE_SEATS, after, entry(70000, 3));
        assertThat(filter.filter(watch, List.of(expensiveIncrease))).isEmpty();
    }

    @Test
    void routeAndInclusiveDatesConstrainEvenLongWatchWindows() {
        var watch = watch(new String[]{"J"}, null, null, 1);
        var start = fresh(entry(60000, 2));
        var end = fresh(entry(ROUTE, DAY.plusDays(120), Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2));
        var early = fresh(entry(ROUTE, DAY.minusDays(1), Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2));
        var late = fresh(entry(ROUTE, DAY.plusDays(121), Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2));
        var reversed = fresh(entry(Route.of("NRT", "ATL"), DAY, Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2));
        assertThat(filter.filter(watch, List.of(start, end, early, late, reversed))).containsExactly(start, end);
    }

    @Test
    void pausedWatchesProduceNothingAndResultIsImmutable() {
        var watch = watch(new String[]{"J"}, null, null, 1);
        var change = fresh(entry(60000, 1));
        var result = filter.filter(watch, List.of(change));
        assertThatThrownBy(() -> result.add(change)).isInstanceOf(UnsupportedOperationException.class);
        watch.setActive(false);
        assertThat(filter.filter(watch, List.of(change))).isEmpty();
    }

    @Test
    void invalidNumericLimitsAreRejected() {
        for (var watch : List.of(watch(new String[]{"J"}, null, 0, 1),
                                 watch(new String[]{"J"}, null, null, 0))) {
            assertThatThrownBy(() -> filter.filter(watch, List.of(fresh(entry(60000, 1)))))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
