package com.awardwatch.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchRepository extends JpaRepository<WatchEntity, Long> {

    List<WatchEntity> findAllByActiveTrueOrderByCreatedAtAsc();

    List<WatchEntity> findAllByUser_IdOrderByCreatedAtAsc(Long userId);
}
