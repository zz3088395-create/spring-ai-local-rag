package com.zhiliang.airag.rag;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final VectorStoreService vectorStore;

    public RagController(VectorStoreService vectorStore) {
        this.vectorStore = vectorStore;
    }

    // body: {"source":"xxx.md","text":"..."}
    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestBody IngestRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            throw new IllegalArgumentException("text 不能为空");
        }
        String source = (request.source() == null || request.source().isBlank()) ? "manual" : request.source();
        int added = vectorStore.ingest(source, request.text());
        return Map.of("chunks", added, "total", vectorStore.size());
    }

    // 只跑检索不跑模型，调阈值的时候用
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam("q") String q, @RequestParam(value = "topK", defaultValue = "3") int topK) {
        return vectorStore.search(q, topK).stream()
                .map(hit -> Map.<String, Object>of(
                        "score", Math.round(hit.score() * 1000) / 1000.0,
                        "source", hit.chunk().source(),
                        "text", hit.chunk().text()))
                .toList();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("chunks", vectorStore.size());
    }

    @DeleteMapping
    public Map<String, Object> clear() {
        vectorStore.clear();
        return Map.of("chunks", 0);
    }

    public record IngestRequest(String source, String text) {
    }
}
