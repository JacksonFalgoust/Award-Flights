package com.awardwatch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Objects;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "watch")
public class WatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUserEntity user;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String origin;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String destination;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] cabins;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] programs;

    @Column(name = "max_mileage")
    private Integer maxMileage;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "min_seats", nullable = false, columnDefinition = "smallint")
    private int minSeats = 1;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    protected WatchEntity() {
    }

    public WatchEntity(
        AppUserEntity user,
        String origin,
        String destination,
        LocalDate dateFrom,
        LocalDate dateTo,
        String[] cabins,
        String[] programs,
        Integer maxMileage,
        int minSeats
    ) {
        this.user = Objects.requireNonNull(user, "user cannot be null");
        this.origin = Objects.requireNonNull(origin, "origin cannot be null");
        this.destination = Objects.requireNonNull(destination, "destination cannot be null");
        this.dateFrom = Objects.requireNonNull(dateFrom, "dateFrom cannot be null");
        this.dateTo = Objects.requireNonNull(dateTo, "dateTo cannot be null");
        this.cabins = copyRequired(cabins, "cabins cannot be null");
        this.programs = copy(programs);
        this.maxMileage = maxMileage;
        this.minSeats = minSeats;
    }

    public Long getId() {
        return id;
    }

    public AppUserEntity getUser() {
        return user;
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

    public String[] getCabins() {
        return copy(cabins);
    }

    public String[] getPrograms() {
        return copy(programs);
    }

    public Integer getMaxMileage() {
        return maxMileage;
    }

    public int getMinSeats() {
        return minSeats;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    private static String[] copyRequired(String[] values, String message) {
        Objects.requireNonNull(values, message);
        return Arrays.copyOf(values, values.length);
    }

    private static String[] copy(String[] values) {
        return values == null ? null : Arrays.copyOf(values, values.length);
    }
}
