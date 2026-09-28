# spring-ai-local-rag

A local AI assistant that runs entirely on your own machine. Spring Boot + Spring AI on the server, Ollama for inference, nothing leaves the computer and nothing is billed per token.

It exists to show, in one small codebase, the pieces that matter when you put an LLM behind a real backend:

- **Streaming answers** over Server-Sent Events, token by token.
- **Conversation memory with summary compression.** The last few turns are sent verbatim; everything older is folded into a rolling summary by the model itself, so prompt size, token cost and first-token latency stay flat no matter how long a session runs.
- **Retrieval-augmented generation** with a hand-rolled vector store: paragraph-aware chunking, `bge-m3` embeddings, cosine search, a relevance threshold, and the retrieved chunks shown next to every answer.
- **Model as configuration.** Chat and embedding models are two lines in `application.yml`; the code only talks to Spring AI's `ChatClient` and `EmbeddingModel`.

## Quick start

Prerequisites: JDK 17+, Maven 3.9+, and [Ollama](https://ollama.com) running locally.

```bash
ollama pull qwen3:8b      # chat model (any pulled model works; change it in application.yml)
ollama pull bge-m3        # embedding model
mvn spring-boot:run
```

Open <http://localhost:8080>. The bundled `docs/about-this-project.md` is indexed on startup, so "how does memory compression work here?" is a good first question. Paste your own notes into the knowledge base panel to ask about those instead. Keep a conversation going past twelve messages and watch the right-hand panel fold the history into a summary.

## How it works

```
browser ──GET /api/chat/stream (SSE)──▶ ChatController
                                            │
                                            ▼
                                       ChatService.ask()
                       ┌────────────────────┼─────────────────────┐
                       ▼                    ▼                     ▼
              SessionMemory        VectorStoreService        ChatClient (Spring AI)
         summary + recent turns    embed query → cosine     stream tokens from Ollama
                       │            top-k above threshold             │
                       └──────────── system prompt ◀──────────────────┘
                                            │
                          answer complete → append to memory
                          overflow? → summarise on a background thread
```

**Prompt assembly.** Every turn sends: persona → rolling summary (if any) → retrieved chunks tagged with their source file → the last `max-recent` messages → the new question.

**Memory compression.** After an answer finishes, if the verbatim history holds `max-recent + compress-batch` messages or more, the oldest ones are sent to the model together with the previous summary and merged into ≤150 words that keep names, preferences, decisions and open questions. This runs on a bounded-elastic thread so the user never waits, and history is only removed after the new summary exists, so a failed call loses nothing.

**Retrieval.** `TextChunker` splits on blank lines and packs paragraphs up to `chunk-size` characters; only oversized paragraphs are cut, with `chunk-overlap` shared characters so a sentence on the boundary survives intact. `VectorStoreService` embeds each chunk once on ingest and answers queries by brute-force cosine similarity, which is plenty for a few thousand chunks. Swapping it for pgvector or Milvus touches one class.

## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/chat/stream?sessionId=&q=` | SSE stream: `{"sources":[…]}`, then `{"delta":"…"}`×N, then `{"done":true}` |
| GET | `/api/chat/memory?sessionId=` | Current summary, compression count and verbatim recent turns |
| DELETE | `/api/chat/memory?sessionId=` | Forget a session |
| POST | `/api/rag/ingest` | `{"source":"notes.md","text":"…"}` → chunk and index |
| GET | `/api/rag/search?q=&topK=3` | Inspect retrieval without calling the chat model |
| GET | `/api/rag/stats` | Chunk count |
| DELETE | `/api/rag` | Clear the knowledge base |

## Configuration

```yaml
spring.ai.ollama.base-url: http://localhost:11434
spring.ai.ollama.chat.options.model: qwen3:8b       # qwen3:14b on a 16 GB+ GPU or a 24 GB Mac
spring.ai.ollama.embedding.options.model: bge-m3

app.rag.chunk-size: 400        # characters per chunk
app.rag.chunk-overlap: 60
app.rag.top-k: 3
app.rag.min-score: 0.30        # cosine threshold; lower if relevant chunks are dropped
app.memory.max-recent: 6       # messages kept verbatim
app.memory.compress-batch: 6   # compress once this many extra messages have piled up
```

Point `base-url` at any OpenAI-compatible endpoint (vLLM, LM Studio, a remote server) and nothing else changes. To use a hosted provider instead, replace `spring-ai-starter-model-ollama` with the OpenAI or Anthropic starter and set that provider's properties.

## Notes

- Qwen3 emits a `<think>…</think>` block by default. The persona ends with `/no_think`, Qwen's soft switch to disable it, and the UI hides the block if it appears anyway. Other models ignore the token.
- Everything is in memory. Restarting the app clears sessions and the knowledge base; the bundled document is re-indexed on startup. A Redis hash per session and a pgvector table are the obvious next step and the code is shaped for it.
- Built against Spring Boot 3.4 and Spring AI 1.0.0 (both on Maven Central). Newer versions should work with the same code; if a Spring AI method has moved, the compiler will say exactly where.

## Layout

```
src/main/java/com/zhiliang/airag
├── AiRagApplication.java
├── config/   AiConfig (ChatClient bean), AppProperties (app.* tunables)
├── chat/     ChatController (SSE), ChatService (prompt + compression), SessionMemory
└── rag/      TextChunker, Chunk, VectorStoreService, DocumentLoader, RagController
src/main/resources
├── application.yml
├── docs/about-this-project.md   indexed on startup
└── static/index.html            the UI, no build step
```
