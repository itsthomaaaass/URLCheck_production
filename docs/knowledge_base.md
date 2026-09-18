# Knowledge base (RAG)

The assistant answers questions about URLCheck itself from Markdown documents
that live in the repository. This is the feature `docs/RAG_design.md` designs;
this file records what was actually built and how to operate it.

## How retrieval is triggered

The knowledge base is exposed as a **tool**, `searchKnowledge`, not as an
always-on advisor. Spring AI registers a `ToolCallingAdvisor` automatically
whenever `tools(...)` is set on the chat request, and the model then decides
which tool to call - `searchKnowledge`, `listMonitoredUrls`, `checkUrl` and the
rest. Nothing in the backend chooses between "answer from documents" and "use a
tool": the LLM does, from the tool descriptions and the system prompt.

The practical consequences:

- A question about the user's own URLs never pays for a vector search, and a
  documentation question never calls the URL tools.
- If the model does not call `searchKnowledge`, no embedding runs and Qdrant is
  never touched.
- The system prompt (see `AiAgent`) tells the model to search the knowledge base
  before claiming that something is not supported.

## Documents

    backend/src/main/resources/ai/knowledge/
      architecture/   *.md
      business/       *.md
      database/       *.md
      api/            *.md

The shipped set is written in Chinese, matching the Chinese-first embedding
model:

    architecture/system-architecture.md   系统总体架构
    business/change-detection.md          内容变化检测（SHA-256 哈希比较）
    business/url-checking.md              URL 检查流程与错误分类
    business/scheduled-monitoring.md      定时监控调度与 Claim 机制
    business/timeline.md                  时间线事件与保留策略
    business/user-authentication.md       用户注册与登录
    database/schema.md                    数据库表结构
    api/endpoints.md                      HTTP API 接口

They document the implemented behaviour, matching `docs/timeline.md`,
`docs/api.md` and `docs/design.md`. Keep the sections under
`max-chunk-characters` (800 by default) so a section stays one chunk and a
table is never cut in half.

Anything matching `classpath*:ai/knowledge/**/*.md` is picked up. The first
directory under `ai/knowledge/` becomes the document's category, and it can be
used to filter a search. The `#` heading is the document title and
`##` or deeper start a new chunk; every chunk is prefixed with the title and its
heading path so a passage makes sense on its own, and a chunk that would exceed `max-chunk-characters` is split. Fenced
code blocks are never split through the middle.

Adding or editing documents is a source change: add the Markdown, rebuild, and
the next start embeds only what changed.

## Ingestion and the hash ledger

Ingestion runs at startup (`AI_KNOWLEDGE_INGEST_ON_START`, default true) and is
driven by content hashes, not by a timer:

1. Each document is hashed with SHA-256.
2. The hash is compared with the row for that document in
   `ai_knowledge_document` (created by `database/008_ai_knowledge.sql`).
3. Unchanged documents are skipped entirely - no chunking, no embedding, no
   Qdrant call. Restarting the application is therefore free.
4. Changed documents are re-chunked and re-embedded; their old chunks are
   replaced, so a shrinking document does not leave orphans behind.
5. Documents that are in the ledger but no longer on disk have their vectors
   deleted and their row forgotten.
6. A document that fails does not stop the others; it stays pending and is
   retried on the next start.

The ledger is MySQL rather than Qdrant payloads so that "has this file changed?"
can be answered without asking Qdrant for every chunk, and so a lost index can
be rebuilt by deleting the ledger rows.

Failures are logged and swallowed at startup: the assistant assists, and a
Qdrant outage must not stop URL monitoring.

## The embedding model

`Xenova/bge-small-zh-v1.5` (MIT), pinned to revision
`75c43b069aac4d136ba6bc1122f995fedcfd2781`, committed under
`backend/src/main/resources/ai/models/bge-small-zh-v1.5/`:

| File | Size | Why |
| --- | --- | --- |
| `model_quantized.onnx` | 23 MB | int8 weights; the session is loaded once at startup |
| `tokenizer.json` | 0.4 MB | HuggingFace tokenizer used to build the tensors |
| `config.json` | 0.7 KB | provenance only; the code does not parse it |
| `SOURCE.txt` | 59 B | the pinned revision, so the file can be re-fetched |

It is a BERT-style encoder: CLS pooling (row 0 of `last_hidden_state`), 512
dimensions wide, L2-normalised, so a Qdrant cosine score is directly comparable with
`AI_KNOWLEDGE_SIMILARITY_THRESHOLD`. Quantised weights, the tokenizer and the
512 dimensions were checked against each other by a test that loads the real
files (`OnnxEmbeddingModelTest`).

The model is committed on purpose. It runs inside the JVM through
`onnxruntime` (JAR, native libraries included) and `ai.djl.huggingface:tokenizers`,
both ordinary Maven dependencies, so a deployment needs **no extra step**: the
Docker image, the jar and even an offline `mvn test` run contain everything.

### What this model is good and bad at

It is a Chinese model with partial English coverage: lowercase English words are
in the vocabulary, capitalised ones often are not ("The" tokenises to `[UNK]`).
Measured on a five-question retrieval task (three English, two Chinese), it
picks the intended passage 5 of 5 times in both languages, but on English it is
clearly weaker than a multilingual model would be, and it can prefer a passage
that merely shares words with the question.

If the knowledge documents are mostly English, the better fit is
`Xenova/multilingual-e5-small` (Apache-2.0, ~113 MB quantised, 384 dimensions).
Swapping is a three-step change: drop the files in a new directory under
`ai/models/`, point `embedding.model`, `embedding.tokenizer` and
`embedding.dimensions` at them, and recreate the Qdrant collection - the vector
size of an existing collection cannot be changed.

Both model directories and the Qdrant collection can be deleted at any time;
nothing else in the project depends on them.

## Qdrant

`QDRANT_URL` and `QDRANT_API_KEY` are read from the environment and never
compiled in. The endpoint parser accepts a bare host, `https://host` and
`http://host:port`; gRPC over TLS on 6334 is the default.

The collection (default `urlcheck_knowledge`) is created by the ingestor on the
first run with the configured dimensions and cosine distance, plus keyword
payload indexes on `document` and `category`. Qdrant refuses a filter over an
unindexed field, so without those indexes dropping a changed document's old
chunks fails with `INVALID_ARGUMENT`; they are re-created on every start, which
is a no-op and also repairs a collection created before they existed. Spring AI's own
schema initialisation is deliberately disabled, so a Qdrant that is unreachable
at startup does not abort the application.

## Tests

`KnowledgeIngestionServiceTest` covers the hash ledger (unchanged documents are
not embedded, removed documents are dropped, one failure does not stop the
rest), `KnowledgeChunkerTest` the chunking rules, `KnowledgeRetrievalServiceTest`
the search request and the threshold, `KnowledgeToolsTest` the tool boundary,
and `OnnxEmbeddingModelTest` the shipped model files. None of them need a
network, a key or a running Qdrant.