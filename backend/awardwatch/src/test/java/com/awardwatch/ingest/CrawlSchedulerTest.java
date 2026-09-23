package com.awardwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.awardwatch.domain.Program;
import com.awardwatch.domain.RouteQuery;
import com.awardwatch.persistence.AppUserEntity;
import com.awardwatch.persistence.CrawlStateEntity;
import com.awardwatch.persistence.CrawlStateId;
import com.awardwatch.persistence.CrawlStateRepository;
import com.awardwatch.persistence.WatchEntity;
import com.awardwatch.persistence.WatchRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

class CrawlSchedulerTest {
    private final WatchRepository watches = mock(WatchRepository.class);
    private final CrawlRunner runner = mock(CrawlRunner.class);
    private final CrawlStateRepository states = mock(CrawlStateRepository.class);
    private final Instant now = Instant.parse("2026-09-23T12:00:00Z");
    private final CrawlScheduler scheduler = new CrawlScheduler(watches, runner, states,
        Clock.fixed(now, ZoneOffset.UTC));
    private final LocalDate start = LocalDate.of(2026, 10, 1);

    @BeforeEach
    void setUp() {
        when(runner.supports(any())).thenReturn(true);
        when(runner.crawl(any())).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 90, 91, 180, 181, 365})
    void splitsInclusiveWindowsWithoutGapsOrDuplicates(int days) {
        List<RouteQuery> queries = scheduler.queriesFor(watch(days, "AADVANTAGE"));
        assertThat(queries).hasSize((days + 89) / 90);
        assertThat(queries).allSatisfy(query -> {
            assertThat(query.departureDates().length()).isBetween(1, 90);
            assertThat(query.program()).isEqualTo(Program.AADVANTAGE);
        });
        assertThat(queries.stream().flatMap(q -> q.departureDates().dates()).toList())
            .containsExactlyElementsOf(start.datesUntil(start.plusDays(days)).toList());
    }

    @Test
    void allProgramsMeansOnlySupportedProgramsAndCabinsDoNotMultiplyQueries() {
        when(runner.supports(any())).thenReturn(false);
        when(runner.supports(Program.AADVANTAGE)).thenReturn(true);
        when(runner.supports(Program.AEROPLAN)).thenReturn(true);
        assertThat(scheduler.queriesFor(watch(1, (String[]) null)))
            .extracting(RouteQuery::program)
            .containsExactlyInAnyOrder(Program.AADVANTAGE, Program.AEROPLAN);
        assertThat(scheduler.queriesFor(watch(1))).isEmpty();
    }

    @Test
    void skipsInactiveAndInvalidWatchesAndDeduplicatesAcrossWatchesAndPrograms() {
        WatchEntity inactive = watch(1, "AEROPLAN");
        inactive.setActive(false);
        when(watches.findAllByActiveTrueOrderByCreatedAtAsc()).thenReturn(List.of(
            inactive, watch(1, "INVALID"), watch(91, "AADVANTAGE", "AADVANTAGE"),
            watch(91, "AADVANTAGE")));
        scheduler.crawl();
        ArgumentCaptor<RouteQuery> queries = ArgumentCaptor.forClass(RouteQuery.class);
        verify(runner, times(2)).crawl(queries.capture());
        assertThat(queries.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void stopsTheTickWhenQuotaIsExhausted() {
        when(watches.findAllByActiveTrueOrderByCreatedAtAsc())
            .thenReturn(List.of(watch(181, "AADVANTAGE")));
        when(runner.crawl(any())).thenReturn(false);
        scheduler.crawl();
        verify(runner).crawl(any());
    }

    @Test
    void emptyWatchListDoesNotFetch() {
        when(watches.findAllByActiveTrueOrderByCreatedAtAsc()).thenReturn(List.of());
        scheduler.crawl();
        verifyNoInteractions(runner);
    }

    @Test
    void defaultScheduleRunsEveryFiveMinutesInUtc() throws Exception {
        Scheduled annotation = CrawlScheduler.class.getMethod("crawl").getAnnotation(Scheduled.class);
        assertThat(annotation.zone()).isEqualTo("UTC");
        String expression = annotation.cron().substring("${crawl.cron:".length(), annotation.cron().length() - 1);
        CronExpression cron = CronExpression.parse(expression);
        LocalDateTime tick = LocalDateTime.of(2026, 9, 22, 23, 55);
        assertThat(cron.next(tick)).isEqualTo(tick.plusMinutes(5));
        assertThat(cron.next(tick.plusSeconds(1))).isEqualTo(tick.plusMinutes(5));
    }

    @Test
    void ranksUsingHistoryForTheExactProgramAndDateRange() {
        WatchEntity watch = watch(91, "AADVANTAGE", "AEROPLAN");
        List<RouteQuery> queries = scheduler.queriesFor(watch);
        var recent = new CrawlStateEntity(
            new CrawlStateId("ATL", "NRT", Program.AADVANTAGE,
                start, start.plusDays(89)));
        recent.recordCrawl(now.minusSeconds(60), false);
        when(states.findAllById(any())).thenReturn(List.of(recent));
        when(watches.findAllByActiveTrueOrderByCreatedAtAsc()).thenReturn(List.of(watch, watch));

        scheduler.crawl();

        ArgumentCaptor<RouteQuery> fetched = ArgumentCaptor.forClass(RouteQuery.class);
        verify(runner, times(4)).crawl(fetched.capture());
        assertThat(fetched.getAllValues()).containsExactly(
            queries.get(1), queries.get(2), queries.get(3), queries.get(0));
        verify(states).findAllById(queries.stream().map(query ->
            new CrawlStateId("ATL", "NRT", query.program(),
                query.departureDates().start(), query.departureDates().end())).toList());
    }

    @Test
    void spendsLimitedQuotaOnHighestScoreFirst() {
        WatchEntity distant = new WatchEntity(new AppUserEntity("test@example.com", "hash"),
            "ATL", "LHR", start.plusDays(60), start.plusDays(60),
            new String[] {"J"}, new String[] {"AADVANTAGE"}, null, 1);
        WatchEntity urgent = watch(1, "AADVANTAGE");
        when(watches.findAllByActiveTrueOrderByCreatedAtAsc()).thenReturn(List.of(distant, urgent));
        when(runner.crawl(any())).thenReturn(false);
        scheduler.crawl();
        RouteQuery expected = scheduler.queriesFor(urgent).getFirst();
        verify(runner).crawl(expected);
    }

    @Test
    void expiredWatchesDoNotReachHistoryOrRunner() {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        WatchEntity expired = datedWatch(today.minusDays(30), today.minusDays(1));
        assertThat(scheduler.queriesFor(expired)).isEmpty();
        when(watches.findAllByActiveTrueOrderByCreatedAtAsc()).thenReturn(List.of(expired));
        scheduler.crawl();
        verifyNoInteractions(runner, states);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 90, 91, 181})
    void ongoingWindowsTrimPastDatesBeforeSplitting(int remainingDays) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<RouteQuery> queries = scheduler.queriesFor(
            datedWatch(today.minusDays(100), today.plusDays(remainingDays - 1)));
        assertThat(queries).hasSize((remainingDays + 89) / 90);
        assertThat(queries.stream().flatMap(query -> query.departureDates().dates()).toList())
            .containsExactlyElementsOf(today.datesUntil(today.plusDays(remainingDays)).toList());
    }

    @Test
    void expirationUsesUtcEvenWhenClockHasAnotherZoneAndKeepsToday() {
        Clock otherZone = Clock.fixed(java.time.Instant.parse("2026-09-23T00:00:00Z"),
            java.time.ZoneId.of("America/Los_Angeles"));
        CrawlScheduler utcScheduler = new CrawlScheduler(watches, runner, states, otherZone);
        LocalDate today = LocalDate.of(2026, 9, 23);
        assertThat(utcScheduler.queriesFor(datedWatch(today.minusDays(2), today.minusDays(1)))).isEmpty();
        assertThat(utcScheduler.queriesFor(datedWatch(today.minusDays(2), today)))
            .singleElement().satisfies(query -> assertThat(query.departureDates())
                .isEqualTo(com.awardwatch.domain.DateRange.single(today)));
    }

    @Test
    void tickDispatchesOnlyTheRemainingDatesOfOngoingWatches() {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        when(watches.findAllByActiveTrueOrderByCreatedAtAsc()).thenReturn(List.of(
            datedWatch(today.minusDays(10), today.minusDays(1)),
            datedWatch(today.minusDays(10), today.plusDays(1))));
        scheduler.crawl();
        ArgumentCaptor<RouteQuery> fetched = ArgumentCaptor.forClass(RouteQuery.class);
        verify(runner).crawl(fetched.capture());
        assertThat(fetched.getValue().departureDates())
            .isEqualTo(new com.awardwatch.domain.DateRange(today, today.plusDays(1)));
    }

    private WatchEntity datedWatch(LocalDate from, LocalDate to) {
        return new WatchEntity(new AppUserEntity("test@example.com", "hash"), "ATL", "NRT",
            from, to, new String[] {"J"}, new String[] {"AADVANTAGE"}, null, 1);
    }

    private WatchEntity watch(int days, String... programs) {
        return new WatchEntity(new AppUserEntity("test@example.com", "hash"), "atl", "nrt",
            start, start.plusDays(days - 1), new String[] {"J", "F"}, programs, null, 1);
    }
}
