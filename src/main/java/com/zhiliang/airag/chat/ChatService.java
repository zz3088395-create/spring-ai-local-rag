package com.zhiliang.airag.chat;

import com.zhiliang.airag.config.AppProperties;
import com.zhiliang.airag.rag.VectorStoreService;
import com.zhiliang.airag.rag.VectorStoreService.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// 拼提示词 -> 流式回答 -> 回答完了看要不要压缩记忆
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    // /no_think 是 qwen3 关思考块的开关，别的模型不认识，当普通文本忽略掉
    private static final String PERSONA = """
            你是一个完全运行在用户本机（通过 Ollama）的助手。
            用用户提问的语言回答，直接、简洁。
            如果给了参考资料，以参考资料为准作答；资料没有覆盖问题时简短说明。
            /no_think
            """;

    private final ChatClient chatClient;
    private final SessionMemory memory;
    private final VectorStoreService vectorStore;
    private final AppProperties props;

    public ChatService(ChatClient chatClient, SessionMemory memory, VectorStoreService vectorStore, AppProperties props) {
        this.chatClient = chatClient;
        this.memory = memory;
        this.vectorStore = vectorStore;
        this.props = props;
    }

    public record Turn(Flux<String> tokens, List<SearchHit> sources) {
    }

    public Turn ask(String sessionId, String question) {
        List<SearchHit> sources = vectorStore.search(question, props.getRag().getTopK()).stream()
                .filter(hit -> hit.score() >= props.getRag().getMinScore())
                .toList();

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(memory.summary(sessionId), sources)));
        messages.addAll(memory.recent(sessionId));
        messages.add(new UserMessage(question));

        StringBuilder answer = new StringBuilder();
        Flux<String> tokens = chatClient.prompt()
                .messages(messages)
                .stream()
                .content()
                .doOnNext(answer::append)
                .doOnComplete(() -> afterAnswer(sessionId, question, answer.toString()));

        return new Turn(tokens, sources);
    }

    private String buildSystemPrompt(String summary, List<SearchHit> sources) {
        StringBuilder sb = new StringBuilder(PERSONA);
        if (!summary.isBlank()) {
            sb.append("\n此前对话的摘要：\n").append(summary).append('\n');
        }
        if (!sources.isEmpty()) {
            sb.append("\n参考资料：\n");
            for (SearchHit hit : sources) {
                sb.append("- [").append(hit.chunk().source()).append("] ").append(hit.chunk().text()).append('\n');
            }
        }
        return sb.toString();
    }

    private void afterAnswer(String sessionId, String question, String answer) {
        memory.append(sessionId, question, answer);
        List<Message> overflow = memory.peekOverflow(sessionId);
        if (overflow.isEmpty()) {
            return;
        }
        // 用户已经拿到回答了，压缩丢后台跑
        Mono.fromRunnable(() -> compress(sessionId, overflow))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> { }, e -> log.warn("会话 {} 压缩失败：{}", sessionId, e.getMessage()));
    }

    private void compress(String sessionId, List<Message> overflow) {
        String transcript = overflow.stream()
                .map(m -> m.getMessageType().getValue() + ": " + m.getText())
                .collect(Collectors.joining("\n"));
        String previous = memory.summary(sessionId);

        String prompt = """
                把下面的对话压缩成一段不超过 150 字的摘要。
                保留用户的姓名、偏好、做出的决定、提到的事实和尚未解决的问题；去掉寒暄。
                如果给了上一版摘要，把新内容合并进去，不要重复。只输出摘要本身。/no_think

                上一版摘要：
                %s

                对话：
                %s
                """.formatted(previous.isBlank() ? "（无）" : previous, transcript);

        String summary = chatClient.prompt().user(prompt).call().content();
        // 基本不会是 null，兜一下
        memory.applySummary(sessionId, summary == null ? previous : summary.trim(), overflow.size());
        log.info("会话 {} 压了 {} 条进摘要", sessionId, overflow.size());
    }
}
