package com.zhiliang.airag.rag;

import java.util.ArrayList;
import java.util.List;

// 按段落切块，段落太长再硬切，切口留点重叠免得把句子切断
public final class TextChunker {

    private TextChunker() {
    }

    public static List<String> split(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) {
            return chunks;
        }
        int step = Math.max(1, chunkSize - Math.min(overlap, chunkSize - 1));

        StringBuilder current = new StringBuilder();
        for (String raw : normalized.split("\n\\s*\n")) {
            String paragraph = raw.trim();
            if (paragraph.isEmpty()) {
                continue;
            }
            if (paragraph.length() > chunkSize) {
                // 超长段落，硬切
                flush(current, chunks);
                for (int start = 0; start < paragraph.length(); start += step) {
                    int end = Math.min(paragraph.length(), start + chunkSize);
                    chunks.add(paragraph.substring(start, end));
                    if (end == paragraph.length()) {
                        break;
                    }
                }
                continue;
            }
            if (current.length() > 0 && current.length() + 1 + paragraph.length() > chunkSize) {
                // 装不下了，先收一块
                flush(current, chunks);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(paragraph);
        }
        flush(current, chunks);
        return chunks;
    }

    private static void flush(StringBuilder buffer, List<String> out) {
        if (buffer.length() > 0) {
            out.add(buffer.toString());
            buffer.setLength(0);
        }
    }
}
