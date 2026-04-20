package com.jldaren.agent.ai.datascope.config;

import io.agentscope.core.shutdown.GracefulShutdownConfig;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 优雅停机配置
 * 
 * <p>功能：
 * <ul>
 *   <li>服务停止时等待进行中的请求完成</li>
 *   <li>保存智能体会话状态</li>
 *   <li>支持中断正在执行的工具调用</li>
 * </ul>
 */
@Configuration
public class ShutdownConfig {

    private static final Logger log = LoggerFactory.getLogger(ShutdownConfig.class);

    @Value("${agentscope.shutdown.timeout-seconds:30}")
    private int shutdownTimeoutSeconds;

    @Value("${agentscope.shutdown.enabled:true}")
    private boolean shutdownEnabled;

    @Bean
    public GracefulShutdownManager shutdownManager() {
        if (shutdownEnabled) {
            GracefulShutdownConfig config = new GracefulShutdownConfig(
                    Duration.ofSeconds(shutdownTimeoutSeconds),
                    io.agentscope.core.shutdown.PartialReasoningPolicy.SAVE
            );
            GracefulShutdownManager.getInstance().setConfig(config);
            log.info("Graceful shutdown enabled, timeout: {}s", shutdownTimeoutSeconds);
        } else {
            log.info("Graceful shutdown disabled");
        }
        return GracefulShutdownManager.getInstance();
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Initiating graceful shutdown...");
        GracefulShutdownManager.getInstance().performGracefulShutdown();
        log.info("Graceful shutdown completed");
    }

}
