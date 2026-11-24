package gg.modl.backend.database.sql;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import gg.modl.backend.infrastructure.config.AsyncConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

class TaskSchedulerTypeTest {
    @Test
    void scheduledJobsRunOnTheVirtualThreadSchedulerWhenVirtualThreadsAreEnabled() {
        new ApplicationContextRunner()
            .withPropertyValues("spring.threads.virtual.enabled=true")
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(AsyncConfig.class)
            .run(context -> assertInstanceOf(SimpleAsyncTaskScheduler.class, context.getBean(TaskScheduler.class)));
    }
}
