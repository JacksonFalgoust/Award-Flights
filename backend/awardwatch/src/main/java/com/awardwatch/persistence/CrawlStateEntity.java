package com.awardwatch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "crawl_state")
public class CrawlStateEntity {

    @EmbeddedId
    private CrawlStateId id;

    @Column(name = "last_crawled_at", columnDefinition = "timestamptz")
    private Instant lastCrawledAt;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "consecutive_empty", nullable = false, columnDefinition = "smallint")
    private int consecutiveEmpty;

    protected CrawlStateEntity() {
    }

    public CrawlStateEntity(CrawlStateId id) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
    }

    public CrawlStateId getId() {
        return id;
    }

    public Instant getLastCrawledAt() {
        return lastCrawledAt;
    }

    public int getConsecutiveEmpty() {
        return consecutiveEmpty;
    }

    public void recordCrawl(Instant crawledAt, boolean empty) {
        lastCrawledAt = Objects.requireNonNull(crawledAt, "crawledAt cannot be null");
        consecutiveEmpty = empty
            ? Math.min(consecutiveEmpty + 1, Short.MAX_VALUE)
            : 0;
    }
}
