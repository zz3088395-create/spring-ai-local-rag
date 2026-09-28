package com.zhiliang.airag.chat;

import com.zhiliang.airag.config.AppProperties;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 每个会话两部分：一段摘要 + 最近几条原文
// 原文攒多了就让模型把最老的压进摘要，提示词长度不会跟着轮数一直涨
// 演示放内存，线上换 redis 一个 session 一个 hash 就行
@Component
public class SessionMemory {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AppProperties props;

    public SessionMemory(AppProperties props) {
        this.props = props;
    }

    public String summary(String sessionId) {
        return session(sessionId).summary;
    }

    public List<Message> recent(String sessionId) {
        Session s = session(sessionId);
        synchronized (s.messages) {
            return new ArrayList<>(s.messages);
        }
    }

    public void append(String sessionId, String userText, String assistantText) {
        Session s = session(sessionId);
        synchronized (s.messages) {
            s.messages.add(new UserMessage(userText));
            s.messages.add(new AssistantMessage(assistantText));
        }
    }

    // 只看不删。拿去生成摘要，成功了再 applySummary 删，不然摘要失败消息就没了
    public List<Message> peekOverflow(String sessionId) {
        Session s = session(sessionId);
        int keep = props.getMemory().getMaxRecent();
        int trigger = keep + props.getMemory().getCompressBatch();
        synchronized (s.messages) {
            if (s.messages.size() < trigger) {
                return List.of();
            }
            return new ArrayList<>(s.messages.subList(0, s.messages.size() - keep));
        }
    }

    public void applySummary(String sessionId, String newSummary, int compressedCount) {
        Session s = session(sessionId);
        synchronized (s.messages) {
            int n = Math.min(compressedCount, s.messages.size());
            s.messages.subList(0, n).clear();
            s.summary = newSummary;
            s.compressions++;
        }
    }

    public int compressions(String sessionId) {
        return session(sessionId).compressions;
    }

    public void reset(String sessionId) {
        sessions.remove(sessionId);
    }

    private Session session(String id) {
        return sessions.computeIfAbsent(id, k -> new Session());
    }

    private static final class Session {
        private volatile String summary = "";
        private volatile int compressions = 0;
        private final List<Message> messages = new ArrayList<>();
    }
}
