package com.awardwatch.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AvailabilityEntryRepository extends JpaRepository<AvailabilityEntryEntity, Long> {

    List<AvailabilityEntryEntity> findAllBySnapshot_Id(Long snapshotId);
}
