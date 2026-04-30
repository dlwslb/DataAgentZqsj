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
package com.jldaren.agent.ai.datascope.service;

import com.jldaren.agent.ai.datascope.util.JwtUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户信息服务
 * 提供本地Token解析方式获取用户信息（无需远程调用）
 */
@Slf4j
@Service
public class UserInfoService {

    private final JwtUtil jwtUtil;

    public UserInfoService(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /**
     * 直接从Token解析获取用户ID（无需远程调用）
     */
    public Long getUserIdFromToken(String token) {
        try {
            return jwtUtil.getUserIdFromToken(token);
        } catch (Exception e) {
            log.error("解析Token获取用户ID失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 直接从Token解析获取租户ID（无需远程调用）
     */
    public Long getTenantIdFromToken(String token) {
        try {
            return jwtUtil.getTenantIdFromToken(token);
        } catch (Exception e) {
            log.error("解析Token获取租户ID失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 直接从Token解析获取角色（无需远程调用）
     */
    public String getRoleFromToken(String token) {
        try {
            return jwtUtil.getRoleFromToken(token);
        } catch (Exception e) {
            log.error("解析Token获取角色失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 直接从Token解析获取用户名（无需远程调用）
     */
    public String getUsernameFromToken(String token) {
        try {
            return jwtUtil.getUsernameFromToken(token);
        } catch (Exception e) {
            log.error("解析Token获取用户名失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 直接从Token解析获取用户基本信息（无需远程调用）
     * 包含: id, username
     */
    public UserInfo getBasicUserInfo(String token) {
        try {
            Long userId = jwtUtil.getUserIdFromToken(token);
            String username = jwtUtil.getUsernameFromToken(token);
            
            UserInfo info = new UserInfo();
            info.setId(userId);
            info.setUsername(username);
            return info;
        } catch (Exception e) {
            log.error("解析Token获取用户信息失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从Token解析获取完整用户信息（无需远程调用）
     * 包含: id, username, tenantId, role
     */
    public TokenUserInfo getUserInfoFromToken(String token) {
        try {
            Long userId = jwtUtil.getUserIdFromToken(token);
            String username = jwtUtil.getUsernameFromToken(token);
            Long tenantId = jwtUtil.getTenantIdFromToken(token);
            String role = jwtUtil.getRoleFromToken(token);
            
            TokenUserInfo info = new TokenUserInfo();
            info.setId(userId);
            info.setUsername(username);
            info.setTenantId(tenantId);
            info.setRole(role);
            return info;
        } catch (Exception e) {
            log.error("解析Token获取用户信息失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 用户基本信息
     * 包含: id, username
     */
    @Data
    public static class UserInfo {
        private Long id;
        private String username;
    }

    /**
     * Token中的完整用户信息
     * 包含: id, username, tenantId, role
     */
    @Data
    public static class TokenUserInfo {
        private Long id;
        private String username;
        private Long tenantId;
        private String role;

        public boolean isSuperAdmin() {
            return "super_admin".equals(role);
        }

        public boolean isAdmin() {
            return "admin".equals(role) || "super_admin".equals(role);
        }
    }
}
