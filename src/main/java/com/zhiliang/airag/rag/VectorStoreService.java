package com.zhiliang.airag.rag;

import com.zhiliang.airag.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

// 内存版向量库，查询就是暴力算余弦
// 几千条以内没问题，量大了换 pgvector，外面只用 ingest 和 search，改这一个类就行
@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    private final EmbeddingModel embeddingModel;
    private final AppProperties props;
    private final List<Chunk> chunks = new CopyOnWriteArrayList<>();

    public VectorStoreService(EmbeddingModel embeddingModel, AppProperties props) {
        this.embeddingModel = embeddingModel;
        this.props = props;
    }

    public int ingest(String source, String text) {
        List<String> pieces = TextChunker.split(text, props.getRag().getChunkSize(), props.getRag().getChunkOverlap());
        for (String piece : pieces) {
            float[] vector = embeddingModel.embed(piece);
            chunks.add(new Chunk(UUID.randomUUID().toString(), source, piece, vector));
        }
        log.info("入库 {} 条，来源 {}，总数 {}", pieces.size(), source, chunks.size());
        return pieces.size();
    }

    public List<SearchHit> search(String query, int topK) {
        if (chunks.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        float[] q = embeddingModel.embed(query);
        return chunks.stream()
                .map(c -> new SearchHit(c, cosine(q, c.vector())))
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(topK)
                .toList();
    }

    public int size() {
        return chunks.size();
    }

    public void clear() {
        chunks.clear();
    }

    public record SearchHit(Chunk chunk, double score) {
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-9);
    }
}
