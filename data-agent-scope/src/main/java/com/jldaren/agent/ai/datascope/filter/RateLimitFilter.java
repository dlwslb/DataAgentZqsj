/*
 * Copyright 2024-2026 the original author or authors.
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
package com.jldaren.agent.ai.datascope.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 基于 IP 的 API 速率限制过滤器（令牌桶算法简化版）
 * <p>
 * 针对 LLM 调用接口做限流，防止恶意请求打爆 DashScope 配额。
 * 配置项：
 * - app.rate-limit.enabled: 是否启用限流（默认 true）
 * - app.rate-limit.max-requests-per-minute: 每分钟最大请求数（默认 30）
 * - app.rate-limit.llm-paths: 需要限流的路径前缀（逗号分隔，默认 /api/v1/chat,/api/scope/agent）
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter implements Filter {

    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${app.rate-limit.max-requests-per-minute:30}")
    private long maxRequestsPerMinute;

    @Value("${app.rate-limit.llm-paths:/api/v1/chat,/api/scope/agent}")
    private String llmPaths;

    private final Map<String, RequestCounter> counters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String requestPath = httpRequest.getRequestURI();

        // 只对 LLM 相关路径限流
        if (!isLlmPath(requestPath)) {
            chain.doFilter(request, response);
            return;
        }

        String clientId = getClientId(httpRequest);
        RequestCounter counter = counters.computeIfAbsent(clientId, k -> new RequestCounter());

        if (counter.tryAcquire(maxRequestsPerMinute)) {
            chain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded for client: {}, path: {}", clientId, requestPath);
            sendRateLimitResponse((HttpServletResponse) response);
        }

        // 清理过期的计数器（每 100 次请求清理一次）
        if (counter.totalCount() % 100 == 0) {
            cleanupExpiredCounters();
        }
    }

    private boolean isLlmPath(String path) {
        if (llmPaths == null || llmPaths.isEmpty()) {
            return false;
        }
        for (String prefix : llmPaths.split(",")) {
            if (path.startsWith(prefix.trim())) {
                return true;
            }
        }
        return false;
    }

    private String getClientId(HttpServletRequest request) {
        // 优先使用认证用户 ID，否则使用 IP
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isEmpty()) {
            return "user:" + userId;
        }
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader != null && !xfHeader.isEmpty()) {
            return "ip:" + xfHeader.split(",")[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private void sendRateLimitResponse(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Map.of("code", 429, "message", "请求过于频繁，请稍后再试")));
    }

    private void cleanupExpiredCounters() {
        long now = System.currentTimeMillis();
        counters.entrySet().removeIf(entry ->
                now - entry.getValue().windowStart > 120_000 // 超过2分钟未活跃的清理掉
        );
    }

    /**
     * 滑动窗口计数器（每分钟窗口）
     */
    static class RequestCounter {
        private volatile long windowStart = System.currentTimeMillis();
        private final LongAdder count = new LongAdder();
        private final AtomicLong lastResetTime = new AtomicLong(System.currentTimeMillis());

        boolean tryAcquire(long maxPerMinute) {
            long now = System.currentTimeMillis();
            long windowEnd = windowStart + 60_000;

            if (now >= windowEnd) {
                // 新窗口
                synchronized (this) {
                    if (now >= windowStart + 60_000) {
                        windowStart = now;
                        count.reset();
                    }
                }
            }

            if (count.sum() >= maxPerMinute) {
                return false;
            }

            count.increment();
            lastResetTime.set(now);
            return true;
        }

        long totalCount() {
            return count.sum();
        }
    }
}
