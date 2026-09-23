package com.awardwatch.ingest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.awardwatch.domain.Route;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RouteLockIntegrationTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);
    private final Route route = Route.of("ATL", "NRT");
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private RouteLock locks;
    private RouteLock other;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        locks = new RouteLock(redis);
        other = new RouteLock(redis);
    }

    @AfterEach
    void tearDown() {
        locks.shutdown();
        other.shutdown();
        connectionFactory.destroy();
    }

    @Test
    void concurrentInstancesHaveOneWinnerAndOtherRoutesRemainIndependent() throws Exception {
        List<Callable<java.util.Optional<RouteLock.Lease>>> attempts = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            RouteLock instance = i % 2 == 0 ? locks : other;
            attempts.add(() -> instance.tryAcquire(Route.of("atl", "nrt")));
        }
        List<RouteLock.Lease> winners = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var future : executor.invokeAll(attempts)) future.get().ifPresent(winners::add);
        }
        assertThat(winners).hasSize(1);
        assertThat(redis.getExpire(RouteLock.key(route))).isBetween(119L, 120L);
        try (var reverse = other.tryAcquire(Route.of("NRT", "ATL")).orElseThrow();
             var unrelated = other.tryAcquire(Route.of("ATL", "LHR")).orElseThrow()) {
            reverse.ensureHeld();
            unrelated.ensureHeld();
        }
        winners.getFirst().close();
        try (var next = other.tryAcquire(route).orElseThrow()) {
            next.ensureHeld();
            winners.getFirst().close(); // Repeated release cannot delete the next owner's lock.
            assertThat(locks.tryAcquire(route)).isEmpty();
        }
    }

    @Test
    void expiredOwnerCannotRenewOrDeleteItsSuccessor() {
        var old = locks.tryAcquire(route).orElseThrow();
        redis.execute(new DefaultRedisScript<>(
            "return redis.call('PEXPIREAT', KEYS[1], 1)", Long.class), List.of(RouteLock.key(route)));
        try (var next = other.tryAcquire(route).orElseThrow()) {
            assertThatIllegalStateException().isThrownBy(old::ensureHeld);
            old.close();
            next.ensureHeld();
            assertThat(locks.tryAcquire(route)).isEmpty();
        }
    }

    @Test
    void scheduledHeartbeatExtendsOnlyItsOwnLeaseAndCannotRecoverLostOwnership() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        RouteLock controlled = new RouteLock(redis, scheduler);
        try (var lease = controlled.tryAcquire(route).orElseThrow()) {
            ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleWithFixedDelay(heartbeat.capture(), eq(30L), eq(30L), eq(TimeUnit.SECONDS));
            redis.expire(RouteLock.key(route), Duration.ofSeconds(5));
            heartbeat.getValue().run();
            assertThat(redis.getExpire(RouteLock.key(route))).isBetween(119L, 120L);
            redis.opsForValue().set(RouteLock.key(route), "successor", Duration.ofSeconds(5));
            heartbeat.getValue().run();
            assertThat(redis.getExpire(RouteLock.key(route))).isBetween(1L, 5L);
            assertThatIllegalStateException().isThrownBy(lease::ensureHeld);
        } finally {
            controlled.shutdown();
        }
        assertThat(redis.opsForValue().get(RouteLock.key(route))).isEqualTo("successor");
    }
}
