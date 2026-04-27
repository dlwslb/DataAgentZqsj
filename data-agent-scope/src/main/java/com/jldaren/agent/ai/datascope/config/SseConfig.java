package com.jldaren.agent.ai.datascope.config;

import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.*;

@Configuration
@EnableScheduling
public class SseConfig {

    @Bean(name = "heartbeatExecutor", destroyMethod = "shutdown")
    public ScheduledExecutorService heartbeatExecutor() {
        return Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("heartbeat-%d")
                        .setDaemon(true)
                        .build());
    }

    @Bean(name = "ragExecutor", destroyMethod = "shutdown")
    public ExecutorService ragExecutor() {
        return Executors.newFixedThreadPool(10,
                new ThreadFactoryBuilder()
                        .setNameFormat("rag-%d")
                        .setDaemon(true)
                        .build());
    }
}