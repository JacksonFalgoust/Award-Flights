package com.awardwatch.ingest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.awardwatch.domain.*;
import com.awardwatch.persistence.CrawlResultService;
import com.awardwatch.persistence.SnapshotService;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class CallLoggingTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(SeatsAeroClient.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final RouteQuery query = new RouteQuery(Route.of("ATL", "NRT"),
        DateRange.single(LocalDate.of(2026, 10, 1)), Program.AADVANTAGE);
    private MockRestServiceServer server;
    private SeatsAeroClient client;
    private SeatsAeroSource source;
    private static final String EMPTY = "{\"data\":[],\"count\":0,\"hasMore\":false}";

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test/")
            .defaultHeader("Partner-Authorization", "secret-test-key");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SeatsAeroClient(builder.build());
        source = new SeatsAeroSource(client, JsonMapper.builder().build(),
            Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC), delay -> {});
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void logsEveryRetryAndPageExactlyOnceWithItsCost() throws Exception {
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(503)));
        server.expect(anything()).andRespond(withSuccess("""
            {"data":[{"ID":"one","Route":{"OriginAirport":"ATL","DestinationAirport":"NRT",
            "Source":"american"},"Date":"2026-10-01","Source":"american",
            "UpdatedAt":"2026-09-10T11:00:00Z"}],"count":1,"hasMore":true,"cursor":123}
            """, MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        source.fetch(query);
        assertThat(logs()).hasSize(3).allSatisfy(log -> assertThat(log)
            .contains("source=seats.aero", "endpoint=search", "origin=ATL", "destination=NRT",
                "program=american", "date_from=2026-10-01", "date_to=2026-10-01", "cost=1", "duration_ms=")
            .doesNotContain("secret-test-key", "Partner-Authorization", "UpdatedAt"));
        assertThat(logs().get(0)).contains("skip=0", "outcome=http_error", "status=503");
        assertThat(logs().get(1)).contains("skip=0", "outcome=response", "status=200");
        assertThat(logs().get(2)).contains("skip=1", "outcome=response", "status=200");
        assertThat(logs().stream().map(log -> log.split("call_id=")[1].split(" ")[0]).toList())
            .doesNotHaveDuplicates();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500})
    void directClientHttpErrorsAreChargedWithoutLeakingErrorBodies(int status) {
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(status)).body("private-error-body"));
        assertThatThrownBy(() -> client.search(request())).isInstanceOf(RuntimeException.class);
        assertThat(logs()).singleElement().satisfies(log -> assertThat(log)
            .contains("status=" + status, "cost=1", "outcome=http_error")
            .doesNotContain("private-error-body", "secret-test-key"));
        server.verify();
    }

    @Test
    void confirmedPreSendFailureCostsZeroWithoutLeakingExceptionDetails() {
        server.expect(anything()).andRespond(withException(new java.net.UnknownHostException("private-transport-detail")));
        assertThatThrownBy(() -> source.fetch(query)).isInstanceOf(AvailabilitySourceException.class);
        assertThat(logs()).singleElement().satisfies(log -> assertThat(log)
            .contains("cost=0", "outcome=transport_error", "status=unknown")
            .doesNotContain("private-transport-detail"));
        server.verify();
    }

    @Test
    void malformedSuccessfulResponseStillCostsOneCall() {
        server.expect(anything()).andRespond(withSuccess("bad JSON", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> source.fetch(query)).isInstanceOf(AvailabilitySourceException.class);
        assertThat(logs()).singleElement().satisfies(log -> assertThat(log).contains("status=200", "cost=1"));
    }

    @Test
    void quotaRejectionBeforeHttpProducesNoCallLog() {
        SeatsAeroSource.CallAccounting budget = new SeatsAeroSource.CallAccounting() {
            public void beforeCall() { throw new IllegalStateException("quota unavailable"); }
            public void transportFailed() { throw new AssertionError("no request was sent"); }
        };
        assertThatIllegalStateException().isThrownBy(() -> source.fetch(query, budget));
        assertThat(logs()).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void logsWhetherTransportRefundActuallyReachedRedis(boolean refundFails) {
        Logger runnerLogger = (Logger) LoggerFactory.getLogger(CrawlRunner.class);
        ListAppender<ILoggingEvent> refunds = new ListAppender<>();
        refunds.start();
        runnerLogger.addAppender(refunds);
        try {
            QuotaBudgeter quota = mock(QuotaBudgeter.class);
            RouteLock locks = mock(RouteLock.class);
            when(locks.tryAcquire(query.route())).thenReturn(java.util.Optional.of(mock(RouteLock.Lease.class)));
            when(quota.tryReserve(any(LocalDate.class), eq(1))).thenReturn(true);
            if (refundFails) doThrow(new IllegalStateException("private-redis-detail"))
                .when(quota).refund(any(LocalDate.class), eq(1));
            CrawlRunner runner = new CrawlRunner(source, quota, mock(CrawlResultService.class),
                mock(SnapshotService.class), Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC),
                mock(ResponseCache.class), locks);
            server.expect(anything()).andRespond(withException(new java.net.ConnectException("connection refused")));
            if (refundFails) assertThatIllegalStateException().isThrownBy(() -> runner.crawl(query));
            else assertThat(runner.crawl(query)).isTrue();
            assertThat(refunds.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("quota_refund")).toList())
                .singleElement().satisfies(message -> assertThat(message)
                    .contains("source=seats.aero", "quota_date=2026-09-23", "calls=1",
                        "outcome=" + (refundFails ? "failed" : "applied"))
                    .doesNotContain("private-redis-detail"));
            assertThat(logs()).singleElement().satisfies(message -> assertThat(message).contains("cost=0"));
        } finally {
            runnerLogger.detachAppender(refunds);
            refunds.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void uncertainTransportFailuresRemainChargedInLogs(boolean readTimeout) {
        IOException cause = readTimeout ? new java.net.SocketTimeoutException("private-timeout")
            : new IOException("private-unknown-failure");
        server.expect(anything()).andRespond(withException(cause));
        assertThatThrownBy(() -> source.fetch(query)).isInstanceOf(AvailabilitySourceException.class);
        assertThat(logs()).singleElement().satisfies(message -> assertThat(message)
            .contains("cost=1", "outcome=transport_error", "status=unknown")
            .doesNotContain("private-timeout", "private-unknown-failure"));
        server.verify();
    }

    private SeatsAeroSearchRequest request() {
        return new SeatsAeroSearchRequest("ATL", "NRT", query.departureDates().start(),
            query.departureDates().end(), "american", 0, null);
    }

    private List<String> logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
