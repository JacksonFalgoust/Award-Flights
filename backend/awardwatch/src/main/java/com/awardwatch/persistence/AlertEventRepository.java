package com.awardwatch.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertEventRepository extends JpaRepository<AlertEventEntity, Long> {

    boolean existsByWatch_IdAndEntry_IdAndSentAtAfter(Long watchId, Long entryId, Instant cutoff);
}
