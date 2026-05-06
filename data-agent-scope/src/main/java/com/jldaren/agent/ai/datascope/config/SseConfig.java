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

import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

//✅ 安全（防OOM/防泄漏） ✅ 可观测（线程名+监控） ✅ 按业务隔离
@Configuration
public class SseConfig {

    // ==================== RAG 线程池 ====================
    // 场景：向量检索+LLM调用，典型IO密集型，允许短暂排队
    @Bean(name = "ragExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor ragExecutor() {
        return new ThreadPoolExecutor(
                8,                      // core: 按 2*CPU 核数预估
                32,                     // max: 突发流量时弹性扩容
                60L, TimeUnit.SECONDS,  // 空闲线程60秒回收
                new ArrayBlockingQueue<>(500),  // 🔑 有界队列！防内存溢出
                new ThreadFactoryBuilder().setNameFormat("rag-%d").setDaemon(true).build(),
                new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满时由调用线程执行，天然背压
        );
    }

    // ==================== 聊天保存线程池 ====================
    // 场景：写库操作，数据一致性优先，宁可慢不能丢
    @Bean(name = "chatSaveExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor chatSaveExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4,                      // core: 保守起步，避免数据库连接池打满
                16,                     // max: 高峰期可扩容
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1000), // 队列稍大，缓冲写库压力
                new ThreadFactoryBuilder().setNameFormat("chat-save-%d").setDaemon(true).build(),
                new ThreadPoolExecutor.CallerRunsPolicy()  // 🔑 关键：任务不丢，反向压降提交速度
        );
        executor.allowCoreThreadTimeOut(true); // 空闲时回收核心线程，节省资源
        return executor;
    }
}
