package com.awardwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class QuotaBudgeterIntegrationTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    private final Instant noon = today.atTime(12, 0).toInstant(ZoneOffset.UTC);
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private QuotaBudgeter budget;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        budget = budgetAt(noon, 1000);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void automatedFloorAndManualExhaustionDoNotOverspend() {
        assertThat(budget.tryReserve(901)).isFalse();
        assertThat(remaining(today)).isEqualTo("1000");
        assertThat(budget.tryReserve(900)).isTrue();
        assertThat(budget.tryReserve(1)).isFalse();
        assertThat(remaining(today)).isEqualTo("100");
        assertThat(budget.tryReserveManual(101)).isFalse();
        assertThat(budget.tryReserveManual(100)).isTrue();
        assertThat(budget.tryReserveManual(1)).isFalse();
        assertThat(remaining(today)).isEqualTo("0");
    }

    @Test
    void roundsTheProtectedTenPercentUp() {
        QuotaBudgeter smallBudget = budgetAt(noon, 11);
        assertThat(smallBudget.tryReserve(10)).isFalse();
        assertThat(smallBudget.tryReserve(9)).isTrue();
        assertThat(remaining(today)).isEqualTo("2");
    }

    @Test
    void concurrentInstancesShareInitializationAndCannotCrossTheFloor() throws Exception {
        QuotaBudgeter other = budgetAt(noon, 1000);
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            QuotaBudgeter instance = i % 2 == 0 ? budget : other;
            attempts.add(() -> instance.tryReserve(10));
        }
        int successes = 0;
        try (var executor = Executors.newFixedThreadPool(16)) {
            for (var result : executor.invokeAll(attempts)) {
                if (result.get()) successes++;
            }
        }
        assertThat(successes).isEqualTo(90);
        assertThat(remaining(today)).isEqualTo("100");
    }

    @Test
    void ttlIsFixedAndSurvivesReservationsAndRefunds() {
        assertThat(budget.tryReserve(today, 5)).isTrue();
        Long expiry = expiry();
        assertThat(expiry).isEqualTo(today.plusDays(2).atStartOfDay(ZoneOffset.UTC).toEpochSecond());
        assertThat(budget.tryReserve(5)).isTrue();
        budget.refund(today, 5);
        assertThat(expiry()).isEqualTo(expiry);
        assertThat(redis.getExpire("quota:" + today)).isPositive();
        assertThat(remaining(today)).isEqualTo("995");
    }

    @Test
    void rolloverUsesUtcAndLateRefundCreditsOnlyOriginalDate() {
        Instant midnight = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        QuotaBudgeter before = new QuotaBudgeter(redis,
            Clock.fixed(midnight.minusSeconds(1), ZoneId.of("Asia/Tokyo")), 1000);
        QuotaBudgeter after = new QuotaBudgeter(redis,
            Clock.fixed(midnight, ZoneId.of("America/Los_Angeles")), 1000);
        assertThat(before.tryReserve(today, 10)).isTrue();
        assertThat(after.tryReserve(today, 10)).isFalse();
        assertThat(after.tryReserve(20)).isTrue();
        after.refund(today, 10);
        assertThat(remaining(today)).isEqualTo("1000");
        assertThat(remaining(today.plusDays(1))).isEqualTo("980");
    }

    @Test
    void refundsAreCappedAndNeverRecreateExpiredCounters() {
        budget.refund(today.minusDays(2), 5);
        assertThat(remaining(today.minusDays(2))).isNull();
        assertThat(budget.tryReserve(10)).isTrue();
        budget.refund(today, 20);
        assertThat(remaining(today)).isEqualTo("1000");
        redis.delete("quota:" + today);
        budget.refund(today, 10);
        assertThat(remaining(today)).isNull();
    }

    @Test
    void invalidArgumentsDoNotTouchRedis() {
        assertThatIllegalArgumentException().isThrownBy(() -> budget.tryReserve(0));
        assertThatIllegalArgumentException().isThrownBy(() -> budget.tryReserve(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> budget.tryReserveManual(0));
        assertThatIllegalArgumentException().isThrownBy(() -> budget.refund(today, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> budget.refund(today.plusDays(1), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> budgetAt(noon, 0));
        assertThat(remaining(today)).isNull();
    }

    private QuotaBudgeter budgetAt(Instant instant, int limit) {
        return new QuotaBudgeter(redis, Clock.fixed(instant, ZoneOffset.UTC), limit);
    }

    private String remaining(LocalDate date) {
        return redis.opsForValue().get("quota:" + date);
    }

    private Long expiry() {
        return redis.execute(new DefaultRedisScript<>(
            "return redis.call('EXPIRETIME', KEYS[1])", Long.class), List.of("quota:" + today));
    }
}
