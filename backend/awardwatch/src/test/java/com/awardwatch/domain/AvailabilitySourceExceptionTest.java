package com.awardwatch.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.io.IOException;
import java.time.LocalDate;

class AvailabilitySourceExceptionTest {

    private static final RouteQuery QUERY = new RouteQuery(Route.of("ATL", "NRT"), new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19)), Program.AADVANTAGE);

    @Test
    void retryableFactoryMarksTheFailureRetryable() {
        assertThat(AvailabilitySourceException.retryable(QUERY, "429").retryable()).isTrue();
    }

    @Test
    void permanentFactoryMarksTheFailurePermanent() {
        assertThat(AvailabilitySourceException.permanent(QUERY, "no such route").retryable()).isFalse();
    }

    @Test
    void retryableWithCauseMarksTheFailureRetryable() {
        assertThat(AvailabilitySourceException.retryable(QUERY, "timeout", new IOException()).retryable()).isTrue();
    }

    @Test
    void permanentWithCauseMarksTheFailurePermanent() {
        assertThat(AvailabilitySourceException.permanent(QUERY, "bad key", new IOException()).retryable()).isFalse();
    }

    @Test
    void retryableCarriesTheQuery() {
        assertThat(AvailabilitySourceException.retryable(QUERY, "429").query()).isEqualTo(QUERY);
    }

    @Test
    void permanentCarriesTheQuery() {
        assertThat(AvailabilitySourceException.permanent(QUERY, "no such route").query()).isEqualTo(QUERY);
    }

    @Test
    void messageIsPreserved() {
        assertThat(AvailabilitySourceException.retryable(QUERY, "429 from upstream")).hasMessage("429 from upstream");
    }

    @Test
    void messageDoesNotInterpolateTheQuery() {
        assertThat(AvailabilitySourceException.retryable(QUERY, "429 from upstream").getMessage()).doesNotContain("ATL");
    }

    @Test
    void causeIsPreserved() {
        IOException cause = new IOException("connection reset");
        assertThat(AvailabilitySourceException.retryable(QUERY, "timeout", cause)).hasCause(cause);
    }

    @Test
    void twoArgFactoriesLeaveNoCause() {
        assertThat(AvailabilitySourceException.permanent(QUERY, "no such route")).hasNoCause();
    }

    @Test
    void retryableRejectsNullQuery() {
        assertThatThrownBy(() -> AvailabilitySourceException.retryable(null, "429")).isInstanceOf(NullPointerException.class).hasMessage("query cannot be null");
    }

    @Test
    void permanentRejectsNullQuery() {
        assertThatThrownBy(() -> AvailabilitySourceException.permanent(null, "no such route")).isInstanceOf(NullPointerException.class).hasMessage("query cannot be null");
    }

    @Test
    void retryableWithCauseRejectsNullQuery() {
        assertThatThrownBy(() -> AvailabilitySourceException.retryable(null, "timeout", new IOException())).isInstanceOf(NullPointerException.class).hasMessage("query cannot be null");
    }

    @Test
    void permanentWithCauseRejectsNullQuery() {
        assertThatThrownBy(() -> AvailabilitySourceException.permanent(null, "bad key", new IOException())).isInstanceOf(NullPointerException.class).hasMessage("query cannot be null");
    }

    @Test
    void isCheckedSoCallersCannotIgnoreFailure() {
        assertThat(Exception.class).isAssignableFrom(AvailabilitySourceException.class);
        assertThat(RuntimeException.class.isAssignableFrom(AvailabilitySourceException.class)).isFalse();
    }
}
