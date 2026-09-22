package com.awardwatch.persistence;

import com.awardwatch.domain.AvailabilityEntry;
import com.awardwatch.domain.RouteQuery;
import com.awardwatch.domain.Snapshot;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SnapshotService {

    private final SnapshotRepository snapshotRepository;

    public SnapshotService(SnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    /**
     * Appends one successful source reading and all of its entries as one transaction.
     * An empty entry list remains a successful reading meaning no awards were available.
     *
     * @return the generated database ID of the persisted snapshot
     */
    @Transactional
    public long record(Snapshot snapshot, int apiCallsUsed, String source) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        validateBookkeeping(apiCallsUsed, source);

        SnapshotEntity entity = snapshotEntity(
            snapshot.query(),
            snapshot.fetchedAt(),
            apiCallsUsed,
            true,
            source.strip()
        );

        for (AvailabilityEntry entry : snapshot.entries()) {
            entity.addEntry(new AvailabilityEntryEntity(
                entity,
                entry.departureDate(),
                entry.cabin(),
                entry.mileageCost(),
                entry.seatsRemaining(),
                entry.nonstop(),
                entry.observedAt(),
                entry.refreshedAt()
            ));
        }

        return persistedId(snapshotRepository.save(entity));
    }

    /**
     * Appends a failed attempt without manufacturing an empty domain Snapshot. Failed
     * rows never contain availability entries and are excluded from diff baselines.
     *
     * @return the generated database ID of the persisted attempt
     */
    @Transactional
    public long recordFailure(
        RouteQuery query,
        Instant attemptedAt,
        int apiCallsUsed,
        String source
    ) {
        Objects.requireNonNull(query, "query cannot be null");
        Objects.requireNonNull(attemptedAt, "attemptedAt cannot be null");
        validateBookkeeping(apiCallsUsed, source);

        SnapshotEntity entity = snapshotEntity(
            query,
            attemptedAt,
            apiCallsUsed,
            false,
            source.strip()
        );

        return persistedId(snapshotRepository.save(entity));
    }

    private static SnapshotEntity snapshotEntity(
        RouteQuery query,
        Instant observedAt,
        int apiCallsUsed,
        boolean succeeded,
        String source
    ) {
        return new SnapshotEntity(
            query.route().origin().value(),
            query.route().destination().value(),
            query.departureDates().start(),
            query.departureDates().end(),
            query.program(),
            observedAt,
            apiCallsUsed,
            succeeded,
            source
        );
    }

    private static void validateBookkeeping(int apiCallsUsed, String source) {
        Objects.requireNonNull(source, "source cannot be null");
        if (apiCallsUsed < 0 || apiCallsUsed > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                "apiCallsUsed must be between 0 and " + Short.MAX_VALUE
            );
        }
        if (source.isBlank()) {
            throw new IllegalArgumentException("source cannot be blank");
        }
    }

    private static long persistedId(SnapshotEntity entity) {
        Long id = entity.getId();
        if (id == null) {
            throw new IllegalStateException("persisted snapshot has no ID");
        }
        return id;
    }
}
