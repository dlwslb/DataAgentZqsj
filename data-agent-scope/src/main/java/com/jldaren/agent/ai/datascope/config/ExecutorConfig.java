package com.jldaren.agent.ai.datascope.config;

import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.*;

@Configuration
public class ExecutorConfig {

    @Bean("chatSaveExecutor")
    public ExecutorService chatSaveExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 16, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1000),
                new ThreadFactoryBuilder().setNameFormat("chat-save-%d").build(),
                // 🔧 优化点 #9：使用 CallerRunsPolicy 避免任务静默丢弃
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean("ragExecutor")
    public ExecutorService ragExecutor() {
        // RAG 任务通常 IO 密集，可适当增加核心线程数
        return Executors.newFixedThreadPool(8);
    }

    @Bean("heartbeatExecutor")
    public ScheduledExecutorService heartbeatExecutor() {
        return Executors.newScheduledThreadPool(2);
    }
}