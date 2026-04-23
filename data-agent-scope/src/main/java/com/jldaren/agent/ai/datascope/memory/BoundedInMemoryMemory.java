package com.jldaren.agent.ai.datascope.memory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 带限制的短期记忆：去重 + 数量限制。
 *
 * <p>双重防护避免上下文膨胀：
 * <ol>
 *   <li>去重：连续相同的 role+textContent 不重复添加</li>
 *   <li>限流：内存中始终 ≤ maxMessages 条，超出自动淘汰最旧的</li>
 * </ol>
 */
public class BoundedInMemoryMemory implements Memory {

    private final List<Msg> messages = new CopyOnWriteArrayList<>();
    private final int maxMessages;

    private static final String KEY_PREFIX = "memory";
    private static final int DEFAULT_MAX_MESSAGES = 20;

    public BoundedInMemoryMemory() {
        this(DEFAULT_MAX_MESSAGES);
    }

    public BoundedInMemoryMemory(int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be > 0, got: " + maxMessages);
        }
        this.maxMessages = maxMessages;
    }

    // ==================== StateModule ====================

    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        session.save(sessionKey, KEY_PREFIX + "_messages", new ArrayList<>(messages));
    }

    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        List<Msg> loaded = session.getList(sessionKey, KEY_PREFIX + "_messages", Msg.class);
        messages.clear();
        messages.addAll(loaded);
        trimToSize();
    }

    // ==================== Memory Interface ====================

    @Override
    public void addMessage(Msg message) {
        if (message == null) return;
        if (isDuplicate(message)) {
            return;
        }
        messages.add(message);
        trimToSize();
    }

    @Override
    public List<Msg> getMessages() {
        return messages.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteMessage(int index) {
        if (index >= 0 && index < messages.size()) {
            messages.remove(index);
        }
    }

    @Override
    public void clear() {
        messages.clear();
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    // ==================== 内部方法 ====================

    private void trimToSize() {
        while (messages.size() > maxMessages) {
            messages.remove(0);
        }
    }

    /**
     * 连续相同的 role+textContent 视为重复，跳过
     */
    private boolean isDuplicate(Msg message) {
        if (messages.isEmpty()) return false;
        Msg last = messages.get(messages.size() - 1);
        if (last == null) return false;
        if (message.getRole() != last.getRole()) return false;
        String newText = message.getTextContent();
        String lastText = last.getTextContent();
        if (newText == null || lastText == null) return false;
        return newText.equals(lastText);
    }
}
