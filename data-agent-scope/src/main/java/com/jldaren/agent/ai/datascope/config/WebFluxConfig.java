/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
