package com.awardwatch.ingest;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatsAeroSearchRequestTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 10, 7);

    @Test
    void validRequestRetainsAllValues() {
        SeatsAeroSearchRequest request = new SeatsAeroSearchRequest(
                "ATL",
                "NRT",
                START,
                END,
                "american",
                1_000,
                1_689_009_958L
        );

        assertThat(request.originAirport()).isEqualTo("ATL");
        assertThat(request.destinationAirport()).isEqualTo("NRT");
        assertThat(request.startDate()).isEqualTo(START);
        assertThat(request.endDate()).isEqualTo(END);
        assertThat(request.sourceSlug()).isEqualTo("american");
        assertThat(request.skip()).isEqualTo(1_000);
        assertThat(request.cursor()).isEqualTo(1_689_009_958L);
    }

    @Test
    void nullOriginAirportIsRejected() {
        assertThatThrownBy(() -> request(null, "NRT", START, END, "american", 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("originAirport cannot be null");
    }

    @Test
    void blankOriginAirportIsRejected() {
        assertThatThrownBy(() -> request("   ", "NRT", START, END, "american", 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("originAirport cannot be blank");
    }

    @Test
    void nullDestinationAirportIsRejected() {
        assertThatThrownBy(() -> request("ATL", null, START, END, "american", 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("destinationAirport cannot be null");
    }

    @Test
    void blankDestinationAirportIsRejected() {
        assertThatThrownBy(() -> request("ATL", "\t", START, END, "american", 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("destinationAirport cannot be blank");
    }

    @Test
    void nullStartDateIsRejected() {
        assertThatThrownBy(() -> request("ATL", "NRT", null, END, "american", 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("startDate cannot be null");
    }

    @Test
    void nullEndDateIsRejected() {
        assertThatThrownBy(() -> request("ATL", "NRT", START, null, "american", 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("endDate cannot be null");
    }

    @Test
    void nullSourceSlugIsRejected() {
        assertThatThrownBy(() -> request("ATL", "NRT", START, END, null, 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("sourceSlug cannot be null");
    }

    @Test
    void blankSourceSlugIsRejected() {
        assertThatThrownBy(() -> request("ATL", "NRT", START, END, "\n", 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceSlug cannot be blank");
    }

    @Test
    void endDateBeforeStartDateIsRejected() {
        assertThatThrownBy(() -> request("ATL", "NRT", START, START.minusDays(1), "american", 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endDate cannot be before startDate");
    }

    @Test
    void negativeSkipIsRejected() {
        assertThatThrownBy(() -> request("ATL", "NRT", START, END, "american", -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("skip cannot be negative");
    }

    @Test
    void zeroSkipIsAccepted() {
        assertThatCode(() -> request("ATL", "NRT", START, END, "american", 0, null))
                .doesNotThrowAnyException();
    }

    @Test
    void singleDateRangeIsAccepted() {
        assertThatCode(() -> request("ATL", "NRT", START, START, "american", 0, null))
                .doesNotThrowAnyException();
    }

    @Test
    void nullCursorIsAccepted() {
        SeatsAeroSearchRequest request = request("ATL", "NRT", START, END, "american", 0, null);

        assertThat(request.cursor()).isNull();
    }

    @Test
    void cursorBeyondIntegerRangeIsAccepted() {
        long cursor = 3_000_000_000L;

        SeatsAeroSearchRequest request = request("ATL", "NRT", START, END, "american", 0, cursor);

        assertThat(request.cursor()).isEqualTo(cursor);
    }

    private static SeatsAeroSearchRequest request(
            String originAirport,
            String destinationAirport,
            LocalDate startDate,
            LocalDate endDate,
            String sourceSlug,
            int skip,
            Long cursor
    ) {
        return new SeatsAeroSearchRequest(
                originAirport,
                destinationAirport,
                startDate,
                endDate,
                sourceSlug,
                skip,
                cursor
        );
    }
}
