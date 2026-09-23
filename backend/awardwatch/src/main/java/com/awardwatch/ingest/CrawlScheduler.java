package com.awardwatch.ingest;

import com.awardwatch.domain.DateRange;
import com.awardwatch.domain.Program;
import com.awardwatch.domain.Route;
import com.awardwatch.domain.RouteQuery;
import com.awardwatch.persistence.CrawlStateEntity;
import com.awardwatch.persistence.CrawlStateId;
import com.awardwatch.persistence.CrawlStateRepository;
import com.awardwatch.persistence.WatchEntity;
import com.awardwatch.persistence.WatchRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CrawlScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrawlScheduler.class);
    private final WatchRepository watches;
    private final CrawlRunner runner;

    private final CrawlStateRepository states;
    private final Clock clock;

    public CrawlScheduler(WatchRepository watches, CrawlRunner runner,
                          CrawlStateRepository states, Clock clock) {
        this.watches = watches;
        this.runner = runner;
        this.states = states;
        this.clock = clock;
    }

    /** Runs sequentially within a tick; the runner caches responses and locks routes across ticks. */
    @Scheduled(cron = "${crawl.cron:0 */5 * * * *}", zone = "UTC")
    public void crawl() {
        Set<RouteQuery> queries = new LinkedHashSet<>();
        for (WatchEntity watch : watches.findAllByActiveTrueOrderByCreatedAtAsc()) {
            if (!watch.isActive()) continue;
            try {
                queries.addAll(queriesFor(watch));
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("Skipping invalid watch {}: {}", watch.getId(), exception.getMessage());
            }
        }
        if (queries.isEmpty()) return;
        Instant now = clock.instant();
        Map<CrawlStateId, CrawlStateEntity> history = states.findAllById(
            queries.stream().map(CrawlScheduler::stateId).toList()).stream()
            .collect(Collectors.toMap(CrawlStateEntity::getId, Function.identity()));
        // Stream sorting is stable: ties retain watch creation / query expansion order.
        List<RouteQuery> ranked = queries.stream().sorted(Comparator.comparingDouble(
            (RouteQuery query) -> {
                CrawlStateEntity state = history.get(stateId(query));
                return RouteScorer.score(query.departureDates().start(),
                    state == null ? null : state.getLastCrawledAt(),
                    state == null ? 0 : state.getConsecutiveEmpty(), now);
            }).reversed()).toList();
        for (RouteQuery query : ranked) {
            if (Thread.currentThread().isInterrupted()) return;
            // Infrastructure failures propagate and stop this tick. Source failures
            // are recorded by the runner and do not suppress unrelated queries.
            if (!runner.crawl(query)) return;
        }
    }

    private static CrawlStateId stateId(RouteQuery query) {
        return new CrawlStateId(query.route().origin().value(), query.route().destination().value(),
            query.program(), query.departureDates().start(), query.departureDates().end());
    }

    List<RouteQuery> queriesFor(WatchEntity watch) {
        Route route = Route.of(watch.getOrigin(), watch.getDestination());
        LocalDate start = watch.getDateFrom();
        LocalDate end = watch.getDateTo();
        if (end.isBefore(start)) throw new IllegalArgumentException("watch end precedes start");
        String[] selected = watch.getPrograms();
        Set<Program> programs = new LinkedHashSet<>();
        if (selected == null) {
            programs.addAll(Arrays.asList(Program.values()));
        } else {
            for (String name : selected) programs.add(Program.valueOf(name));
        }
        programs.removeIf(program -> !runner.supports(program));
        Set<RouteQuery> queries = new LinkedHashSet<>();
        while (true) {
            long days = Math.min(ChronoUnit.DAYS.between(start, end), DateRange.MAX_DAYS - 1L);
            LocalDate chunkEnd = start.plusDays(days);
            DateRange dates = new DateRange(start, chunkEnd);
            for (Program program : programs) queries.add(new RouteQuery(route, dates, program));
            if (chunkEnd.equals(end)) break;
            start = chunkEnd.plusDays(1);
        }
        return List.copyOf(queries);
    }
}
