package com.awardwatch.persistence;

import com.awardwatch.domain.Cabin;
import com.awardwatch.domain.Program;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "availability_entry")
public class AvailabilityEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    private SnapshotEntity snapshot;

    @Column(name = "departure_date", nullable = false, updatable = false)
    private LocalDate departureDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private Program program;

    @Convert(converter = CabinCodeConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 1, columnDefinition = "char(1)")
    private Cabin cabin;

    @Column(name = "mileage_cost", nullable = false, updatable = false)
    private int mileageCost;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "seats_remaining", updatable = false, columnDefinition = "smallint")
    private Integer seatsRemaining;

    @Column(nullable = false, updatable = false)
    private boolean nonstop;

    @Column(name = "observed_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant observedAt;

    @Column(name = "refreshed_at", updatable = false, columnDefinition = "timestamptz")
    private Instant refreshedAt;

    protected AvailabilityEntryEntity() {
    }

    public AvailabilityEntryEntity(
        SnapshotEntity snapshot,
        LocalDate departureDate,
        Cabin cabin,
        int mileageCost,
        Integer seatsRemaining,
        boolean nonstop,
        Instant observedAt,
        Instant refreshedAt
    ) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot cannot be null");
        this.departureDate = Objects.requireNonNull(departureDate, "departureDate cannot be null");
        this.program = snapshot.getProgram();
        this.cabin = Objects.requireNonNull(cabin, "cabin cannot be null");
        this.mileageCost = mileageCost;
        this.seatsRemaining = seatsRemaining;
        this.nonstop = nonstop;
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt cannot be null");
        this.refreshedAt = refreshedAt;
    }

    public Long getId() {
        return id;
    }

    public SnapshotEntity getSnapshot() {
        return snapshot;
    }

    public LocalDate getDepartureDate() {
        return departureDate;
    }

    public Program getProgram() {
        return program;
    }

    public Cabin getCabin() {
        return cabin;
    }

    public int getMileageCost() {
        return mileageCost;
    }

    public Integer getSeatsRemaining() {
        return seatsRemaining;
    }

    public boolean isNonstop() {
        return nonstop;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public Instant getRefreshedAt() {
        return refreshedAt;
    }
}
