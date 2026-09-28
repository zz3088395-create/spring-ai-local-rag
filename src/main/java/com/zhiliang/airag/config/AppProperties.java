package com.zhiliang.airag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// app.* 的配置，值在 application.yml 里
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Rag rag = new Rag();
    private final Memory memory = new Memory();

    public Rag getRag() {
        return rag;
    }

    public Memory getMemory() {
        return memory;
    }

    public static class Rag {
        private int chunkSize = 400;      // 每块最多多少字
        private int chunkOverlap = 60;    // 长段落硬切时前后重叠多少字
        private int topK = 3;             // 取前几条
        private double minScore = 0.30;   // 低于这个分数当没搜到

        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getChunkOverlap() { return chunkOverlap; }
        public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        public double getMinScore() { return minScore; }
        public void setMinScore(double minScore) { this.minScore = minScore; }
    }

    public static class Memory {
        private int maxRecent = 6;        // 最近几条原文保留
        private int compressBatch = 6;    // 攒够这么多再压一次，别每轮都压

        public int getMaxRecent() { return maxRecent; }
        public void setMaxRecent(int maxRecent) { this.maxRecent = maxRecent; }
        public int getCompressBatch() { return compressBatch; }
        public void setCompressBatch(int compressBatch) { this.compressBatch = compressBatch; }
    }
}
