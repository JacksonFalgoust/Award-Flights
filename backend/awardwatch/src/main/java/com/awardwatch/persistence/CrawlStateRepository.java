package com.awardwatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlStateRepository extends JpaRepository<CrawlStateEntity, CrawlStateId> {
}
