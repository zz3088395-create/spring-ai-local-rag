package com.zhiliang.airag.rag;

// 切好并算过向量的一段文本
public record Chunk(String id, String source, String text, float[] vector) {
}
