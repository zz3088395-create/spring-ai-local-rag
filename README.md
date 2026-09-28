# spring-ai-local-rag

一个完全跑在本机的 AI 助手。服务端是 Spring Boot + Spring AI，推理用 Ollama，数据不出电脑，不按 token 计费。

[English README](README_EN.md)

它用一个很小的代码库演示把大模型放到真实后端里时最关键的几件事：

- **流式输出**：通过 Server-Sent Events 逐 token 推到浏览器。
- **带摘要压缩的对话记忆**：最近几轮原文保留，更早的内容由模型自己压缩成一段滚动摘要。无论会话多长，提示词长度、token 成本和首字延迟都保持平稳。
- **RAG 检索增强**：手写的向量存储——按段落感知切块、`bge-m3` 向量化、余弦相似度检索、相关度阈值过滤，每次回答旁边都能看到用了哪些片段。
- **模型即配置**：对话模型和向量模型是 `application.yml` 里的两行；代码只依赖 Spring AI 的 `ChatClient` 和 `EmbeddingModel` 抽象。

## 快速开始

前置条件：JDK 17+、Maven 3.9+、本机已运行 [Ollama](https://ollama.com)。

```bash
ollama pull qwen3:8b      # 对话模型（任何已拉取的模型都行，在 application.yml 里改）
ollama pull bge-m3        # 向量模型
mvn spring-boot:run
```

打开 <http://localhost:8080>。启动时会把内置的 `docs/about-this-project.md` 建好索引，第一个问题可以问"这个项目的记忆压缩是怎么做的"。把自己的笔记粘进右侧知识库面板，就能问自己的内容。连续聊超过十二条消息，可以看到右侧面板把历史压缩成摘要。

## 工作原理

```
浏览器 ──GET /api/chat/stream (SSE)──▶ ChatController
                                            │
                                            ▼
                                       ChatService.ask()
                       ┌────────────────────┼─────────────────────┐
                       ▼                    ▼                     ▼
              SessionMemory        VectorStoreService        ChatClient (Spring AI)
           摘要 + 最近几轮原文     问题向量化 → 余弦检索      从 Ollama 流式取 token
                       │            取 top-k 并过滤低分               │
                       └──────────── 组装 system prompt ◀────────────┘
                                            │
                              回答完成 → 追加到记忆
                              超出预算？→ 后台线程压缩
```

**提示词组装。** 每一轮发送：人设 → 滚动摘要（如有）→ 带来源文件名的检索片段 → 最近 `max-recent` 条消息 → 新问题。

**记忆压缩。** 回答完成后，如果原文历史达到 `max-recent + compress-batch` 条，最旧的消息会连同上一版摘要一起交给模型，合并成 150 字以内的新摘要，保留姓名、偏好、决定和未解决的问题。压缩在 bounded-elastic 线程上运行，用户不需要等；只有新摘要生成后才删除旧消息，所以压缩失败不会丢历史。

**检索。** `TextChunker` 按空行切分，把段落打包到 `chunk-size` 个字符以内；只有超长段落才按长度切，切口处保留 `chunk-overlap` 个重叠字符，边界上的句子至少在一块里是完整的。`VectorStoreService` 在入库时对每块做一次向量化，查询时用暴力余弦相似度，几千个块以内足够。换成 pgvector 或 Milvus 只需改这一个类。

## 接口

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/chat/stream?sessionId=&q=` | SSE 流：先 `{"sources":[…]}`，然后 N 个 `{"delta":"…"}`，最后 `{"done":true}` |
| GET | `/api/chat/memory?sessionId=` | 当前摘要、压缩次数和原文保留的最近消息 |
| DELETE | `/api/chat/memory?sessionId=` | 清空一个会话 |
| POST | `/api/rag/ingest` | `{"source":"notes.md","text":"…"}` → 切块并建索引 |
| GET | `/api/rag/search?q=&topK=3` | 只看检索结果，不调用对话模型 |
| GET | `/api/rag/stats` | 已索引的块数 |
| DELETE | `/api/rag` | 清空知识库 |

## 配置

```yaml
spring.ai.ollama.base-url: http://localhost:11434
spring.ai.ollama.chat.options.model: qwen3:8b       # 16G 以上显存或 24G Mac 可换 qwen3:14b
spring.ai.ollama.embedding.options.model: bge-m3

app.rag.chunk-size: 400        # 每块字符数
app.rag.chunk-overlap: 60
app.rag.top-k: 3
app.rag.min-score: 0.30        # 余弦相似度阈值；相关片段被丢掉就调低
app.memory.max-recent: 6       # 原文保留的消息条数
app.memory.compress-batch: 6   # 超出 max-recent 这么多条后触发压缩
```

把 `base-url` 指向任何 OpenAI 兼容的地址（vLLM、LM Studio、远程服务器），其余不用动。要换成云端厂商，把 `spring-ai-starter-model-ollama` 换成 OpenAI 或 Anthropic 的 starter 并配置对应属性即可。

## 说明

- Qwen3 默认会先输出一段 `<think>…</think>` 思考内容。人设末尾的 `/no_think` 是 Qwen 的软开关，用来关掉它；万一仍然出现，前端会隐藏。其他模型会忽略这个标记。
- 全部数据在内存里。重启应用会清空会话和知识库，内置文档在启动时重新索引。下一步自然是每个会话一个 Redis hash、向量存到 pgvector 表，代码结构已经按这个方向留好了。
- 基于 Spring Boot 3.4 和 Spring AI 1.0.0（都在 Maven 中央仓库）。更新的版本应该可以直接用；如果 Spring AI 的某个方法改了名字，编译器会告诉你在哪。

## 目录结构

```
src/main/java/com/zhiliang/airag
├── AiRagApplication.java
├── config/   AiConfig（ChatClient Bean）、AppProperties（app.* 可调参数）
├── chat/     ChatController（SSE）、ChatService（提示词组装 + 压缩）、SessionMemory
└── rag/      TextChunker、Chunk、VectorStoreService、DocumentLoader、RagController
src/main/resources
├── application.yml
├── docs/about-this-project.md   启动时建索引
└── static/index.html            页面，无需构建
```
