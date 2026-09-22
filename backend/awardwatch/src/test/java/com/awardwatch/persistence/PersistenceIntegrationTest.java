package com.awardwatch.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.awardwatch.domain.AvailabilityEntry;
import com.awardwatch.domain.Cabin;
import com.awardwatch.domain.DateRange;
import com.awardwatch.domain.Program;
import com.awardwatch.domain.Route;
import com.awardwatch.domain.RouteQuery;
import com.awardwatch.domain.Snapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "seats-aero.api-key=test-key-not-a-secret",
        "spring.data.redis.repositories.enabled=false",
        "spring.jpa.open-in-view=false"
    }
)
class PersistenceIntegrationTest {

    private static final Route ATL_NRT = Route.of("ATL", "NRT");
    private static final DateRange OCTOBER = new DateRange(
        LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 10, 7)
    );

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("awardwatch_test")
        .withUsername("awardwatch")
        .withPassword("awardwatch");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private SnapshotService snapshotService;

    @Autowired
    private SnapshotRepository snapshotRepository;

    @Autowired
    private AvailabilityEntryRepository entryRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private WatchRepository watchRepository;

    @Autowired
    private CrawlStateRepository crawlStateRepository;

    @Autowired
    private AlertEventRepository alertEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE alert_event, availability_entry, snapshot, "
                + "watch, app_user, crawl_state RESTART IDENTITY CASCADE"
        );
    }

    @Test
    void successfulSnapshotAndEntriesRoundTripThroughPostgres() {
        RouteQuery query = query(ATL_NRT, Program.AADVANTAGE);
        Instant firstObservedAt = Instant.parse("2026-09-21T18:00:01Z");
        Instant secondObservedAt = Instant.parse("2026-09-21T18:00:07Z");
        Instant fetchedAt = Instant.parse("2026-09-21T18:00:10Z");
        Snapshot snapshot = new Snapshot(
            query,
            fetchedAt,
            List.of(
                entry(query, LocalDate.of(2026, 10, 2), Cabin.BUSINESS, 60_000, 2, true, firstObservedAt),
                entry(query, LocalDate.of(2026, 10, 3), Cabin.FIRST, 90_000, null, false, secondObservedAt)
            )
        );

        long snapshotId = snapshotService.record(snapshot, 3, "seats.aero");

        SnapshotEntity storedSnapshot = snapshotRepository.findById(snapshotId).orElseThrow();
        assertThat(storedSnapshot.getOrigin()).isEqualTo("ATL");
        assertThat(storedSnapshot.getDestination()).isEqualTo("NRT");
        assertThat(storedSnapshot.getDateFrom()).isEqualTo(OCTOBER.start());
        assertThat(storedSnapshot.getDateTo()).isEqualTo(OCTOBER.end());
        assertThat(storedSnapshot.getProgram()).isEqualTo(Program.AADVANTAGE);
        assertThat(storedSnapshot.getObservedAt()).isEqualTo(fetchedAt);
        assertThat(storedSnapshot.getApiCallsUsed()).isEqualTo(3);
        assertThat(storedSnapshot.isSucceeded()).isTrue();
        assertThat(storedSnapshot.getSource()).isEqualTo("seats.aero");

        List<AvailabilityEntryEntity> entries = entryRepository.findAllBySnapshot_Id(snapshotId);
        assertThat(entries)
            .extracting(AvailabilityEntryEntity::getObservedAt)
            .containsExactlyInAnyOrder(firstObservedAt, secondObservedAt);
        assertThat(entries)
            .extracting(AvailabilityEntryEntity::getCabin)
            .containsExactlyInAnyOrder(Cabin.BUSINESS, Cabin.FIRST);
        assertThat(entries)
            .filteredOn(entry -> entry.getCabin() == Cabin.FIRST)
            .singleElement()
            .satisfies(entry -> {
                assertThat(entry.getSeatsRemaining()).isNull();
                assertThat(entry.isNonstop()).isFalse();
            });
    }

    @Test
    void newestTwoQueryExcludesFailuresAndOtherQueries() {
        RouteQuery american = query(ATL_NRT, Program.AADVANTAGE);
        RouteQuery aeroplan = query(ATL_NRT, Program.AEROPLAN);
        RouteQuery reverse = query(Route.of("NRT", "ATL"), Program.AADVANTAGE);
        Instant first = Instant.parse("2026-09-21T18:00:00Z");
        Instant second = first.plusSeconds(60);
        Instant third = second.plusSeconds(60);

        snapshotService.record(new Snapshot(american, first, List.of()), 1, "seats.aero");
        long secondId = snapshotService.record(
            new Snapshot(american, second, List.of()),
            1,
            "seats.aero"
        );
        long thirdId = snapshotService.record(
            new Snapshot(american, third, List.of()),
            1,
            "seats.aero"
        );
        snapshotService.recordFailure(american, third.plusSeconds(60), 3, "seats.aero");
        snapshotService.record(
            new Snapshot(aeroplan, third.plusSeconds(120), List.of()),
            1,
            "seats.aero"
        );
        snapshotService.record(
            new Snapshot(reverse, third.plusSeconds(180), List.of()),
            1,
            "seats.aero"
        );

        List<SnapshotEntity> newest = snapshotRepository
            .findTop2ByOriginAndDestinationAndProgramAndSucceededTrueOrderByObservedAtDescIdDesc(
                "ATL",
                "NRT",
                Program.AADVANTAGE
            );

        assertThat(newest)
            .extracting(SnapshotEntity::getId)
            .containsExactly(thirdId, secondId);
        assertThat(newest).allMatch(SnapshotEntity::isSucceeded);
    }

    @Test
    void watchArraysAndCompositeCrawlStateRoundTrip() {
        AppUserEntity user = userRepository.saveAndFlush(
            new AppUserEntity("owner@example.com", "encoded-password")
        );
        WatchEntity watch = watchRepository.saveAndFlush(new WatchEntity(
            user,
            "ATL",
            "NRT",
            OCTOBER.start(),
            OCTOBER.end(),
            new String[] {"J", "F"},
            new String[] {"AADVANTAGE", "AEROPLAN"},
            90_000,
            2
        ));
        CrawlStateId crawlId = new CrawlStateId(
            "ATL",
            "NRT",
            Program.AADVANTAGE,
            OCTOBER.start(),
            OCTOBER.end()
        );
        CrawlStateEntity crawlState = new CrawlStateEntity(crawlId);
        Instant crawledAt = Instant.parse("2026-09-21T19:00:00Z");
        crawlState.recordCrawl(crawledAt, true);
        crawlStateRepository.saveAndFlush(crawlState);

        WatchEntity storedWatch = watchRepository.findById(watch.getId()).orElseThrow();
        assertThat(storedWatch.getCabins()).containsExactly("J", "F");
        assertThat(storedWatch.getPrograms()).containsExactly("AADVANTAGE", "AEROPLAN");
        assertThat(storedWatch.getMaxMileage()).isEqualTo(90_000);
        assertThat(storedWatch.getMinSeats()).isEqualTo(2);

        CrawlStateEntity storedCrawlState = crawlStateRepository.findById(crawlId).orElseThrow();
        assertThat(storedCrawlState.getLastCrawledAt()).isEqualTo(crawledAt);
        assertThat(storedCrawlState.getConsecutiveEmpty()).isEqualTo(1);
    }

    @Test
    void alertCooldownQueryUsesPersistedWatchEntryAndTimestamp() {
        AppUserEntity user = userRepository.saveAndFlush(
            new AppUserEntity("owner@example.com", "encoded-password")
        );
        WatchEntity watch = watchRepository.saveAndFlush(new WatchEntity(
            user,
            "ATL",
            "NRT",
            OCTOBER.start(),
            OCTOBER.end(),
            new String[] {"J"},
            null,
            null,
            1
        ));
        RouteQuery query = query(ATL_NRT, Program.AADVANTAGE);
        Instant observedAt = Instant.parse("2026-09-21T18:00:01Z");
        Snapshot snapshot = new Snapshot(
            query,
            observedAt.plusSeconds(1),
            List.of(entry(
                query,
                LocalDate.of(2026, 10, 2),
                Cabin.BUSINESS,
                60_000,
                2,
                true,
                observedAt
            ))
        );
        long snapshotId = snapshotService.record(snapshot, 1, "seats.aero");
        AvailabilityEntryEntity persistedEntry = entryRepository
            .findAllBySnapshot_Id(snapshotId)
            .getFirst();
        AlertEventEntity alert = alertEventRepository.saveAndFlush(
            new AlertEventEntity(watch, persistedEntry, "email")
        );

        assertThat(alertEventRepository.existsByWatch_IdAndEntry_IdAndSentAtAfter(
            watch.getId(),
            persistedEntry.getId(),
            alert.getSentAt().minusSeconds(1)
        )).isTrue();
        assertThat(alertEventRepository.existsByWatch_IdAndEntry_IdAndSentAtAfter(
            watch.getId(),
            persistedEntry.getId(),
            alert.getSentAt().plusSeconds(1)
        )).isFalse();
    }

    private static RouteQuery query(Route route, Program program) {
        return new RouteQuery(route, OCTOBER, program);
    }

    private static AvailabilityEntry entry(
        RouteQuery query,
        LocalDate departureDate,
        Cabin cabin,
        int mileageCost,
        Integer seatsRemaining,
        boolean nonstop,
        Instant observedAt
    ) {
        return new AvailabilityEntry(
            query.route(),
            departureDate,
            query.program(),
            cabin,
            mileageCost,
            seatsRemaining,
            nonstop,
            observedAt,
            observedAt.minusSeconds(600)
        );
    }
}
