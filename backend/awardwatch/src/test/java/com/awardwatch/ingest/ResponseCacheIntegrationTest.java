package com.awardwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.awardwatch.persistence.CrawlResultService;
import com.awardwatch.persistence.SnapshotService;
import java.time.Clock;
import java.time.ZoneOffset;

import com.awardwatch.domain.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class ResponseCacheIntegrationTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);
    private final RouteQuery query = new RouteQuery(Route.of("atl", "nrt"),
        DateRange.single(LocalDate.of(2026, 10, 1)), Program.AADVANTAGE);
    private final Instant fetchedAt = Instant.parse("2026-09-23T12:00:00Z");
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private ResponseCache cache;
    private RouteLock locks;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        locks = new RouteLock(redis);
        cache = new ResponseCache(redis, JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        locks.shutdown();
        connectionFactory.destroy();
    }

    @Test
    void roundTripsAcrossInstancesPreservingAllFieldsAndOriginalTimestamps() {
        Snapshot snapshot = new Snapshot(query, fetchedAt, List.of(
            new AvailabilityEntry(query.route(), query.departureDates().start(), query.program(),
                Cabin.BUSINESS, 70000, null, true, fetchedAt.minusSeconds(1), null),
            new AvailabilityEntry(query.route(), query.departureDates().start(), query.program(),
                Cabin.ECONOMY, 30000, 0, false, fetchedAt.minusSeconds(2), fetchedAt.minusSeconds(3600))));
        cache.put(snapshot);
        ResponseCache other = new ResponseCache(redis, JsonMapper.builder().build());
        assertThat(other.get(query)).contains(snapshot);
        RouteQuery uppercase = new RouteQuery(Route.of("ATL", "NRT"), query.departureDates(), query.program());
        assertThat(other.get(uppercase)).contains(snapshot);
    }

    @Test
    void emptySuccessIsCachedAndReadsDoNotExtendFifteenMinuteExpiry() {
        assertThat(cache.get(query)).isEmpty();
        Snapshot empty = new Snapshot(query, fetchedAt, List.of());
        cache.put(empty);
        assertThat(redis.getExpire(ResponseCache.key(query))).isBetween(899L, 900L);
        Long expiry = expiry();
        assertThat(cache.get(query)).contains(empty);
        assertThat(expiry()).isEqualTo(expiry);
        // Advance this key's Redis expiration rather than sleeping for fifteen minutes.
        redis.execute(new DefaultRedisScript<>(
            "return redis.call('PEXPIREAT', KEYS[1], 1)", Long.class), List.of(ResponseCache.key(query)));
        assertThat(cache.get(query)).isEmpty();
    }

    @Test
    void routeDirectionProgramAndBothDateEndpointsAreIsolated() {
        cache.put(new Snapshot(query, fetchedAt, List.of()));
        assertThat(cache.get(new RouteQuery(Route.of("NRT", "ATL"), query.departureDates(), query.program()))).isEmpty();
        assertThat(cache.get(new RouteQuery(Route.of("ATL", "LHR"), query.departureDates(), query.program()))).isEmpty();
        assertThat(cache.get(new RouteQuery(query.route(), query.departureDates(), Program.AEROPLAN))).isEmpty();
        assertThat(cache.get(new RouteQuery(query.route(), new DateRange(
            query.departureDates().start().minusDays(1), query.departureDates().end()), query.program()))).isEmpty();
        assertThat(cache.get(new RouteQuery(query.route(), new DateRange(
            query.departureDates().start(), query.departureDates().end().plusDays(1)), query.program()))).isEmpty();
    }

    @Test
    void malformedOrMismatchedCachedResponsesAreMisses() {
        for (String invalid : List.of("invalid JSON", "null", "{}")) {
            redis.opsForValue().set(ResponseCache.key(query), invalid);
            assertThat(cache.get(query)).isEmpty();
        }
        RouteQuery other = new RouteQuery(query.route(), query.departureDates(), Program.AEROPLAN);
        cache.put(new Snapshot(other, fetchedAt, List.of()));
        redis.opsForValue().set(ResponseCache.key(query), redis.opsForValue().get(ResponseCache.key(other)));
        assertThat(cache.get(query)).isEmpty();
    }

    @Test
    void repeatedCrawlsSpendQuotaAgainOnlyAfterExpiration() {
        SeatsAeroClient client = mock(SeatsAeroClient.class);
        QuotaBudgeter quota = mock(QuotaBudgeter.class);
        CrawlResultService results = mock(CrawlResultService.class);
        SnapshotService failures = mock(SnapshotService.class);
        Clock clock = Clock.fixed(fetchedAt, ZoneOffset.UTC);
        when(quota.tryReserve(any(LocalDate.class), eq(1))).thenReturn(true);
        when(client.search(any())).thenReturn("{\"data\":[],\"count\":0,\"hasMore\":false}");
        SeatsAeroSource source = new SeatsAeroSource(client, JsonMapper.builder().build(), clock, delay -> {});
        CrawlRunner runner = new CrawlRunner(source, quota, results, failures, clock, cache, locks);
        assertThat(runner.crawl(query)).isTrue();
        assertThat(runner.crawl(query)).isTrue();
        verify(client).search(any());
        verify(quota).tryReserve(any(LocalDate.class), eq(1));
        verify(results).record(any(), eq(1), eq("seats.aero"));
        redis.execute(new DefaultRedisScript<>(
            "return redis.call('PEXPIREAT', KEYS[1], 1)", Long.class), List.of(ResponseCache.key(query)));
        assertThat(runner.crawl(query)).isTrue();
        verify(client, times(2)).search(any());
        verify(quota, times(2)).tryReserve(any(LocalDate.class), eq(1));
        verify(results, times(2)).record(any(), eq(1), eq("seats.aero"));
        verifyNoInteractions(failures);
    }

    @Test
    void overlappingWorkersSkipBusyRouteAcrossProgramsAndDates() throws Exception {
        SeatsAeroClient client = mock(SeatsAeroClient.class);
        QuotaBudgeter quota = mock(QuotaBudgeter.class);
        CrawlResultService results = mock(CrawlResultService.class);
        SnapshotService failures = mock(SnapshotService.class);
        Clock clock = Clock.fixed(fetchedAt, ZoneOffset.UTC);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        when(quota.tryReserve(any(LocalDate.class), eq(1))).thenReturn(true);
        when(client.search(any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!finish.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return "{\"data\":[],\"count\":0,\"hasMore\":false}";
        });
        SeatsAeroSource source = new SeatsAeroSource(client, JsonMapper.builder().build(), clock, delay -> {});
        RouteLock otherLocks = new RouteLock(redis);
        CrawlRunner first = new CrawlRunner(source, quota, results, failures, clock, cache, locks);
        CrawlRunner second = new CrawlRunner(source, quota, results, failures, clock, cache, otherLocks);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var active = executor.submit(() -> first.crawl(query));
            try {
                assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                RouteQuery differentProgramAndDates = new RouteQuery(query.route(),
                    DateRange.single(query.departureDates().start().plusDays(1)), Program.AEROPLAN);
                assertThat(second.crawl(differentProgramAndDates)).isTrue();
                verify(client).search(any());
                verify(quota).tryReserve(any(LocalDate.class), eq(1));
                verifyNoInteractions(results);
            } finally {
                finish.countDown();
            }
            assertThat(active.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(second.crawl(query)).isTrue(); // Now served from the first worker's cache.
            verify(client).search(any());
            RouteQuery unrelated = new RouteQuery(Route.of("ATL", "LHR"), query.departureDates(), query.program());
            assertThat(second.crawl(unrelated)).isTrue();
            verify(client, times(2)).search(any());
        } finally {
            otherLocks.shutdown();
        }
    }

    private Long expiry() {
        return redis.execute(new DefaultRedisScript<>(
            "return redis.call('PEXPIRETIME', KEYS[1])", Long.class), List.of(ResponseCache.key(query)));
    }
}
