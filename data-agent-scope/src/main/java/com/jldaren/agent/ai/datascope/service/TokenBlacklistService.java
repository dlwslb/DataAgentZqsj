package com.jldaren.agent.ai.datascope.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "token:blacklist:";
    
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public Mono<Boolean> isBlacklisted(String jti) {
        String key = BLACKLIST_PREFIX + jti;
        return redisTemplate.hasKey(key);
    }

    /**
     * 同步检查 Token 是否在黑名单中（用于 Servlet Filter）
     */
    public boolean isBlacklistedSync(String jti) {
        String key = BLACKLIST_PREFIX + jti;
        Boolean exists = redisTemplate.hasKey(key).block();
        return exists != null && exists;
    }
}
