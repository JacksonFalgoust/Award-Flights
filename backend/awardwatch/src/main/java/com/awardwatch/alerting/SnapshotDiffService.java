package com.awardwatch.alerting;

import com.awardwatch.domain.AvailabilityEntry;
import com.awardwatch.domain.DateRange;
import com.awardwatch.domain.Route;
import com.awardwatch.domain.RouteQuery;
import com.awardwatch.domain.Snapshot;
import com.awardwatch.persistence.SnapshotEntity;
import com.awardwatch.persistence.SnapshotRepository;
import com.awardwatch.persistence.WatchEntity;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Selects trusted persisted readings before handing them to the pure diff engine. */
@Service
public class SnapshotDiffService {
    private final SnapshotRepository snapshots;
    private final DiffEngine engine = new DiffEngine();

    public SnapshotDiffService(SnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    /**
     * Call after a snapshot commits. Failed attempts and first successful readings
     * produce no changes. Recovery compares against the last success, skipping failures.
     * A stale trigger is ignored rather than replaying changes for a newer reading.
     * Successful empty readings remain valid inventory observations.
     */
    @Transactional(readOnly = true)
    public List<AvailabilityChange> diff(long snapshotId) {
        SnapshotEntity current = snapshots.findById(snapshotId)
            .orElseThrow(() -> new IllegalArgumentException("snapshot not found: " + snapshotId));
        if (!current.isSucceeded()) return List.of();

        var latest = snapshots
            .findTop2ByOriginAndDestinationAndProgramAndSucceededTrueOrderByObservedAtDescIdDesc(
                current.getOrigin(), current.getDestination(), current.getProgram());
        if (latest.size() < 2 || !Long.valueOf(snapshotId).equals(latest.get(0).getId())) {
            return List.of();
        }
        // Check again at the conversion boundary: failed rows must never become empty Snapshots.
        return engine.diff(toSnapshot(latest.get(1)), toSnapshot(latest.get(0)));
    }

    /** Watch-matching candidates, before cooldown suppression and notification. */
    @Transactional(readOnly = true)
    public List<AvailabilityChange> changesForWatch(long snapshotId, WatchEntity watch) {
        java.util.Objects.requireNonNull(watch, "watch cannot be null");
        if (!watch.isActive()) return List.of();
        return new WatchFilter().filter(watch, diff(snapshotId));
    }

    private static Snapshot toSnapshot(SnapshotEntity entity) {
        if (!entity.isSucceeded()) {
            throw new IllegalStateException("failed snapshots cannot be diff baselines");
        }
        Route route = Route.of(entity.getOrigin(), entity.getDestination());
        RouteQuery query = new RouteQuery(route, new DateRange(entity.getDateFrom(), entity.getDateTo()),
            entity.getProgram());
        var entries = entity.getEntries().stream().map(entry -> new AvailabilityEntry(
            route, entry.getDepartureDate(), entry.getProgram(), entry.getCabin(),
            entry.getMileageCost(), entry.getSeatsRemaining(), entry.isNonstop(),
            entry.getObservedAt(), entry.getRefreshedAt())).toList();
        return new Snapshot(query, entity.getObservedAt(), entries);
    }
}
