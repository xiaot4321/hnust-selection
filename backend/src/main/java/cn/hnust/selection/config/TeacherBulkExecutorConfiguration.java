package cn.hnust.selection.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 有界线程池承接返回 202 的导师批量决定操作。 */
@Configuration
public class TeacherBulkExecutorConfiguration {
    @Bean(name = "teacherBulkTaskExecutor")
    public TaskExecutor teacherBulkTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("teacher-bulk-");
        executor.initialize();
        return executor;
    }
}
