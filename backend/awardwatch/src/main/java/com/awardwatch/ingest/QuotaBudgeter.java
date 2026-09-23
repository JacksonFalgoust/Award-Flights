package com.awardwatch.ingest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Shared daily call allowance. Reserve before each HTTP attempt, including retries
 * and additional pages. Redis failures propagate: an unavailable counter must not
 * authorize spending. HTTP integration is the caller's responsibility.
 */
@Component
public class QuotaBudgeter {

    // Initialization, floor check and decrement are one Redis operation. Retain
    // yesterday's counter for late refunds; a refund never creates a missing key.
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
        if not redis.call('GET', KEYS[1]) then
            redis.call('SET', KEYS[1], ARGV[1], 'EXAT', ARGV[4])
        end
        local remaining = tonumber(redis.call('GET', KEYS[1]))
        if remaining - tonumber(ARGV[2]) < tonumber(ARGV[3]) then
            return 0
        end
        redis.call('DECRBY', KEYS[1], ARGV[2])
        return 1
        """, Long.class);

    private static final DefaultRedisScript<Long> REFUND = new DefaultRedisScript<>("""
        local remaining = redis.call('GET', KEYS[1])
        if not remaining then return 0 end
        local credit = math.min(tonumber(ARGV[1]), tonumber(ARGV[2]) - tonumber(remaining))
        if credit > 0 then redis.call('INCRBY', KEYS[1], credit) end
        return 1
        """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final int dailyLimit;
    private final int automatedFloor;

    public QuotaBudgeter(
        StringRedisTemplate redis,
        Clock clock,
        @Value("${quota.daily-limit:1000}") int dailyLimit
    ) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (dailyLimit <= 0) throw new IllegalArgumentException("dailyLimit must be positive");
        this.dailyLimit = dailyLimit;
        this.automatedFloor = (int) ((dailyLimit + 9L) / 10);
    }

    /** Reserves automated calls, leaving at least 10% of the allowance. */
    public boolean tryReserve(int calls) {
        return reserve(currentDate(), calls, automatedFloor);
    }

    /** Manual searches may spend the protected allowance, but never go negative. */
    public boolean tryReserveManual(int calls) {
        return reserve(currentDate(), calls, 0);
    }

    /**
     * Date-explicit variant for callers that may need to refund a transport
     * failure. Capture the UTC date once before reserving and retain it for refund.
     * Refuses a date that is already stale when the reservation is requested.
     */
    public boolean tryReserve(LocalDate reservationDate, int calls) {
        Objects.requireNonNull(reservationDate, "reservationDate");
        validateCalls(calls);
        if (!reservationDate.equals(currentDate())) return false;
        return reserve(reservationDate, calls, automatedFloor);
    }

    /**
     * Refund a successful reservation exactly once, only when the request was not
     * charged (confirmed pre-send failure, not a timeout after dispatch). The caller retains the
     * reservation date; late refunds never credit today's allowance. Credits are
     * capped at the daily limit and expired counters are not recreated.
     */
    public void refund(LocalDate reservationDate, int calls) {
        Objects.requireNonNull(reservationDate, "reservationDate");
        validateCalls(calls);
        if (reservationDate.isAfter(currentDate())) {
            throw new IllegalArgumentException("reservationDate must not be in the future");
        }
        Long result = redis.execute(REFUND, List.of(key(reservationDate)),
            Integer.toString(calls), Integer.toString(dailyLimit));
        Objects.requireNonNull(result, "Redis did not return a refund result");
    }

    private boolean reserve(LocalDate date, int calls, int floor) {
        validateCalls(calls);
        long expiresAt = date.plusDays(2).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        Long result = redis.execute(RESERVE, List.of(key(date)),
            Integer.toString(dailyLimit), Integer.toString(calls),
            Integer.toString(floor), Long.toString(expiresAt));
        return Objects.requireNonNull(result, "Redis did not return a reservation result") == 1L;
    }

    private LocalDate currentDate() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String key(LocalDate date) {
        return "quota:" + date;
    }

    private static void validateCalls(int calls) {
        if (calls <= 0) throw new IllegalArgumentException("calls must be positive");
    }
}
