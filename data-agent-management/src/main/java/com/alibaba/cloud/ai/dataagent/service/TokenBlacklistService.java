package com.alibaba.cloud.ai.dataagent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "token:blacklist:";
    
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public Mono<Void> addToBlacklist(String jti, long expirationSeconds) {
        String key = BLACKLIST_PREFIX + jti;
        return redisTemplate.opsForValue()
                .set(key, "1", Duration.ofSeconds(expirationSeconds))
                .then();
    }

    public Mono<Boolean> isBlacklisted(String jti) {
        String key = BLACKLIST_PREFIX + jti;
        return redisTemplate.hasKey(key);
    }
}
