package com.awardwatch.alerting;

import com.awardwatch.domain.AvailabilityEntry;
import com.awardwatch.domain.Route;
import com.awardwatch.persistence.WatchEntity;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Selects alert candidates after diffing; raw changes remain available for history. */
public final class WatchFilter {

    /**
     * Uses the current observation, so crossing into a watch's price or seat limits
     * qualifies. Mileage ceilings and seat floors are inclusive. An unknown count
     * means available and satisfies only a one-seat watch; zero never qualifies.
     * Null programs means all programs, while an empty selection matches nothing.
     * Paused watches and GONE changes never produce alert candidates.
     * Preserves change order and returns an immutable list.
     */
    public List<AvailabilityChange> filter(WatchEntity watch, List<AvailabilityChange> changes) {
        Objects.requireNonNull(watch, "watch cannot be null");
        Objects.requireNonNull(changes, "changes cannot be null");
        if (!watch.isActive()) return List.of();
        Route route = Route.of(watch.getOrigin(), watch.getDestination());
        var cabins = Arrays.asList(watch.getCabins());
        String[] selectedPrograms = watch.getPrograms();
        var programs = selectedPrograms == null ? null : Arrays.asList(selectedPrograms);
        Integer maxMileage = watch.getMaxMileage();
        if (watch.getDateTo().isBefore(watch.getDateFrom()) || watch.getMinSeats() < 1
                || (maxMileage != null && maxMileage < 1)) {
            throw new IllegalArgumentException("invalid watch date range or price/seat limits");
        }
        return changes.stream()
            .filter(change -> change.type() != AvailabilityChange.Type.GONE)
            .filter(change -> {
                AvailabilityEntry entry = change.current();
                return entry.route().equals(route)
                    && !entry.departureDate().isBefore(watch.getDateFrom())
                    && !entry.departureDate().isAfter(watch.getDateTo())
                    && cabins.contains(String.valueOf(entry.cabin().code()))
                    && (programs == null || programs.contains(entry.program().name()))
                    && (maxMileage == null || entry.mileageCost() <= maxMileage)
                    && (entry.seatsRemaining() == null
                        ? watch.getMinSeats() == 1
                        : entry.seatsRemaining() >= watch.getMinSeats());
            })
            .toList();
    }
}
