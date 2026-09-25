package com.awardwatch.persistence;

import com.awardwatch.domain.Program;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "snapshot")
public class SnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3, columnDefinition = "char(3)")
    private String origin;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3, columnDefinition = "char(3)")
    private String destination;

    @Column(name = "date_from", nullable = false, updatable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false, updatable = false)
    private LocalDate dateTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private Program program;

    @Column(name = "observed_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant observedAt;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "api_calls_used", nullable = false, updatable = false, columnDefinition = "smallint")
    private int apiCallsUsed;

    @Column(nullable = false, updatable = false)
    private boolean succeeded;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String source;

    @OneToMany(mappedBy = "snapshot", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
    private List<AvailabilityEntryEntity> entries = new ArrayList<>();

    protected SnapshotEntity() {
    }

    public SnapshotEntity(
        String origin,
        String destination,
        LocalDate dateFrom,
        LocalDate dateTo,
        Program program,
        Instant observedAt,
        int apiCallsUsed,
        boolean succeeded,
        String source
    ) {
        this.origin = Objects.requireNonNull(origin, "origin cannot be null");
        this.destination = Objects.requireNonNull(destination, "destination cannot be null");
        this.dateFrom = Objects.requireNonNull(dateFrom, "dateFrom cannot be null");
        this.dateTo = Objects.requireNonNull(dateTo, "dateTo cannot be null");
        this.program = Objects.requireNonNull(program, "program cannot be null");
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt cannot be null");
        this.apiCallsUsed = apiCallsUsed;
        this.succeeded = succeeded;
        this.source = Objects.requireNonNull(source, "source cannot be null");
    }

    public Long getId() {
        return id;
    }

    public String getOrigin() {
        return origin;
    }

    public String getDestination() {
        return destination;
    }

    public LocalDate getDateFrom() {
        return dateFrom;
    }

    public LocalDate getDateTo() {
        return dateTo;
    }

    public Program getProgram() {
        return program;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public int getApiCallsUsed() {
        return apiCallsUsed;
    }

    public boolean isSucceeded() {
        return succeeded;
    }

    public String getSource() {
        return source;
    }

    public List<AvailabilityEntryEntity> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public void addEntry(AvailabilityEntryEntity entry) {
        Objects.requireNonNull(entry, "entry cannot be null");
        if (entry.getSnapshot() != this) {
            throw new IllegalArgumentException("entry belongs to a different snapshot");
        }
        if (!succeeded) {
            throw new IllegalStateException("failed snapshots cannot contain availability entries");
        }
        entries.add(entry);
    }
}
