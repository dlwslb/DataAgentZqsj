package com.jldaren.agent.ai.datascope.util;

/**
 * 认证信息（从 JWT 解析后得到）
 *
 * @param userId   用户 ID
 * @param tenantId 租户 ID
 * @param username 用户名（token subject）
 * @param role     角色（user / admin）
 */
public record AuthInfo(Long userId, Long tenantId, String username, String role) {
}
