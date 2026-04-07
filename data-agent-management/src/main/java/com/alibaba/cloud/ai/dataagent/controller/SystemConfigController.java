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
package com.alibaba.cloud.ai.dataagent.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统配置控制器
 */
@RestController
@RequestMapping("/api/system")
public class SystemConfigController {

	@Value("${app.system.name:AI Daren Data Agent}")
	private String systemName;

	/**
	 * 获取系统配置
	 */
	@GetMapping("/config")
	public Map<String, Object> getSystemConfig() {
		Map<String, Object> config = new HashMap<>();
		config.put("systemName", systemName);
		return config;
	}
}
