package com.awardwatch.ingest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "crawl", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrawlSchedulingConfiguration {
}
