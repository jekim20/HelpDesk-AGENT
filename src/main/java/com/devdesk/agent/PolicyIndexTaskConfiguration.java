package com.devdesk.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Keeps external embedding calls off the application startup thread. */
@Configuration
public class PolicyIndexTaskConfiguration {

    @Bean(name = "policyIndexExecutor")
    ThreadPoolTaskExecutor policyIndexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("policy-index-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
