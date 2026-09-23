package com.awardwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.awardwatch.domain.DateRange;
import com.awardwatch.domain.Program;
import com.awardwatch.domain.Route;
import com.awardwatch.domain.RouteQuery;
import com.awardwatch.domain.Snapshot;
import java.util.List;
import java.util.Optional;
import com.awardwatch.persistence.CrawlResultService;
import com.awardwatch.persistence.SnapshotService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

class CrawlRunnerTest {
    private static final Instant NOW = Instant.parse("2026-09-22T23:59:59Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);
    private static final RouteQuery QUERY = new RouteQuery(Route.of("ATL", "NRT"),
        DateRange.single(LocalDate.of(2026, 10, 1)), Program.AADVANTAGE);
    private static final String EMPTY = "{\"data\":[],\"count\":0,\"hasMore\":false}";
    private static final String FIRST_PAGE = """
        {"data":[{"ID":"one","Route":{"OriginAirport":"ATL","DestinationAirport":"NRT",
          "Source":"american"},"Date":"2026-10-01","Source":"american",
          "UpdatedAt":"2026-09-10T11:00:00Z"}],"count":1,"hasMore":true,"cursor":123}
        """;

    private final SeatsAeroClient client = mock(SeatsAeroClient.class);
    private final QuotaBudgeter quota = mock(QuotaBudgeter.class);
    private final CrawlResultService results = mock(CrawlResultService.class);
    private final SnapshotService snapshots = mock(SnapshotService.class);
    private final ResponseCache cache = mock(ResponseCache.class);
    private CrawlRunner runner;

    @BeforeEach
    void setUp() {
        runner = runner(Clock.fixed(NOW, ZoneOffset.UTC));
        when(quota.tryReserve(any(LocalDate.class), eq(1))).thenReturn(true);
    }

    @Test
    void reservesBeforeEveryPageAndPersistsActualCallCount() {
        when(client.search(any())).thenReturn(FIRST_PAGE, EMPTY);
        assertThat(runner.crawl(QUERY)).isTrue();
        InOrder order = inOrder(cache, quota, client, results);
        order.verify(cache).get(QUERY);
        order.verify(quota).tryReserve(TODAY, 1);
        order.verify(client).search(any());
        order.verify(quota).tryReserve(TODAY, 1);
        order.verify(client).search(any());
        order.verify(results).record(argThat(s -> s.query().equals(QUERY)), eq(2), eq("seats.aero"));
        order.verify(cache).put(argThat(s -> s.query().equals(QUERY)));
        verifyNoInteractions(snapshots);
    }

    @Test
    void everyHttpRetryConsumesAnotherReservation() {
        when(client.search(any())).thenThrow(http(503)).thenReturn(EMPTY);
        assertThat(runner.crawl(QUERY)).isTrue();
        verify(quota, times(2)).tryReserve(TODAY, 1);
        verify(results).record(any(), eq(2), eq("seats.aero"));
        verify(quota, never()).refund(any(), anyInt());
    }

    @Test
    void exhaustionBeforeFirstCallDoesNotFetchOrCreateSnapshot() {
        when(quota.tryReserve(TODAY, 1)).thenReturn(false);
        assertThat(runner.crawl(QUERY)).isFalse();
        verifyNoInteractions(client, snapshots, results);
    }

    @Test
    void exhaustionBetweenPagesRecordsFailureAndDiscardsPartialSnapshot() {
        when(quota.tryReserve(TODAY, 1)).thenReturn(true, false);
        when(client.search(any())).thenReturn(FIRST_PAGE);
        assertThat(runner.crawl(QUERY)).isFalse();
        verify(client).search(any());
        verify(snapshots).recordFailure(QUERY, NOW, 1, "seats.aero");
        verifyNoInteractions(results);
    }

    @Test
    void exhaustionBeforeRetryDoesNotMakeUnbudgetedAttempt() {
        when(quota.tryReserve(TODAY, 1)).thenReturn(true, false);
        when(client.search(any())).thenThrow(http(429));
        assertThat(runner.crawl(QUERY)).isFalse();
        verify(client).search(any());
        verify(snapshots).recordFailure(QUERY, NOW, 1, "seats.aero");
    }

    @Test
    void transportFailureRefundsOnlyFailedAttemptEvenAcrossMidnight() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        runner = runner(clock);
        when(client.search(any())).thenReturn(FIRST_PAGE).thenAnswer(invocation -> {
            when(clock.instant()).thenReturn(NOW.plusSeconds(2));
            throw new ResourceAccessException("connection failed");
        });
        assertThat(runner.crawl(QUERY)).isTrue();
        verify(quota).refund(TODAY, 1);
        verify(snapshots).recordFailure(QUERY, NOW.plusSeconds(2), 1, "seats.aero");
        verifyNoInteractions(results);
    }

    @Test
    void httpErrorsRemainChargedAndAreRecordedAsFailures() {
        when(client.search(any())).thenThrow(http(401));
        assertThat(runner.crawl(QUERY)).isTrue();
        verify(snapshots).recordFailure(QUERY, NOW, 1, "seats.aero");
        verify(quota, never()).refund(any(), anyInt());
        verifyNoInteractions(results);
    }

    @Test
    void exhaustedRetriesRecordAllCallsWithoutRefund() {
        when(client.search(any())).thenThrow(http(503));
        assertThat(runner.crawl(QUERY)).isTrue();
        verify(client, times(3)).search(any());
        verify(quota, times(3)).tryReserve(TODAY, 1);
        verify(snapshots).recordFailure(QUERY, NOW, 3, "seats.aero");
        verify(quota, never()).refund(any(), anyInt());
    }

    @Test
    void malformedResponseIsChargedAndNeverBecomesAnEmptySuccess() {
        when(client.search(any())).thenReturn("invalid JSON");
        assertThat(runner.crawl(QUERY)).isTrue();
        verify(snapshots).recordFailure(QUERY, NOW, 1, "seats.aero");
        verify(quota, never()).refund(any(), anyInt());
        verifyNoInteractions(results);
    }

    @Test
    void redisFailureStopsBeforeHttp() {
        when(quota.tryReserve(TODAY, 1)).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> runner.crawl(QUERY)).isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(client, snapshots, results);
    }

    @Test
    void persistenceFailureDoesNotRefundSuccessfulHttpCall() {
        when(client.search(any())).thenReturn(EMPTY);
        doThrow(new DataAccessResourceFailureException("offline"))
            .when(results).record(any(), anyInt(), anyString());
        assertThatThrownBy(() -> runner.crawl(QUERY)).isInstanceOf(DataAccessResourceFailureException.class);
        verify(quota, never()).refund(any(), anyInt());
        verifyNoInteractions(snapshots);
        verify(cache, never()).put(any());
    }

    @Test
    void cacheHitSkipsQuotaHttpAndPersistenceIncludingScoringHistory() {
        var snapshot = new Snapshot(QUERY, NOW, List.of());
        when(cache.get(QUERY)).thenReturn(Optional.of(snapshot));
        assertThat(runner.crawl(QUERY)).isTrue();
        verifyNoInteractions(quota, client, results, snapshots);
        verify(cache, never()).put(any());
    }

    @Test
    void cacheOutageStopsBeforeSpendingQuota() {
        when(cache.get(QUERY)).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> runner.crawl(QUERY)).isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(quota, client, results, snapshots);
    }

    @Test
    void failuresAndPartialResponsesNeverEnterCache() {
        when(client.search(any())).thenReturn(FIRST_PAGE).thenThrow(http(401));
        assertThat(runner.crawl(QUERY)).isTrue();
        verify(cache, never()).put(any());
        verifyNoInteractions(results);
    }

    private CrawlRunner runner(Clock clock) {
        SeatsAeroSource source = new SeatsAeroSource(client, JsonMapper.builder().build(), clock, delay -> { });
        return new CrawlRunner(source, quota, results, snapshots, clock, cache);
    }

    private RestClientResponseException http(int status) {
        return new RestClientResponseException("failure", status, "failure", null, null, null);
    }
}
