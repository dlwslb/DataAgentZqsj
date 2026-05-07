package com.jldaren.agent.ai.datascope.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * WebFlux 配置
 * 为阻塞操作提供专用线程池
 */
@Slf4j
@Configuration
public class WebFluxConfig {

    /**
     * 阻塞操作专用调度器
     * boundedElastic: 按需创建线程，空闲时回收，适合 JDBC/IO 阻塞操作
     */
    @Bean
    public Scheduler blockingScheduler() {
        log.info("✅WebFlux blocking scheduler initialized");
        return Schedulers.boundedElastic();
    }
}
