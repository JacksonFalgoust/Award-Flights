package com.awardwatch.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.LocalDate;

class RouteQueryTest {

    private static final Route ATL_NRT = Route.of("ATL", "NRT");
    private static final DateRange WINDOW = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));

    @Test
    void nullRouteRejected() {
        assertThatThrownBy(() -> new RouteQuery(null, WINDOW, Program.AADVANTAGE)).isInstanceOf(NullPointerException.class).hasMessage("route cannot be null");
    }

    @Test
    void nullDepartureDatesRejected() {
        assertThatThrownBy(() -> new RouteQuery(ATL_NRT, null, Program.AADVANTAGE)).isInstanceOf(NullPointerException.class).hasMessage("departureDates cannot be null");
    }

    @Test
    void nullProgramRejected() {
        assertThatThrownBy(() -> new RouteQuery(ATL_NRT, WINDOW, null)).isInstanceOf(NullPointerException.class).hasMessage("program cannot be null");
    }

    @Test
    void componentsArePreserved() {
        RouteQuery query = new RouteQuery(ATL_NRT, WINDOW, Program.AADVANTAGE);

        assertThat(query.route()).isEqualTo(ATL_NRT);
        assertThat(query.departureDates()).isEqualTo(WINDOW);
        assertThat(query.program()).isEqualTo(Program.AADVANTAGE);
    }

    @Test
    void equalQueriesAreEqualAndHashAlike() {
        RouteQuery first = new RouteQuery(Route.of("ATL", "NRT"), new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19)), Program.AADVANTAGE);
        RouteQuery second = new RouteQuery(Route.of("ATL", "NRT"), new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19)), Program.AADVANTAGE);

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void differentProgramIsNotEqual() {
        RouteQuery aadvantage = new RouteQuery(ATL_NRT, WINDOW, Program.AADVANTAGE);
        RouteQuery aeroplan = new RouteQuery(ATL_NRT, WINDOW, Program.AEROPLAN);

        assertThat(aadvantage).isNotEqualTo(aeroplan);
    }

    @Test
    void reversedRouteIsNotEqual() {
        RouteQuery outbound = new RouteQuery(Route.of("ATL", "NRT"), WINDOW, Program.AADVANTAGE);
        RouteQuery inbound = new RouteQuery(Route.of("NRT", "ATL"), WINDOW, Program.AADVANTAGE);

        assertThat(outbound).isNotEqualTo(inbound);
    }

    @Test
    void differentDateRangeIsNotEqual() {
        RouteQuery narrow = new RouteQuery(ATL_NRT, WINDOW, Program.AADVANTAGE);
        RouteQuery wide = new RouteQuery(ATL_NRT, new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 20)), Program.AADVANTAGE);

        assertThat(narrow).isNotEqualTo(wide);
    }
}
