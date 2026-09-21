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
import java.util.Objects;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "alert_event")
public class AlertEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "watch_id", nullable = false, updatable = false)
    private WatchEntity watch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false, updatable = false)
    private AvailabilityEntryEntity entry;

    @CreationTimestamp
    @Column(name = "sent_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant sentAt;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String channel;

    protected AlertEventEntity() {
    }

    public AlertEventEntity(WatchEntity watch, AvailabilityEntryEntity entry, String channel) {
        this.watch = Objects.requireNonNull(watch, "watch cannot be null");
        this.entry = Objects.requireNonNull(entry, "entry cannot be null");
        this.channel = Objects.requireNonNull(channel, "channel cannot be null");
    }

    public Long getId() {
        return id;
    }

    public WatchEntity getWatch() {
        return watch;
    }

    public AvailabilityEntryEntity getEntry() {
        return entry;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getChannel() {
        return channel;
    }
}
