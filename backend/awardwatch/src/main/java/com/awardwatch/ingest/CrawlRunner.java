package com.awardwatch.ingest;

import com.awardwatch.domain.AvailabilitySourceException;
import com.awardwatch.domain.Program;
import com.awardwatch.domain.RouteQuery;
import com.awardwatch.domain.Snapshot;
import com.awardwatch.persistence.CrawlResultService;
import com.awardwatch.persistence.SnapshotService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CrawlRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrawlRunner.class);
    private static final String SOURCE = "seats.aero";
    private final SeatsAeroSource source;
    private final QuotaBudgeter quota;
    private final CrawlResultService results;
    private final SnapshotService snapshots;
    private final Clock clock;
    private final ResponseCache cache;
    private final RouteLock locks;

    public CrawlRunner(SeatsAeroSource source, QuotaBudgeter quota, CrawlResultService results,
                       SnapshotService snapshots, Clock clock, ResponseCache cache, RouteLock locks) {
        this.source = source;
        this.quota = quota;
        this.results = results;
        this.snapshots = snapshots;
        this.clock = clock;
        this.cache = cache;
        this.locks = locks;
    }

    public boolean supports(Program program) {
        return source.supports(program);
    }

    /** Returns false when quota is exhausted, telling the scheduler to stop the tick. */
    public boolean crawl(RouteQuery query) {
        if (cache.get(query).isPresent()) return true;
        var acquired = locks.tryAcquire(query.route());
        if (acquired.isEmpty()) return true;
        try (RouteLock.Lease lease = acquired.get()) {
            // Another worker may have filled the cache between the first read and acquisition.
            if (cache.get(query).isPresent()) return true;
            return crawlLocked(query, lease);
        }
    }

    private boolean crawlLocked(RouteQuery query, RouteLock.Lease lease) {
        CallBudget budget = new CallBudget(lease);
        Snapshot snapshot;
        try {
            snapshot = source.fetch(query, budget);
        } catch (QuotaExhaustedException exception) {
            if (budget.callsUsed > 0) {
                lease.ensureHeld();
                snapshots.recordFailure(query, clock.instant(), budget.callsUsed, SOURCE);
            }
            LOGGER.info("Stopping crawl tick: quota unavailable for {}", query);
            return false;
        } catch (AvailabilitySourceException exception) {
            lease.ensureHeld();
            snapshots.recordFailure(query, clock.instant(), budget.callsUsed, SOURCE);
            LOGGER.warn("Crawl failed for {}: {}", query, exception.getMessage());
            return true;
        }
        lease.ensureHeld();
        results.record(snapshot, budget.callsUsed, SOURCE);
        lease.ensureHeld();
        cache.put(snapshot);
        return true;
    }

    private final class CallBudget implements SeatsAeroSource.CallAccounting {
        private final RouteLock.Lease lease;
        private int callsUsed;
        private LocalDate reservationDate;

        private CallBudget(RouteLock.Lease lease) {
            this.lease = lease;
        }

        @Override
        public void beforeCall() {
            lease.ensureHeld();
            // Persistence uses SMALLINT; refuse an unrecordable fetch before spending.
            if (callsUsed == Short.MAX_VALUE) {
                throw new IllegalStateException("A crawl exceeded the maximum recordable call count");
            }
            reservationDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (!quota.tryReserve(reservationDate, 1)) throw new QuotaExhaustedException();
            callsUsed++;
        }

        @Override
        public void transportFailed() {
            quota.refund(reservationDate, 1);
            callsUsed--;
        }
    }

    private static final class QuotaExhaustedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
