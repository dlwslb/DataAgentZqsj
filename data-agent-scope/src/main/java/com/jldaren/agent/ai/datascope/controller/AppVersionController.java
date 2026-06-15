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
package com.jldaren.agent.ai.datascope.controller;

import com.jldaren.agent.ai.datascope.entity.AppVersion;
import com.jldaren.agent.ai.datascope.service.AppVersionService;
import com.jldaren.agent.ai.datascope.vo.ApiResponse;
import com.jldaren.agent.ai.datascope.vo.AppVersionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;

/**
 * App Version Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/scope/app")
@RequiredArgsConstructor
@Tag(name = "App Version", description = "App 版本检查接口")
public class AppVersionController {

    private final AppVersionService appVersionService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    @GetMapping("/version")
    @Operation(summary = "获取App最新版本", description = "根据平台类型获取最新版本信息")
    public ApiResponse<AppVersionVO> getVersion(@RequestParam String platform) {
        log.info("获取 App 版本信息: platform={}", platform);

        // 验证平台参数
        if (platform == null || platform.isBlank()) {
            return ApiResponse.error("平台类型不能为空");
        }

        String normalizedPlatform = platform.toLowerCase().trim();
        if (!normalizedPlatform.matches("^(android|ios|web)$")) {
            return ApiResponse.error("不支持的平台类型，仅支持 android/ios/web");
        }

        AppVersion appVersion = appVersionService.getVersionInfo(normalizedPlatform);
        if (appVersion == null) {
            return ApiResponse.error("未找到对应平台的版本信息");
        }

        AppVersionVO vo = AppVersionVO.builder()
                .latestVersion(appVersion.getLatestVersion())
                .versionCode(appVersion.getVersionCode())
                .platform(appVersion.getPlatform())
                .forceUpdate(appVersion.getForceUpdate() != null && appVersion.getForceUpdate() == 1)
                .downloadUrl(appVersion.getDownloadUrl())
                .releaseNotes(appVersion.getReleaseNotes())
                .minVersion(appVersion.getMinVersion())
                .publishedAt(appVersion.getPublishedAt() != null
                        ? appVersion.getPublishedAt().format(DATE_FORMATTER) : null)
                .build();

        return ApiResponse.success("获取版本信息成功", vo);
    }

}






