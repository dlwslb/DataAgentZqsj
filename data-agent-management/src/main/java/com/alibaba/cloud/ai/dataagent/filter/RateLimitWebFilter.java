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
package com.alibaba.cloud.ai.dataagent.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * WebFlux 版本的 API 速率限制过滤器
 * <p>
 * 配置项：
 * - app.rate-limit.enabled: 是否启用限流（默认 true）
 * - app.rate-limit.max-requests-per-minute: 每分钟最大请求数（默认 30）
 * - app.rate-limit.llm-paths: 需要限流的路径前缀（逗号分隔，默认 /api/agent,/api/conversation）
 */
@Slf4j
@Component
@Order(1)
public class RateLimitWebFilter implements WebFilter {

    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${app.rate-limit.max-requests-per-minute:30}")
    private long maxRequestsPerMinute;

    @Value("${app.rate-limit.llm-paths:/api/agent,/api/conversation}")
    private String llmPaths;

    private final Map<String, RequestCounter> counters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        String requestPath = exchange.getRequest().getURI().getPath();

        if (!isLlmPath(requestPath)) {
            return chain.filter(exchange);
        }

        String clientId = getClientId(exchange);
        RequestCounter counter = counters.computeIfAbsent(clientId, k -> new RequestCounter());

        if (counter.tryAcquire(maxRequestsPerMinute)) {
            return chain.filter(exchange);
        }

        log.warn("Rate limit exceeded for client: {}, path: {}", clientId, requestPath);
        exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        try {
            byte[] body = objectMapper.writeValueAsBytes(
                    Map.of("code", 429, "message", "请求过于频繁，请稍后再试"));
            return exchange.getResponse().writeWith(Mono.just(
                    exchange.getResponse().bufferFactory().wrap(body)));
        } catch (Exception e) {
            return Mono.empty();
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

    private String getClientId(ServerWebExchange exchange) {
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (userId != null && !userId.isEmpty()) {
            return "user:" + userId;
        }
        String xfHeader = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xfHeader != null && !xfHeader.isEmpty()) {
            return "ip:" + xfHeader.split(",")[0].trim();
        }
        String remoteAddr = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
        return "ip:" + remoteAddr;
    }

    /**
     * 滑动窗口计数器（每分钟窗口）
     */
    static class RequestCounter {
        private volatile long windowStart = System.currentTimeMillis();
        private final LongAdder count = new LongAdder();

        synchronized boolean tryAcquire(long maxPerMinute) {
            long now = System.currentTimeMillis();
            if (now >= windowStart + 60_000) {
                windowStart = now;
                count.reset();
            }

            if (count.sum() >= maxPerMinute) {
                return false;
            }

            count.increment();
            return true;
        }
    }
}
