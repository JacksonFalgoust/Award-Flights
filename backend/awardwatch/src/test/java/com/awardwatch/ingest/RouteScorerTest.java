package com.awardwatch.ingest;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RouteScorerTest {
    private final Instant now = Instant.parse("2026-09-23T12:00:00Z");
    private final LocalDate today = LocalDate.of(2026, 9, 23);

    @Test
    void urgencyRisesInsideTwentyOneDaysAndStopsAtDeparture() {
        assertThat(score(22, 60, 0)).isEqualTo(60);
        assertThat(score(21, 60, 0)).isEqualTo(60);
        assertThat(score(20, 60, 0)).isGreaterThan(60);
        assertThat(score(0, 60, 0)).isEqualTo(120);
        assertThat(score(-1, 60, 0)).isEqualTo(120);
    }

    @Test
    void stalenessGrowsToOneDayAndClampsFutureTimestamps() {
        assertThat(score(30, 0, 0)).isZero();
        assertThat(score(30, -1, 0)).isZero();
        assertThat(score(30, 1440, 0)).isEqualTo(1440);
        assertThat(score(30, 2880, 0)).isEqualTo(1440);
        assertThat(RouteScorer.score(today.plusDays(30), null, 0, now)).isEqualTo(1440);
        assertThat(RouteScorer.score(today.plusDays(30), now.minusSeconds(30), 0, now)).isEqualTo(0.5);
    }

    @Test
    void emptyCrawlsDecayPriorityWithoutDroppingQueries() {
        assertThat(score(30, 60, 1)).isEqualTo(54);
        assertThat(score(30, 60, 20)).isCloseTo(60 * Math.pow(0.9, 20), within(0.000001));
        assertThat(score(30, 60, Short.MAX_VALUE)).isEqualTo(6);
        assertThat(score(30, 1440, 100)).isGreaterThan(score(0, 5, 0));
    }

    @Test
    void rejectsInvalidInputs() {
        assertThatIllegalArgumentException().isThrownBy(() -> score(0, 1, -1));
        assertThatNullPointerException().isThrownBy(() -> RouteScorer.score(null, null, 0, now));
        assertThatNullPointerException().isThrownBy(() -> RouteScorer.score(today, null, 0, null));
    }

    private double score(int days, int minutes, int empty) {
        return RouteScorer.score(today.plusDays(days), now.minusSeconds(minutes * 60L), empty, now);
    }
}
