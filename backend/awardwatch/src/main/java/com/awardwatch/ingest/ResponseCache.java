package com.awardwatch.ingest;

import com.awardwatch.domain.RouteQuery;
import com.awardwatch.domain.Snapshot;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Successful seats.aero responses, shared across callers without refreshing their timestamps. */
@Component
public class ResponseCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResponseCache.class);
    private static final Duration TTL = Duration.ofMinutes(15);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public ResponseCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /** Reads do not extend the TTL. Redis outages propagate before any quota is spent. */
    public Optional<Snapshot> get(RouteQuery query) {
        String value = redis.opsForValue().get(key(query));
        if (value == null) return Optional.empty();
        try {
            Snapshot snapshot = mapper.readValue(value, Snapshot.class);
            if (snapshot != null && query.equals(snapshot.query())) return Optional.of(snapshot);
        } catch (JacksonException | IllegalArgumentException exception) {
            LOGGER.warn("Ignoring invalid cached response for {}", query);
        }
        return Optional.empty();
    }

    /** Call only after the successful snapshot has committed to history. */
    public void put(Snapshot snapshot) {
        redis.opsForValue().set(key(snapshot.query()), mapper.writeValueAsString(snapshot), TTL);
    }

    static String key(RouteQuery query) {
        // Domain construction canonicalizes airport case. Program and both endpoints
        // are part of query identity; cabin is not (responses contain all cabins).
        return "response:seats.aero:v1:" + query.route().origin().value() + ":"
            + query.route().destination().value() + ":" + query.program().name() + ":"
            + query.departureDates().start() + ":" + query.departureDates().end();
    }
}
