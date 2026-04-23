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

import com.jldaren.agent.ai.datascope.entity.AgentScopeAgent;
import com.jldaren.agent.ai.datascope.entity.ChatMessage;
import com.jldaren.agent.ai.datascope.entity.ChatSession;
import com.jldaren.agent.ai.datascope.mapper.AgentScopeAgentMapper;
import com.jldaren.agent.ai.datascope.mapper.ChatMessageMapper;
import com.jldaren.agent.ai.datascope.mapper.ChatSessionMapper;
import com.jldaren.agent.ai.datascope.registry.AgentScopeRegistry;
import com.jldaren.agent.ai.datascope.service.AgentScopeAgentManager;
import com.jldaren.agent.ai.datascope.vo.ApiResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AgentScope Agent Controller
 * 提供 Agent 的 CRUD 和聊天接口
 */
@Slf4j
@RestController
@RequestMapping("/api/scope/agent")
@RequiredArgsConstructor
@Tag(name = "AgentScope Agent", description = "AgentScope 智能体管理接口")
public class AgentScopeController {

    private final AgentScopeAgentMapper agentMapper;


    private final ChatSessionMapper chatSessionMapper;

    private final ChatMessageMapper chatMessageMapper;

    private final AgentScopeAgentManager agentManager;

    private final AgentScopeRegistry agentRegistry;

    /**
     * 获取 Agent 列表
     */
    @GetMapping("/list")
    @Operation(summary = "获取Agent列表", description = "获取所有Agent列表")
    public List<AgentScopeAgent> list(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            return agentMapper.searchByKeyword(keyword);
        }
        if (status != null && !status.isBlank()) {
            return agentMapper.findByStatus(status);
        }
        return agentMapper.findAll();
    }

    /**
     * 获取 Agent 详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取Agent详情", description = "根据ID获取Agent详情")
    public AgentScopeAgent get(@PathVariable Long id) {
        AgentScopeAgent agent = agentMapper.findById(id);
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + id);
        }
        return agent;
    }

    /**
     * 创建 Agent
     */
    @PostMapping
    @Operation(summary = "创建Agent", description = "创建新的Agent")
    public AgentScopeAgent create(@RequestBody AgentScopeAgent agent) {
        if (agent.getStatus() == null || agent.getStatus().isBlank()) {
            agent.setStatus("draft");
        }
        if (agent.getApiKeyEnabled() == null) {
            agent.setApiKeyEnabled(0);
        }
        agentMapper.insert(agent);
        log.info("✅ Agent 创建成功: id={}, name={}", agent.getId(), agent.getName());
        return agent;
    }

    /**
     * 更新 Agent
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新Agent", description = "更新Agent信息")
    public AgentScopeAgent update(@PathVariable Long id, @RequestBody AgentScopeAgent agent) {
        AgentScopeAgent existing = agentMapper.findById(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + id);
        }

        agent.setId(id);
        agentMapper.updateById(agent);

        // 如果是已发布状态，刷新 Agent
        if ("published".equals(agent.getStatus()) || "published".equals(existing.getStatus())) {
            agentManager.reload(id);
        }

        log.info("✅ Agent 更新成功: id={}, name={}", id, agent.getName());
        return agent;
    }

    /**
     * 删除 Agent
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除Agent", description = "删除Agent")
    public void delete(@PathVariable Long id) {
        AgentScopeAgent agent = agentMapper.findById(id);
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + id);
        }

        // 从注册表注销
        agentRegistry.unregister(id);

        // 删除数据库记录
        agentMapper.deleteById(id);

        log.info("✅ Agent 删除成功: id={}", id);
    }

    /**
     * 发布 Agent
     */
    @PostMapping("/{id}/publish")
    @Operation(summary = "发布Agent", description = "将Agent从草稿发布为已发布状态")
    public void publish(@PathVariable Long id) {
        agentManager.publish(id);
    }

    /**
     * 下线 Agent
     */
    @PostMapping("/{id}/offline")
    @Operation(summary = "下线Agent", description = "将Agent从已发布下线")
    public void offline(@PathVariable Long id) {
        agentManager.offline(id);
    }

    /**
     * 重新发布 Agent（从下线状态）
     */
    @PostMapping("/{id}/republish")
    @Operation(summary = "重新发布Agent", description = "将下线的Agent重新发布")
    public void republish(@PathVariable Long id) {
        agentManager.republish(id);
    }

    // ==================== 会话管理 ====================

    /**
     * 获取会话列表
     */
    @GetMapping("/{agentId}/sessions")
    @Operation(summary = "获取会话列表", description = "获取Agent的会话列表")
    public ApiResponse<List<ChatSession>> getSessions(@PathVariable Long agentId) {
        checkAgentExists(agentId);
        List<ChatSession> sessions = chatSessionMapper.findByAgentId(agentId);
        return ApiResponse.success("获取会话列表成功", sessions);
    }

    /**
     * 创建新会话
     */
    @PostMapping("/{agentId}/sessions")
    @Operation(summary = "创建会话", description = "创建新的聊天会话")
    public ApiResponse<ChatSession> createSession(
            @PathVariable Long agentId,
            @RequestParam(required = false, defaultValue = "新会话") String title) {
        checkAgentExists(agentId);
        String sessionId = UUID.randomUUID().toString();
        ChatSession session = ChatSession.builder()
                .id(sessionId)
                .agentId(agentId)
                .title(title)
                .status("active")
                .build();
        chatSessionMapper.insert(session);
        return ApiResponse.success("创建会话成功", session);
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "删除会话", description = "删除聊天会话及其消息")
    public ApiResponse<Void> deleteSession(@PathVariable String sessionId) {
        ChatSession session = chatSessionMapper.findById(sessionId);
        chatMessageMapper.deleteBySessionId(sessionId);
        chatSessionMapper.deleteById(sessionId);
        return ApiResponse.success("删除会话成功", null);
    }

    /**
     * 更新会话标题
     */
    @PutMapping("/sessions/{sessionId}/title")
    @Operation(summary = "更新会话标题", description = "更新会话的标题")
    public ApiResponse<Void> updateSessionTitle(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> request) {
        String title = request.get("title");
        if (title == null || title.trim().isEmpty()) {
            return ApiResponse.error("标题不能为空");
        }
        ChatSession session = chatSessionMapper.findById(sessionId);
        if (session != null) {
            session.setTitle(title);
            chatSessionMapper.updateById(session);
        }
        return ApiResponse.success("更新会话标题成功", null);
    }

    /**
     * 置顶/取消置顶会话
     */
    @PutMapping("/sessions/{sessionId}/pin")
    @Operation(summary = "置顶会话", description = "置顶或取消置顶会话")
    public ApiResponse<Void> pinSession(
            @PathVariable String sessionId,
            @RequestBody Map<String, Boolean> request) {
        Boolean pinned = request.get("pinned");
        if (pinned == null) {
            return ApiResponse.error("参数错误");
        }
        // TODO: 需要在 ChatSession 实体中添加 isPinned 字段
        // ChatSession session = chatSessionMapper.findById(sessionId);
        // if (session != null) {
        //     session.setIsPinned(pinned);
        //     chatSessionMapper.update(session);
        // }
        return ApiResponse.success(pinned ? "会话已置顶" : "会话已取消置顶", null);
    }

    /**
     * 获取会话消息列表
     */
    @GetMapping("/sessions/{sessionId}/messages")
    @Operation(summary = "获取消息列表", description = "获取会话的消息列表")
    public ApiResponse<List<ChatMessage>> getMessages(@PathVariable String sessionId) {
        ChatSession session = chatSessionMapper.findById(sessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + sessionId);
        }
        List<ChatMessage> messages = chatMessageMapper.findBySessionId(sessionId);
        return ApiResponse.success("获取消息列表成功", messages);
    }

    /**
     * 与 Agent 聊天（支持会话）
     */
    @PostMapping("/{id}/chat")
    @Operation(summary = "与Agent聊天", description = "向Agent发送消息并获取回复")
    public ApiResponse<Map<String, Object>> chat(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {
        ReActAgent agent = agentRegistry.getAgent(id);
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found or not published: " + id);
        }

        String message = (String) request.get("message");
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }

        String sessionId = request.get("sessionId") != null ? ((String) request.get("sessionId")) : null;

        ChatSession session;
        if (sessionId == null) {
            session = ChatSession.builder()
                    .agentId(id)
                    .title(message.length() > 20 ? message.substring(0, 20) + "..." : message)
                    .status("active")
                    .build();
            chatSessionMapper.insert(session);
            sessionId = session.getId();
        } else {
            session = chatSessionMapper.findById(sessionId);
            if (session == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + sessionId);
            }
        }

        ChatMessage userMsg = ChatMessage.builder()
                .sessionId(sessionId)
                .agentId(id)
                .role("user")
                .content(message)
                .messageType("text")
                .build();
        chatMessageMapper.insert(userMsg);
        chatSessionMapper.updateTime(sessionId);

        Msg userMsgForAgent = Msg.builder().textContent(message).build();
        log.debug("📨 [Chat] Agent收到消息: agentId={}, message={}", id, message);
        Msg response = Mono.fromFuture(agent.call(userMsgForAgent).toFuture()).block();
        log.debug("📨 [Chat] Agent原始响应: agentId={}, role={}, contentType={}, getTextContent长度={}",
                id, 
                response != null ? response.getRole() : "null",
                response != null && response.getContent() != null && !response.getContent().isEmpty() 
                        ? response.getContent().get(0).getClass().getSimpleName() : "empty",
                response != null && response.getTextContent() != null ? response.getTextContent().length() : 0);
        String responseContent = extractResponseText(response);
        log.debug("📨 [Chat] Agent回复完成: agentId={}, response长度={}", id, responseContent != null ? responseContent.length() : 0);
        String messageType = detectMessageType(responseContent);

        ChatMessage assistantMsg = ChatMessage.builder()
                .sessionId(sessionId)
                .agentId(id)
                .role("assistant")
                .content(responseContent)
                .messageType(messageType)
                .build();
        chatMessageMapper.insert(assistantMsg);

        return ApiResponse.success("发送成功", Map.of(
                "sessionId", sessionId,
                "message", responseContent,
                "messageId", assistantMsg.getId(),
                "messageType", messageType
        ));
    }

    /**
     * 从 Agent 响应 Msg 中提取文本内容
     * 
     * <p>当 stopAgent() 被调用后，工具结果存储在 ToolResultBlock 中，
     * getTextContent() 只提取顶层 TextBlock，无法获取 ToolResultBlock.output 中的文本。
     * 此方法递归提取所有层级的文本内容。
     */
    private String extractResponseText(Msg response) {
        if (response == null) return "";

        // 优先使用顶层 TextBlock
        String textContent = response.getTextContent();
        if (textContent != null && !textContent.isBlank()) {
            log.debug("📨 [extractResponseText] 通过getTextContent提取成功, 长度={}", textContent.length());
            return textContent;
        }

        // 从 ToolResultBlock.output 中提取文本
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.getContent()) {
            if (block instanceof ToolResultBlock toolResult) {
                for (ContentBlock outputBlock : toolResult.getOutput()) {
                    if (outputBlock instanceof TextBlock textBlock) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(textBlock.getText());
                    }
                }
            }
        }
        String result = sb.toString();
        log.debug("📨 [extractResponseText] 通过ToolResultBlock提取, 长度={}", result.length());
        return result;
    }

    /**
     * 根据返回内容检测消息类型
     */
    private String detectMessageType(String content) {
        if (content == null || content.isEmpty()) {
            return "text";
        }
        // 工具返回格式: $$$markdown-report# 或 report content: $$$markdown-report
        if (content.contains("$$$markdown-report") || content.contains("report content:")) {
            return "markdown-report";
        }
        if (content.contains("$$$html-report") || content.contains("report content: $$$html")) {
            return "html-report";
        }
        // 检测 Markdown 特征
        if (content.contains("# ") || content.contains("## ") || content.contains("```")) {
            return "markdown-report";
        }
        // 检测 HTML 特征
        if (content.contains("<html") || content.contains("</div>") || content.contains("</")) {
            return "html-report";
        }
        return "text";
    }

    /**
     * 刷新所有 Agent
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新所有Agent", description = "重新加载所有已发布的Agent")
    public void refreshAll() {
        agentManager.refreshAll();
    }

    /**
     * 获取注册状态
     */
    @GetMapping("/registry/status")
    @Operation(summary = "获取注册状态", description = "获取Agent注册表状态")
    public Map<String, Object> getRegistryStatus() {
        return Map.of(
                "registeredCount", agentRegistry.getRegisteredCount(),
                "agentIds", agentRegistry.getAllAgents().keySet()
        );
    }

    // ==================== API Key 管理 ====================

    /**
     * 获取 API Key
     */
    @GetMapping("/{id}/api-key")
    @Operation(summary = "获取API Key", description = "获取Agent的API Key")
    public ApiResponse<Map<String, Object>> getApiKey(@PathVariable Long id) {
        log.info("获取 API Key: agentId={}", id);
        AgentScopeAgent agent = checkAgentExists(id);
        log.info("Agent found: id={}, apiKey={}, apiKeyEnabled={}", id, agent.getApiKey(), agent.getApiKeyEnabled());
        String maskedKey = null;
        if (agent.getApiKey() != null && !agent.getApiKey().isEmpty()) {
            maskedKey = maskApiKey(agent.getApiKey());
        }
        return ApiResponse.success("获取API Key成功", Map.of(
                "apiKey", maskedKey != null ? maskedKey : "",
                "apiKeyEnabled", agent.getApiKeyEnabled() != null && agent.getApiKeyEnabled() == 1
        ));
    }

    /**
     * 生成 API Key
     */
    @PostMapping("/{id}/api-key/generate")
    @Operation(summary = "生成API Key", description = "为Agent生成新的API Key")
    public ApiResponse<Map<String, Object>> generateApiKey(@PathVariable Long id) {
        checkAgentExists(id);
        AgentScopeAgent agent = agentMapper.findById(id);
        String newKey = "as_" + UUID.randomUUID().toString().replace("-", "");
        agent.setApiKey(newKey);
        agent.setApiKeyEnabled(1);
        agentMapper.updateById(agent);
        log.info("✅ API Key 生成成功: agentId={}", id);
        return ApiResponse.success("API Key生成成功", Map.of(
                "apiKey", newKey,
                "apiKeyEnabled", true
        ));
    }

    /**
     * 重置 API Key
     */
    @PostMapping("/{id}/api-key/reset")
    @Operation(summary = "重置API Key", description = "重置Agent的API Key")
    public ApiResponse<Map<String, Object>> resetApiKey(@PathVariable Long id) {
        checkAgentExists(id);
        AgentScopeAgent agent = agentMapper.findById(id);
        String newKey = "as_" + UUID.randomUUID().toString().replace("-", "");
        agent.setApiKey(newKey);
        agentMapper.updateById(agent);
        log.info("✅ API Key 重置成功: agentId={}", id);
        return ApiResponse.success("API Key重置成功", Map.of(
                "apiKey", newKey,
                "apiKeyEnabled", agent.getApiKeyEnabled() != null && agent.getApiKeyEnabled() == 1
        ));
    }

    /**
     * 删除 API Key
     */
    @DeleteMapping("/{id}/api-key")
    @Operation(summary = "删除API Key", description = "删除Agent的API Key")
    public ApiResponse<Map<String, Object>> deleteApiKey(@PathVariable Long id) {
        checkAgentExists(id);
        AgentScopeAgent agent = agentMapper.findById(id);
        agent.setApiKey(null);
        agent.setApiKeyEnabled(0);
        agentMapper.updateById(agent);
        log.info("✅ API Key 删除成功: agentId={}", id);
        return ApiResponse.success("API Key删除成功", Map.of(
                "apiKey", "",
                "apiKeyEnabled", false
        ));
    }

    /**
     * 切换 API Key 启用状态
     */
    @PutMapping("/{id}/api-key/toggle")
    @Operation(summary = "切换API Key状态", description = "启用或禁用Agent的API Key")
    public ApiResponse<Map<String, Object>> toggleApiKey(@PathVariable Long id, @RequestParam boolean enabled) {
        checkAgentExists(id);
        AgentScopeAgent agent = agentMapper.findById(id);
        agent.setApiKeyEnabled(enabled ? 1 : 0);
        agentMapper.updateById(agent);
        log.info("✅ API Key {}: agentId={}", enabled ? "启用" : "禁用", id);
        return ApiResponse.success(enabled ? "API Key启用成功" : "API Key禁用成功", Map.of(
                "apiKey", agent.getApiKey() != null ? maskApiKey(agent.getApiKey()) : "",
                "apiKeyEnabled", enabled
        ));
    }

    /**
     * 掩码 API Key（显示前4位 + *** + 后4位）
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 12) {
            return "***";
        }
        return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
    }


    private AgentScopeAgent checkAgentExists(Long id) {
        AgentScopeAgent agent = agentMapper.findById(id);
        if (agent == null) {
            log.warn("Agent not found: {}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + id);
        }
        return agent;
    }

}
