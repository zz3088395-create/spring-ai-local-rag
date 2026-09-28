package com.zhiliang.airag.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

// 启动后把 docs 下的 md 先建进去，不然演示时没东西可搜
@Component
public class DocumentLoader {

    private static final Logger log = LoggerFactory.getLogger(DocumentLoader.class);

    private final VectorStoreService vectorStore;

    public DocumentLoader(VectorStoreService vectorStore) {
        this.vectorStore = vectorStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadBundledDocs() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:docs/*.md");
            for (Resource resource : resources) {
                String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                vectorStore.ingest(resource.getFilename(), text);
            }
            log.info("知识库就绪，{} 个文件 {} 条", resources.length, vectorStore.size());
        } catch (Exception e) {
            // ollama 没起来或者 bge-m3 没拉，别让应用起不来
            log.warn("内置文档没建进去，先检查 ollama 和向量模型：{}", e.getMessage());
        }
    }
}
