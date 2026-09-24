package com.awardwatch.alerting;

import com.awardwatch.domain.AvailabilityEntry;
import com.awardwatch.domain.AvailabilityEntry.AwardKey;
import com.awardwatch.domain.DateRange;
import com.awardwatch.domain.Snapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.awardwatch.alerting.AvailabilityChange.Type.*;

/** Pure comparison of successful snapshots; persistence callers must skip failed fetches. */
public final class DiffEngine {
    private final int mileageDropThreshold;

    /** Reports every mileage decrease. */
    public DiffEngine() {
        this(0);
    }

    /** A CHEAPER change requires a drop strictly greater than this nonnegative number of miles. */
    public DiffEngine(int mileageDropThreshold) {
        if (mileageDropThreshold < 0) {
            throw new IllegalArgumentException("mileageDropThreshold cannot be negative");
        }
        this.mileageDropThreshold = mileageDropThreshold;
    }

    /**
     * Compares only dates fetched by both queries. Disjoint windows produce no changes.
     * Both improvements are emitted if an award gets cheaper and gains seats; unknown
     * seat counts never establish an increase. GONE means the key disappeared, not
     * merely that its reported seat count reached zero.
     *
     * Results are immutable, in current-entry order (CHEAPER before MORE_SEATS),
     * followed by disappeared awards in previous-entry order. A baseline is required;
     * callers should skip a first fetch rather than fabricate an empty baseline.
     *
     * @throws IllegalArgumentException if the route or program differs, or current predates previous
     */
    public List<AvailabilityChange> diff(Snapshot previous, Snapshot current) {
        Objects.requireNonNull(previous, "previous cannot be null");
        Objects.requireNonNull(current, "current cannot be null");
        if (!previous.query().route().equals(current.query().route())
                || previous.query().program() != current.query().program()) {
            throw new IllegalArgumentException("snapshots must have the same route and program");
        }
        if (current.fetchedAt().isBefore(previous.fetchedAt())) {
            throw new IllegalArgumentException("current cannot predate previous");
        }
        var overlap = previous.query().departureDates().intersection(current.query().departureDates());
        if (overlap.isEmpty()) return List.of();

        Map<AwardKey, AvailabilityEntry> oldEntries = index(previous, overlap.get());
        Map<AwardKey, AvailabilityEntry> newEntries = index(current, overlap.get());
        List<AvailabilityChange> changes = new ArrayList<>();
        newEntries.forEach((key, after) -> {
            AvailabilityEntry before = oldEntries.remove(key);
            if (before == null) {
                changes.add(new AvailabilityChange(NEW, null, after));
                return;
            }
            if (before.mileageCost() - after.mileageCost() > mileageDropThreshold) {
                changes.add(new AvailabilityChange(CHEAPER, before, after));
            }
            if (before.seatsRemaining() != null && after.seatsRemaining() != null
                    && after.seatsRemaining() > before.seatsRemaining()) {
                changes.add(new AvailabilityChange(MORE_SEATS, before, after));
            }
        });
        oldEntries.values().forEach(before -> changes.add(new AvailabilityChange(GONE, before, null)));
        return List.copyOf(changes);
    }

    private static Map<AwardKey, AvailabilityEntry> index(Snapshot snapshot, DateRange overlap) {
        Map<AwardKey, AvailabilityEntry> entries = new LinkedHashMap<>();
        for (AvailabilityEntry entry : snapshot.entries()) {
            if (overlap.contains(entry.departureDate())) entries.put(entry.awardKey(), entry);
        }
        return entries;
    }
}
