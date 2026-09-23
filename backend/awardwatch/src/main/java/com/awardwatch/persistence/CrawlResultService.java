package com.awardwatch.persistence;

import com.awardwatch.domain.Snapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CrawlResultService {
    private final SnapshotService snapshots;
    private final CrawlStateRepository states;

    public CrawlResultService(SnapshotService snapshots, CrawlStateRepository states) {
        this.snapshots = snapshots;
        this.states = states;
    }

    /** Snapshot and scheduling history must commit together. No HTTP inside this transaction. */
    @Transactional
    public void record(Snapshot snapshot, int callsUsed, String source) {
        snapshots.record(snapshot, callsUsed, source);
        var query = snapshot.query();
        CrawlStateId id = new CrawlStateId(query.route().origin().value(),
            query.route().destination().value(), query.program(),
            query.departureDates().start(), query.departureDates().end());
        CrawlStateEntity state = states.findById(id).orElseGet(() -> new CrawlStateEntity(id));
        state.recordCrawl(snapshot.fetchedAt(), snapshot.entries().isEmpty());
        states.save(state);
    }
}
