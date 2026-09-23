package com.awardwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.awardwatch.persistence.CrawlStateRepository;
import com.awardwatch.persistence.WatchRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class CrawlSchedulingConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(CrawlSchedulingConfiguration.class)
        .withBean(CrawlScheduler.class, () -> new CrawlScheduler(
            mock(WatchRepository.class), mock(CrawlRunner.class),
            mock(CrawlStateRepository.class), Clock.systemUTC()));

    @Test
    void registersOneScheduledTaskByDefault() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            var processor = application.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(processor.getScheduledTasks()).hasSize(1);
        });
    }

    @Test
    void disabledSchedulingDoesNotRegisterBackgroundTasks() {
        context.withPropertyValues("crawl.enabled=false").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }
}
