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
import com.awardwatch.persistence.CrawlResultService;
import com.awardwatch.persistence.SnapshotService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private final RouteLock locks = mock(RouteLock.class);
    private final RouteLock.Lease lease = mock(RouteLock.Lease.class);
    private CrawlRunner runner;

    @BeforeEach
    void setUp() {
        when(locks.tryAcquire(QUERY.route())).thenReturn(Optional.of(lease));
        runner = runner(Clock.fixed(NOW, ZoneOffset.UTC));
        when(quota.tryReserve(any(LocalDate.class), eq(1))).thenReturn(true);
    }

    @Test
    void reservesBeforeEveryPageAndPersistsActualCallCount() {
        when(client.search(any())).thenReturn(FIRST_PAGE, EMPTY);
        assertThat(runner.crawl(QUERY)).isTrue();
        InOrder order = inOrder(cache, locks, lease, quota, client, results);
        order.verify(cache).get(QUERY);
        order.verify(locks).tryAcquire(QUERY.route());
        order.verify(cache).get(QUERY);
        order.verify(quota).tryReserve(TODAY, 1);
        order.verify(client).search(any());
        order.verify(quota).tryReserve(TODAY, 1);
        order.verify(client).search(any());
        order.verify(results).record(argThat(s -> s.query().equals(QUERY)), eq(2), eq("seats.aero"));
        order.verify(cache).put(argThat(s -> s.query().equals(QUERY)));
        order.verify(lease).close();
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
            throw new ResourceAccessException("connection failed", new java.net.ConnectException("refused"));
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

    @Test
    void busyRouteSkipsWithoutStoppingTheTickOrSpendingQuota() {
        when(locks.tryAcquire(QUERY.route())).thenReturn(Optional.empty());
        assertThat(runner.crawl(QUERY)).isTrue();
        verifyNoInteractions(client, quota, results, snapshots, lease);
    }

    @Test
    void rechecksCacheUnderLockAndReleasesWithoutFetching() {
        when(cache.get(QUERY)).thenReturn(Optional.empty()).thenReturn(Optional.of(new Snapshot(QUERY, NOW, List.of())));
        assertThat(runner.crawl(QUERY)).isTrue();
        verify(lease).close();
        verifyNoInteractions(client, quota, results, snapshots);
    }

    @ParameterizedTest
    @ValueSource(strings = {"quota", "source", "persistence", "cache", "ownership"})
    void releasesLockOnEveryFailurePath(String failure) {
        when(client.search(any())).thenReturn(EMPTY);
        switch (failure) {
            case "quota" -> when(quota.tryReserve(any(LocalDate.class), eq(1))).thenReturn(false);
            case "source" -> when(client.search(any())).thenThrow(http(401));
            case "persistence" -> doThrow(new IllegalStateException("failed"))
                .when(results).record(any(), anyInt(), anyString());
            case "cache" -> doThrow(new IllegalStateException("failed")).when(cache).put(any());
            case "ownership" -> doThrow(new IllegalStateException("lost")).when(lease).ensureHeld();
        }
        if (failure.equals("quota")) assertThat(runner.crawl(QUERY)).isFalse();
        else if (failure.equals("source")) assertThat(runner.crawl(QUERY)).isTrue();
        else assertThatThrownBy(() -> runner.crawl(QUERY)).isInstanceOf(IllegalStateException.class);
        verify(lease).close();
        if (failure.equals("ownership")) verifyNoInteractions(client, quota, results, snapshots);
    }

    @Test
    void lostLeaseAfterHttpPreventsPersistenceAndCacheWrites() {
        when(client.search(any())).thenAnswer(invocation -> {
            doThrow(new IllegalStateException("lost")).when(lease).ensureHeld();
            return EMPTY;
        });
        assertThatThrownBy(() -> runner.crawl(QUERY)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(results, snapshots);
        verify(cache, never()).put(any());
        verify(lease).close();
    }

    @Test
    void lockRedisFailureDoesNotAuthorizeHttp() {
        when(locks.tryAcquire(QUERY.route())).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> runner.crawl(QUERY)).isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(client, quota, results, snapshots, lease);
    }

    @ParameterizedTest
    @ValueSource(strings = {"read_timeout", "reset", "unknown"})
    void uncertainTransportFailuresKeepAllDispatchedCallsCharged(String kind) {
        java.io.IOException cause = switch (kind) {
            case "read_timeout" -> new java.net.SocketTimeoutException("read timed out");
            case "reset" -> new java.net.SocketException("connection reset");
            default -> new java.io.IOException("unspecified failure");
        };
        when(client.search(any())).thenReturn(FIRST_PAGE)
            .thenThrow(new ResourceAccessException("request dispatched", cause));
        assertThat(runner.crawl(QUERY)).isTrue();
        verify(client, times(2)).search(any());
        verify(quota, times(2)).tryReserve(TODAY, 1);
        verify(quota, never()).refund(any(), anyInt());
        verify(snapshots).recordFailure(QUERY, NOW, 2, "seats.aero");
        verifyNoInteractions(results);
        verify(cache, never()).put(any());
        verify(lease).close();
    }

    private CrawlRunner runner(Clock clock) {
        SeatsAeroSource source = new SeatsAeroSource(client, JsonMapper.builder().build(), clock, delay -> { });
        return new CrawlRunner(source, quota, results, snapshots, clock, cache, locks);
    }

    private RestClientResponseException http(int status) {
        return new RestClientResponseException("failure", status, "failure", null, null, null);
    }
}
