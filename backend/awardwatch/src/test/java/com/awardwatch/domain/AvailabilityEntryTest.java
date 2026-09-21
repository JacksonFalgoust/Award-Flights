package com.awardwatch.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

class AvailabilityEntryTest {

    private static final Route ATL_NRT = Route.of("ATL", "NRT");
    private static final LocalDate DEPARTURE = LocalDate.of(2026, 8, 9);
    private static final Instant OBSERVED = Instant.parse("2026-08-01T12:00:00Z");

    private static AvailabilityEntry entry(Cabin cabin, boolean nonstop, int mileageCost, Integer seatsRemaining) {
        return new AvailabilityEntry(ATL_NRT, DEPARTURE, Program.AADVANTAGE, cabin, mileageCost, seatsRemaining, nonstop, OBSERVED, null);
    }

    @Test
    void negativeMileageRejected() {
        assertThatThrownBy(() -> entry(Cabin.BUSINESS, true, -1, 2)).isInstanceOf(IllegalArgumentException.class).hasMessage("mileageCost must be positive");
    }

    @Test
    void negativeSeatsRejected() {
        assertThatThrownBy(() -> entry(Cabin.BUSINESS, true, 60000, -1)).isInstanceOf(IllegalArgumentException.class).hasMessage("seatsRemaining cannot be negative");
    }

    @Test
    void zeroSeatsAccepted() {
        assertThatCode(() -> entry(Cabin.BUSINESS, true, 60000, 0)).doesNotThrowAnyException();
    }

    @Test
    void zeroMileageRejected() {
        assertThatThrownBy(() -> entry(Cabin.BUSINESS, true, 0, 2)).isInstanceOf(IllegalArgumentException.class).hasMessage("mileageCost must be positive");
    }

    @Test
    void unknownSeatCountAccepted() {
        assertThat(entry(Cabin.BUSINESS, true, 60_000, null).seatsRemaining()).isNull();
    }

    @Test
    void nullRouteRejected() {
        assertThatThrownBy(() -> new AvailabilityEntry(null, DEPARTURE, Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2, true, OBSERVED, null)).isInstanceOf(NullPointerException.class).hasMessage("route cannot be null");
    }

    @Test
    void nullDepartureDateRejected() {
        assertThatThrownBy(() -> new AvailabilityEntry(ATL_NRT, null, Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2, true, OBSERVED, null)).isInstanceOf(NullPointerException.class).hasMessage("departureDate cannot be null");
    }

    @Test
    void nullProgramRejected() {
        assertThatThrownBy(() -> new AvailabilityEntry(ATL_NRT, DEPARTURE, null, Cabin.BUSINESS, 60000, 2, true, OBSERVED, null)).isInstanceOf(NullPointerException.class).hasMessage("program cannot be null");
    }

    @Test
    void nullCabinRejected() {
        assertThatThrownBy(() -> new AvailabilityEntry(ATL_NRT, DEPARTURE, Program.AADVANTAGE, null, 60000, 2, true, OBSERVED, null)).isInstanceOf(NullPointerException.class).hasMessage("cabin cannot be null");
    }

    @Test
    void nullObservedAtRejected() {
        assertThatThrownBy(() -> new AvailabilityEntry(ATL_NRT, DEPARTURE, Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2, true, null, null)).isInstanceOf(NullPointerException.class).hasMessage("observedAt cannot be null");
    }

    @Test
    void refreshedAfterObservedRejected() {
        assertThatThrownBy(() -> new AvailabilityEntry(ATL_NRT, DEPARTURE, Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2, true, OBSERVED, OBSERVED.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class).hasMessage("refreshedAt cannot be after observedAt");
    }

    @Test
    void refreshedEqualToObservedAccepted() {
        AvailabilityEntry entry = new AvailabilityEntry(ATL_NRT, DEPARTURE, Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2, true, OBSERVED, OBSERVED);
        assertThat(entry.staleness()).contains(Duration.ZERO);
    }

    @Test
    void nullRefreshedAtAccepted() {
        assertThatCode(() -> new AvailabilityEntry(ATL_NRT, DEPARTURE, Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2, true, OBSERVED, null)).doesNotThrowAnyException();
    }

    @Test
    void stalenessIsEmptyWhenRefreshedAtIsNull() {
        assertThat(entry(Cabin.BUSINESS, true, 60000, 2).staleness()).isEmpty();
    }

    @Test
    void stalenessIsObservedMinusRefreshed() {
        AvailabilityEntry entry = new AvailabilityEntry(ATL_NRT, DEPARTURE, Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2, true, OBSERVED, OBSERVED.minus(Duration.ofHours(6)));
        assertThat(entry.staleness()).contains(Duration.ofHours(6));
    }

    @Test
    void awardKeyMatchesEntryComponents() {
        AvailabilityEntry entry = entry(Cabin.BUSINESS, true, 60000, 2);
        assertThat(entry.awardKey()).isEqualTo(new AvailabilityEntry.AwardKey(DEPARTURE, Program.AADVANTAGE, Cabin.BUSINESS, true));
    }

    @Test
    void awardKeyIgnoresPriceAndSeats() {
        AvailabilityEntry cheap = entry(Cabin.BUSINESS, true, 60000, 2);
        AvailabilityEntry expensive = entry(Cabin.BUSINESS, true, 90000, 5);

        assertThat(cheap.awardKey()).isEqualTo(expensive.awardKey());
        assertThat(cheap).isNotEqualTo(expensive);
    }

    @Test
    void awardKeyIgnoresObservationTime() {
        AvailabilityEntry earlier = new AvailabilityEntry(ATL_NRT, DEPARTURE, Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2, true, OBSERVED, null);
        AvailabilityEntry later = new AvailabilityEntry(ATL_NRT, DEPARTURE, Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2, true, OBSERVED.plusSeconds(3600), null);

        assertThat(earlier.awardKey()).isEqualTo(later.awardKey());
    }

    @Test
    void awardKeyDistinguishesNonstop() {
        assertThat(entry(Cabin.BUSINESS, true, 60000, 2).awardKey()).isNotEqualTo(entry(Cabin.BUSINESS, false, 60000, 2).awardKey());
    }

    @Test
    void awardKeyDistinguishesCabin() {
        assertThat(entry(Cabin.BUSINESS, true, 60000, 2).awardKey()).isNotEqualTo(entry(Cabin.FIRST, true, 60000, 2).awardKey());
    }

    @Test
    void awardKeyDistinguishesDepartureDate() {
        AvailabilityEntry ninth = entry(Cabin.BUSINESS, true, 60000, 2);
        AvailabilityEntry tenth = new AvailabilityEntry(ATL_NRT, DEPARTURE.plusDays(1), Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2, true, OBSERVED, null);

        assertThat(ninth.awardKey()).isNotEqualTo(tenth.awardKey());
    }

    @Test
    void awardKeyDistinguishesProgram() {
        AvailabilityEntry aadvantage = entry(Cabin.BUSINESS, true, 60000, 2);
        AvailabilityEntry aeroplan = new AvailabilityEntry(ATL_NRT, DEPARTURE, Program.AEROPLAN, Cabin.BUSINESS, 60000, 2, true, OBSERVED, null);

        assertThat(aadvantage.awardKey()).isNotEqualTo(aeroplan.awardKey());
    }

    @Test
    void awardKeyIsBlindToRoute() {
        AvailabilityEntry atlNrt = entry(Cabin.BUSINESS, true, 60000, 2);
        AvailabilityEntry lhrJfk = new AvailabilityEntry(Route.of("LHR", "JFK"), DEPARTURE, Program.AADVANTAGE, Cabin.BUSINESS, 60000, 2, true, OBSERVED, null);

        assertThat(atlNrt.awardKey()).isEqualTo(lhrJfk.awardKey());
    }
}
