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
package com.jldaren.agent.ai.datascope.tool;

import io.agentscope.core.tool.Toolkit;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心
 * <p>
 * Spring 启动时自动扫描所有含 @Tool 注解的 Bean，
 * 收集工具元信息（名称、描述、参数），支持：
 * 1. 查询所有已注册工具（供前端选择）
 * 2. 按 Agent 粒度构建 Toolkit（只注册指定工具）
 */
@Slf4j
@Component
public class ToolRegistry implements BeanPostProcessor {

    /** 工具名称 -> 工具元信息 */
    private final Map<String, ToolMeta> toolMetaMap = new ConcurrentHashMap<>();

    /** 工具名称 -> Bean 实例 */
    private final Map<String, Object> toolBeanMap = new ConcurrentHashMap<>();

    /** 工具名称 -> 方法 */
    private final Map<String, Method> toolMethodMap = new ConcurrentHashMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        // 扫描 Bean 中所有带 @Tool 注解的方法
        ReflectionUtils.doWithMethods(bean.getClass(), method -> {
            io.agentscope.core.tool.Tool toolAnnotation =
                    method.getAnnotation(io.agentscope.core.tool.Tool.class);
            if (toolAnnotation != null) {
                String toolName = toolAnnotation.name();
                String toolDesc = toolAnnotation.description();

                // 收集参数信息
                List<ToolParamMeta> params = new ArrayList<>();
                for (java.lang.reflect.Parameter param : method.getParameters()) {
                    io.agentscope.core.tool.ToolParam paramAnnotation =
                            param.getAnnotation(io.agentscope.core.tool.ToolParam.class);
                    if (paramAnnotation != null) {
                        params.add(new ToolParamMeta(
                                paramAnnotation.name(),
                                paramAnnotation.description(),
                                paramAnnotation.required(),
                                param.getType().getSimpleName()
                        ));
                    }
                }

                ToolMeta meta = new ToolMeta(toolName, toolDesc, bean.getClass().getSimpleName(), params);
                toolMetaMap.put(toolName, meta);
                toolBeanMap.put(toolName, bean);
                toolMethodMap.put(toolName, method);

                log.info("✅Registered tool: {} -> {} (bean: {})", toolName, toolDesc, beanName);
            }
        }, method -> method.isAnnotationPresent(io.agentscope.core.tool.Tool.class));

        return bean;
    }

    /**
     * 获取所有已注册工具的元信息
     */
    public List<ToolMeta> getAllTools() {
        return new ArrayList<>(toolMetaMap.values());
    }

    /**
     * 构建全量 Toolkit（注册所有工具）
     */
    public Toolkit buildFullToolkit() {
        Toolkit toolkit = new Toolkit();
        log.info("🔧 buildFullToolkit: toolBeanMap contains {} tools: {}", toolBeanMap.size(), toolBeanMap.keySet());
        for (Map.Entry<String, Object> entry : toolBeanMap.entrySet()) {
            toolkit.registerTool(entry.getValue());
        }
        log.info("🔧 buildFullToolkit: toolkit now has {} tools: {}", toolkit.getToolNames().size(), toolkit.getToolNames());
        return toolkit;
    }

    /**
     * 按 Agent 粒度构建 Toolkit（只注册指定名称的工具）
     *
     * @param toolNames 工具名称列表（逗号分隔）
     * @return Toolkit 实例
     */
    public Toolkit buildToolkit(String toolNames) {
        Toolkit toolkit = new Toolkit();

        if (toolNames == null || toolNames.isBlank()) {
            // 没有配置则使用全量工具
            return buildFullToolkit();
        }

        log.info("🔧 buildToolkit: toolNames='{}', available tools={}", toolNames, toolBeanMap.keySet());

        List<String> names = Arrays.stream(toolNames.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        int registered = 0;
        for (String name : names) {
            Object bean = toolBeanMap.get(name);
            if (bean != null) {
                toolkit.registerTool(bean);
                registered++;
            } else {
                log.warn("Tool '{}' not found in registry, skipping", name);
            }
        }

        if (registered == 0) {
            log.warn("No tools registered for toolNames='{}', falling back to full toolkit", toolNames);
            return buildFullToolkit();
        }

        log.info("Built toolkit with {}/{} tools: {}", registered, names.size(), names);
        return toolkit;
    }

    /**
     * 工具元信息
     */
    @Data
    public static class ToolMeta {
        private final String name;
        private final String description;
        private final String provider;
        private final List<ToolParamMeta> params;
    }

    /**
     * 工具参数元信息
     */
    @Data
    public static class ToolParamMeta {
        private final String name;
        private final String description;
        private final boolean required;
        private final String type;
    }
}
