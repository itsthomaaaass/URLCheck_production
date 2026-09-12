package com.urlcheck.monitor;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * One thread for outbound checks: a slow target cannot make the backend open
 * dozens of connections at once, and CallerRunsPolicy applies back pressure
 * instead of dropping or queueing without bound.
 */
@Configuration
public class CheckConfig {

    @Bean("checkExecutor")
    public ThreadPoolTaskExecutor checkExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("url-check-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}