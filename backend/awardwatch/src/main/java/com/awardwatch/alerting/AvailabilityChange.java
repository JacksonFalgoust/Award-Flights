package com.awardwatch.alerting;

import com.awardwatch.domain.AvailabilityEntry;
import java.util.Objects;

/**
 * A change to one award. NEW has no previous entry; GONE has no current entry.
 * Price and seat changes carry both observations so callers can render the difference.
 */
public record AvailabilityChange(Type type, AvailabilityEntry previous, AvailabilityEntry current) {

    public enum Type { NEW, CHEAPER, MORE_SEATS, GONE }

    public AvailabilityChange {
        Objects.requireNonNull(type, "type cannot be null");
        switch (type) {
            case NEW -> {
                Objects.requireNonNull(current, "NEW requires a current entry");
                if (previous != null) throw new IllegalArgumentException("NEW cannot have a previous entry");
            }
            case GONE -> {
                Objects.requireNonNull(previous, "GONE requires a previous entry");
                if (current != null) throw new IllegalArgumentException("GONE cannot have a current entry");
            }
            case CHEAPER, MORE_SEATS -> {
                Objects.requireNonNull(previous, "change requires a previous entry");
                Objects.requireNonNull(current, "change requires a current entry");
                if (!previous.route().equals(current.route()) || !previous.awardKey().equals(current.awardKey())) {
                    throw new IllegalArgumentException("entries must describe the same award");
                }
            }
        }
    }
}
