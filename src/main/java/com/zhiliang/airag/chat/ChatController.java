package com.zhiliang.airag.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final SessionMemory memory;
    private final ObjectMapper mapper;

    public ChatController(ChatService chatService, SessionMemory memory, ObjectMapper mapper) {
        this.chatService = chatService;
        this.memory = memory;
        this.mapper = mapper;
    }

    // SSE。每条 data 是一个 json，不然 token 里的换行会被 SSE 吃掉
    // 顺序：sources -> delta... -> done
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam("sessionId")  String sessionId, @RequestParam("q") String q) {
        ChatService.Turn turn = chatService.ask(sessionId, q);

        List<Map<String, Object>> sources = turn.sources().stream()
                .map(hit -> Map.<String, Object>of(
                        "source", hit.chunk().source(),
                        "score", Math.round(hit.score() * 1000) / 1000.0,
                        "text", hit.chunk().text()))
                .toList();

        return Flux.concat(
                        Mono.just(json(Map.of("sources", sources))),
                        turn.tokens().map(delta -> json(Map.of("delta", delta))),
                        Mono.just(json(Map.of("done", true))))
                .onErrorResume(e -> Mono.just(json(Map.of(
                        "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))));
    }

    @GetMapping("/memory")
    public Map<String, Object> memory(@RequestParam String sessionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", memory.summary(sessionId));
        out.put("compressions", memory.compressions(sessionId));
        out.put("recent", memory.recent(sessionId).stream()
                .map(m -> Map.of("role", m.getMessageType().getValue(), "text", m.getText()))
                .toList());
        return out;
    }

    @DeleteMapping("/memory")
    public Map<String, Object> reset(@RequestParam String sessionId) {
        memory.reset(sessionId);
        return Map.of("reset", true);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
