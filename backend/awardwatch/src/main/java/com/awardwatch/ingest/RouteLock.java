package com.awardwatch.ingest;

import com.awardwatch.domain.Route;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Nonblocking route leases shared by all application instances using this Redis. */
@Component
public class RouteLock {
    private static final Logger LOGGER = LoggerFactory.getLogger(RouteLock.class);
    private static final Duration TTL = Duration.ofMinutes(2);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('PEXPIRE', KEYS[1], ARGV[2])
        end
        return 0
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);
    private final StringRedisTemplate redis;
    private final ScheduledExecutorService renewals;

    @Autowired
    public RouteLock(StringRedisTemplate redis) {
        this(redis, Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "route-lock-renewal");
            thread.setDaemon(true);
            return thread;
        }));
    }

    RouteLock(StringRedisTemplate redis, ScheduledExecutorService renewals) {
        this.redis = redis;
        this.renewals = renewals;
    }

    public Optional<Lease> tryAcquire(Route route) {
        String key = key(route);
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, TTL);
        if (acquired == null) throw new IllegalStateException("Redis did not return a lock result");
        if (!acquired) return Optional.empty();
        Lease lease = new Lease(key, token);
        try {
            lease.renewal = renewals.scheduleWithFixedDelay(lease::heartbeat, 30, 30, TimeUnit.SECONDS);
        } catch (RuntimeException exception) {
            lease.close();
            throw exception;
        }
        return Optional.of(lease);
    }

    static String key(Route route) {
        return "crawl-lock:v1:" + route.origin().value() + ":" + route.destination().value();
    }

    @PreDestroy
    public void shutdown() {
        renewals.shutdownNow();
        // Active leases expire if the worker cannot reach its finally block.
    }

    public final class Lease implements AutoCloseable {
        private final String key;
        private final String token;
        private ScheduledFuture<?> renewal;
        private boolean lost;
        private boolean closed;

        private Lease(String key, String token) {
            this.key = key;
            this.token = token;
        }

        /** Fail closed before spending quota or recording results after ownership is lost. */
        public synchronized void ensureHeld() {
            if (closed || lost) throw new IllegalStateException("Route lock is no longer held: " + key);
            try {
                Long renewed = redis.execute(RENEW, List.of(key), token, Long.toString(TTL.toMillis()));
                if (!Long.valueOf(1).equals(renewed)) {
                    throw new IllegalStateException("Route lock is no longer held: " + key);
                }
            } catch (RuntimeException exception) {
                lost = true;
                throw exception;
            }
        }

        private void heartbeat() {
            try {
                ensureHeld();
            } catch (RuntimeException exception) {
                LOGGER.warn("Route lease renewal failed for {}; crawl will stop at its next ownership check", key);
                synchronized (this) {
                    if (renewal != null) renewal.cancel(false);
                }
            }
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (renewal != null) renewal.cancel(false);
            redis.execute(RELEASE, List.of(key), token);
        }
    }
}
