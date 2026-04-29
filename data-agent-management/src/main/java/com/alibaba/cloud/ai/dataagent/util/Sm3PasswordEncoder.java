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
package com.alibaba.cloud.ai.dataagent.util;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.util.encoders.Hex;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * SM3国密密码编码器
 * 符合中国国家标准GM/T 0004-2012
 * 
 * 双重加密机制：
 * 1. 前端：明文 -> SM3哈希
 * 2. 后端：SM3哈希 + 随机盐 -> 最终存储哈希
 * 
 * 存储格式：$sm3${salt}${finalHash}
 */
@Slf4j
public class Sm3PasswordEncoder {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final int SALT_LENGTH = 16;

    private static final String PREFIX = "$sm3$";

    /**
     * 生成随机盐值
     */
    public static String generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        return Hex.toHexString(salt);
    }

    /**
     * SM3哈希算法
     * 符合GM/T 0004-2012标准
     */
    public static String sm3Hash(String data) {
        if (data == null) {
            return null;
        }
        SM3Digest digest = new SM3Digest();
        byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
        digest.update(dataBytes, 0, dataBytes.length);
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return Hex.toHexString(result);
    }

    /**
     * 编码密码（后端二次加密）
     * @param preHashedPassword 前端SM3加密后的密码
     * @return 格式: $sm3${salt}${finalHash}
     */
    public static String encode(String preHashedPassword) {
        String salt = generateSalt();
        String finalHash = sm3Hash(salt + preHashedPassword);
        return PREFIX + salt + "$" + finalHash;
    }

    /**
     * 验证密码
     * @param preHashedPassword 前端传来的SM3加密密码
     * @param encodedPassword 数据库中存储的加密密码
     * @return 是否匹配
     */
    public static boolean matches(String preHashedPassword, String encodedPassword) {
        if (encodedPassword == null || !encodedPassword.startsWith(PREFIX)) {
            log.warn("Invalid encoded password format");
            return false;
        }

        try {
            String withoutPrefix = encodedPassword.substring(PREFIX.length());
            String[] parts = withoutPrefix.split("\\$", 2);

            if (parts.length != 2) {
                log.warn("Invalid password format: missing salt or hash");
                return false;
            }

            String salt = parts[0];
            String expectedHash = parts[1];
            String actualHash = sm3Hash(salt + preHashedPassword);

            return actualHash.equals(expectedHash);
        } catch (Exception e) {
            log.error("Password verification failed", e);
            return false;
        }
    }

    /**
     * 检查密码格式是否为SM3格式
     */
    public static boolean isSm3Format(String encodedPassword) {
        return encodedPassword != null && encodedPassword.startsWith(PREFIX);
    }

}
