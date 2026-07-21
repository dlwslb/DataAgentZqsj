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
package com.jldaren.agent.ai.datascope2.auth;

/**
 * 当前请求的认证上下文（从 JWT 解出后存在这里）
 *
 * <p>用 ThreadLocal 存：WebFlux + 2.0 Tool 调用链跨线程时会有问题，
 * <p>但 Tool 接收 tenantId 作为必填参数，ChatController 在调 agent 前会把
 * <p>tenantId 注入到 user message 的环境信息里，LLM 看到后会主动给 Tool 传。
 */
public class AuthContext {

    private static final ThreadLocal<AuthInfo> CURRENT = new ThreadLocal<>();

    public static void set(AuthInfo info) {
        CURRENT.set(info);
    }

    public static AuthInfo get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 认证信息
     */
    public record AuthInfo(Long userId, Long tenantId, String province, String username, String role) {}
}
