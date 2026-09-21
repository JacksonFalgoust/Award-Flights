package com.awardwatch.persistence;

import com.awardwatch.domain.Program;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnapshotRepository extends JpaRepository<SnapshotEntity, Long> {

    List<SnapshotEntity> findTop2ByOriginAndDestinationAndProgramAndSucceededTrueOrderByObservedAtDescIdDesc(
        String origin,
        String destination,
        Program program
    );
}
