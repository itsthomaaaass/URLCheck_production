\# AI Knowledge Base \& RAG Design Document



\## 1. Overview



This document describes the design of the \*\*AI Knowledge Base and Retrieval-Augmented Generation (RAG)\*\* feature for the URLCheck AI Assistant.



The purpose of this feature is to allow the AI assistant to understand and answer questions about the URLCheck application's:



\* Business logic

\* System architecture

\* URL checking behavior

\* Change detection mechanism

\* Authentication

\* Database behavior

\* API behavior

\* Other stable application-specific knowledge



For example, when a user asks:



> How does URLCheck determine whether a webpage has changed?



The AI should be able to retrieve the relevant system knowledge and answer:



> URLCheck compares the SHA-256 hash of the newly fetched content with the previously stored hash. If the hashes differ, the content is considered changed.



The knowledge base is separate from conversational memory:



\* \*\*Memory\*\* stores information from conversations.

\* \*\*Knowledge Base / RAG\*\* stores stable information about the URLCheck system.

\* \*\*Tools\*\* access dynamic application data or perform application actions.



\---



\# 2. Goals



\## 2.1 Primary Goals



The feature should:



1\. Allow the AI assistant to answer questions about URLCheck's implementation and business rules.

2\. Store knowledge in human-readable Markdown documents.

3\. Use embeddings to make knowledge searchable by semantic similarity.

4\. Use Qdrant as a persistent vector store.

5\. Avoid regenerating embeddings every time the Spring Boot server restarts.

6\. Avoid embedding documents that have not changed.

7\. Re-embed only documents whose content has changed.

8\. Keep knowledge files version-controlled together with the backend code.

9\. Integrate with the existing Spring AI architecture.

10\. Keep the initial implementation simple enough for the current project.



\---



\# 3. Non-Goals



The initial version will NOT implement:



\* Complex hybrid search

\* Reranking models

\* Query rewriting

\* Automatic knowledge generation by the AI

\* Runtime editing of knowledge documents

\* A knowledge-base administration UI

\* Automatic filesystem watching

\* Multiple independent knowledge bases

\* Complex document version management

\* Fine-grained authorization of individual knowledge documents



These can be introduced later if the project requires them.



\---



\# 4. High-Level Architecture



The AI system will have three major sources of context:



```text

&#x20;                          ┌─────────────────────┐

&#x20;                          │       User          │

&#x20;                          └──────────┬──────────┘

&#x20;                                     │

&#x20;                                     ↓

&#x20;                          ┌─────────────────────┐

&#x20;                          │   AI Controller     │

&#x20;                          └──────────┬──────────┘

&#x20;                                     │

&#x20;                                     ↓

&#x20;                          ┌─────────────────────┐

&#x20;                          │     AI Agent        │

&#x20;                          └──────────┬──────────┘

&#x20;                                     │

&#x20;             ┌───────────────────────┼───────────────────────┐

&#x20;             │                       │                       │

&#x20;             ↓                       ↓                       ↓

&#x20;      ┌────────────┐          ┌──────────────┐        ┌────────────┐

&#x20;      │   Memory   │          │     RAG      │        │   Tools    │

&#x20;      │            │          │              │        │            │

&#x20;      │ Conversation│         │ Knowledge    │        │ Live data  │

&#x20;      │ context    │          │ retrieval    │        │ / actions  │

&#x20;      └────────────┘          └──────┬───────┘        └────────────┘

&#x20;                                     │

&#x20;                                     ↓

&#x20;                               ┌───────────┐

&#x20;                               │  Qdrant   │

&#x20;                               │ Vector DB │

&#x20;                               └───────────┘

&#x20;                                     │

&#x20;                                     ↓

&#x20;                               Relevant docs

&#x20;                                     │

&#x20;                                     ↓

&#x20;                             ┌──────────────┐

&#x20;                             │     LLM      │

&#x20;                             └──────┬───────┘

&#x20;                                    │

&#x20;                                    ↓

&#x20;                                  Answer

```



\---



\# 5. Technology Stack



| Component               | Technology                          |

| ----------------------- | ----------------------------------- |

| AI framework            | Spring AI                           |

| Backend                 | Spring Boot                         |

| Knowledge format        | Markdown                            |

| Embedding model         | Spring AI supported embedding model |

| Vector database         | Qdrant                              |

| Vector database hosting | Qdrant Cloud Free                   |

| Vector search           | Qdrant similarity search            |

| Document hashing        | SHA-256                             |

| Knowledge packaging     | `src/main/resources`                |

| LLM                     | Existing project LLM                |

| Conversation memory     | Existing AI memory implementation   |



Spring AI is responsible for the RAG integration and communication with Qdrant.



Qdrant is responsible for persistent storage and similarity search of vector embeddings.



\---



\# 6. Knowledge Source



Knowledge documents will be stored inside the backend project.



Recommended structure:



```text

backend/

├── src/

│   ├── main/

│   │   ├── java/

│   │   │   └── com/urlcheck/

│   │   │       └── ai/

│   │   │           ├── agent/

│   │   │           ├── config/

│   │   │           ├── controller/

│   │   │           ├── conversation/

│   │   │           ├── deletion/

│   │   │           ├── knowledge/

│   │   │           │   ├── ingestion/

│   │   │           │   └── retrieval/

│   │   │           ├── memory/

│   │   │           ├── message/

│   │   │           └── tool/

│   │   └── resources/

│   │       └── ai/

│   │           └── knowledge/

│   │               ├── architecture/

│   │               ├── business/

│   │               ├── database/

│   │               └── api/

│   │

│   └── test/

│

├── pom.xml

└── Dockerfile

```



Example:



```text

src/main/resources/ai/knowledge/



├── architecture/

│   ├── system-architecture.md

│   └── ai-architecture.md

│

├── business/

│   ├── change-detection.md

│   └── url-checking.md

│

├── database/

│   ├── users.md

│   └── monitored-urls.md

│

└── api/

&#x20;   ├── authentication.md

&#x20;   └── monitored-url-api.md

```



These files are part of the source code repository and are deployed together with the backend.



\---



\# 7. Knowledge Document Design



Knowledge documents should describe the system in a form that is useful for an AI rather than simply copying source code.



For example:



```markdown

\# Change Detection



\## Purpose



URLCheck determines whether the content of a monitored URL has changed.



\## Process



1\. The system requests the monitored URL.

2\. The returned content is processed.

3\. A SHA-256 hash is calculated from the content.

4\. The new hash is compared with the previously stored hash.

5\. If the hashes are different, the content is considered changed.

6\. If the hashes are equal, the content is considered unchanged.



\## Important Behavior



A failed HTTP request is not automatically considered a content change.

```



The exact behavior must reflect the actual implementation.



Knowledge documents should be treated as \*\*documentation of the application's actual behavior\*\*, not as a separate source of business rules.



\---



\# 8. RAG Architecture



RAG consists of two separate processes:



1\. Knowledge ingestion

2\. Knowledge retrieval



They should not happen at the same time.



\---



\# 9. Knowledge Ingestion



Knowledge ingestion converts Markdown documents into searchable vectors.



```text

Markdown files

&#x20;     ↓

Read documents

&#x20;     ↓

Calculate SHA-256

&#x20;     ↓

Check document status

&#x20;     ↓

Only changed documents

&#x20;     ↓

Chunk documents

&#x20;     ↓

Generate embeddings

&#x20;     ↓

Store vectors in Qdrant

```



The ingestion process should NOT run every time a user asks a question.



\---



\# 10. Document Hashing



Each knowledge document receives a SHA-256 hash based on its content.



Example:



```text

change-detection.md

&#x20;       ↓

SHA-256

&#x20;       ↓

A81F3C...92D

```



The system maintains metadata indicating which version of the document has already been embedded.



Conceptually:



```text

Document:

&#x20;   change-detection.md



Hash:

&#x20;   A81F3C...92D



Status:

&#x20;   embedded

```



When ingestion runs again:



```text

Current document

&#x20;      ↓

calculate SHA-256

&#x20;      ↓

compare with stored hash

```



If:



```text

currentHash == storedHash

```



the document does not need to be embedded again.



If:



```text

currentHash != storedHash

```



the document must be reprocessed.



\---



\# 11. Why Hashing Is Important



The backend server may restart frequently during development and deployment.



Without hashing:



```text

Server restart

&#x20;     ↓

read all documents

&#x20;     ↓

chunk all documents

&#x20;     ↓

embed all documents

&#x20;     ↓

upload all vectors

```



This wastes:



\* Embedding API calls

\* Money

\* Startup time

\* CPU/network resources



With hashing:



```text

Server restart

&#x20;     ↓

check knowledge state

&#x20;     ↓

unchanged

&#x20;     ↓

do nothing

```



Therefore, server restarts do not cause unnecessary embedding generation.



\---



\# 12. Persistent Vector Storage



Qdrant will store the generated embeddings.



The important distinction is:



```text

Knowledge files

&#x20;   =

Source of truth

```



while:



```text

Qdrant

&#x20;   =

Searchable vector index

```



The Markdown files remain in Git and are deployed with the backend.



Qdrant stores the generated representation used for semantic search.



\---



\# 13. Qdrant Architecture



The production architecture will be:



```text

&#x20;                   Spring Boot

&#x20;                        │

&#x20;             ┌──────────┴──────────┐

&#x20;             │                     │

&#x20;       Ingestion              Retrieval

&#x20;             │                     │

&#x20;             └──────────┬──────────┘

&#x20;                        │

&#x20;                        ↓

&#x20;                   Spring AI

&#x20;                        │

&#x20;                        ↓

&#x20;                   Qdrant Cloud

&#x20;                        │

&#x20;                        ↓

&#x20;                    Vectors

```



Qdrant should be treated as persistent infrastructure independent of the Spring Boot process.



Therefore:



```text

Spring Boot restart

&#x20;      ↓

Qdrant remains running

&#x20;      ↓

Existing vectors remain available

```



No embeddings need to be regenerated simply because the backend restarted.



\---



\# 14. Ingestion Lifecycle



The ingestion process should be separate from normal application startup.



\## Initial deployment



When the system is first deployed:



```text

Knowledge files

&#x20;     ↓

Calculate hashes

&#x20;     ↓

No existing knowledge in Qdrant

&#x20;     ↓

Chunk documents

&#x20;     ↓

Generate embeddings

&#x20;     ↓

Store vectors in Qdrant

&#x20;     ↓

Record document hashes

```



After this process completes, the knowledge base is ready.



\---



\# 15. Normal Server Restart



A normal restart should NOT rebuild the vector database.



```text

Spring Boot starts

&#x20;     ↓

Connect to Qdrant

&#x20;     ↓

Existing vectors available

&#x20;     ↓

AI assistant ready

```



No embedding API calls should be required.



Hash checking can be performed separately from normal startup.



\---



\# 16. New Deployment



When a new version of URLCheck is deployed:



```text

New backend version

&#x20;      ↓

Knowledge ingestion

&#x20;      ↓

Calculate document hashes

&#x20;      ↓

Compare against previous state

&#x20;      ↓

Find changed documents

```



For example:



```text

architecture.md       unchanged

authentication.md     unchanged

change-detection.md   changed

database.md           unchanged

```



Only:



```text

change-detection.md

```



is re-embedded.



\---



\# 17. Incremental Ingestion



The ingestion system should support incremental updates.



For each document:



```text

&#x20;            Document

&#x20;                │

&#x20;                ↓

&#x20;            SHA-256

&#x20;                │

&#x20;                ↓

&#x20;       ┌────────┴────────┐

&#x20;       │                 │

&#x20;   unchanged           changed

&#x20;       │                 │

&#x20;       ↓                 ↓

&#x20;     skip         delete old chunks

&#x20;                         ↓

&#x20;                   chunk document

&#x20;                         ↓

&#x20;                   generate embeddings

&#x20;                         ↓

&#x20;                    insert vectors

```



This prevents unnecessary API usage.



\---



\# 18. Chunking



Large knowledge documents should be divided into smaller chunks before embedding.



Example:



```text

change-detection.md

&#x20;       ↓

┌──────────────────────┐

│ Chunk 1              │

│ Purpose              │

└──────────────────────┘



┌──────────────────────┐

│ Chunk 2              │

│ Detection process    │

└──────────────────────┘



┌──────────────────────┐

│ Chunk 3              │

│ Important behavior   │

└──────────────────────┘

```



Chunk size should initially be kept simple.



The project should avoid excessive tuning until there is evidence that retrieval quality requires it.



\---



\# 19. Metadata



Every vector stored in Qdrant should include useful metadata.



Recommended metadata:



```text

source

category

document

documentHash

version

```



Example:



```json

{

&#x20; "source": "change-detection.md",

&#x20; "category": "business",

&#x20; "documentHash": "A81F3C...",

&#x20; "version": "0.3"

}

```



Metadata makes future filtering and document management easier.



Even if advanced metadata filtering is not required initially, metadata should be included from the beginning.



\---



\# 20. Retrieval



When the user asks a question:



```text

User

&#x20;↓

AI Controller

&#x20;↓

AI Agent

&#x20;↓

Knowledge Retrieval

&#x20;↓

Embedding of user question

&#x20;↓

Qdrant similarity search

&#x20;↓

Relevant knowledge chunks

&#x20;↓

LLM context

&#x20;↓

Answer

```



For example:



```text

Question:



"How does URLCheck determine if something changed?"

```



The retrieval system searches Qdrant and may return:



```text

change-detection.md

&#x20;   Chunk 2



"URLCheck calculates a SHA-256 hash..."

```



The retrieved information is then provided to the LLM.



\---



\# 21. Relationship With Conversation Memory



RAG and memory have different responsibilities.



\## Memory



Answers:



> What did we discuss previously?



Example:



```text

User:

I am building the URL monitoring feature.



Later:



User:

What database did I choose?



Memory:

The user previously discussed MySQL.

```



\## RAG



Answers:



> How does the application work?



Example:



```text

User:

How does URLCheck determine whether a URL changed?



RAG:

According to the system documentation,

URLCheck compares SHA-256 hashes.

```



Therefore:



```text

AI Context

│

├── Conversation Memory

│      └── Previous conversation

│

├── RAG

│      └── Stable application knowledge

│

└── Tools

&#x20;      └── Current application state/actions

```



These components should remain separate.



\---



\# 22. Relationship With Tools



RAG should not replace application tools.



For example:



\### RAG



Question:



> How does URLCheck detect changes?



Answer from documentation.



\### Tool



Question:



> Did my URL change today?



This requires current application data.



The AI should use a tool to access the actual monitored URL/check history.



Therefore:



```text

"What does the system do?"

&#x20;       ↓

&#x20;      RAG



"What happened to my URL?"

&#x20;       ↓

&#x20;     Tool



"What did we discuss?"

&#x20;       ↓

&#x20;     Memory

```



\---



\# 23. Proposed Package Structure



The AI package will become:



```text

ai/

├── agent/

│

├── config/

│

├── controller/

│

├── conversation/

│

├── deletion/

│

├── knowledge/

│   ├── ingestion/

│   │   ├── KnowledgeIngestionService

│   │   └── KnowledgeDocumentTracker

│   │

│   └── retrieval/

│       └── KnowledgeRetrievalService

│

├── memory/

│

├── message/

│

└── tool/

```



The knowledge source files remain under:



```text

src/main/resources/ai/knowledge/

```



\---



\# 24. Responsibilities



\## KnowledgeIngestionService



Responsible for:



\* Discovering knowledge documents

\* Reading documents

\* Calculating SHA-256 hashes

\* Determining whether documents changed

\* Chunking changed documents

\* Generating embeddings

\* Updating Qdrant

\* Updating document tracking information



It should NOT handle user conversations.



\---



\## KnowledgeDocumentTracker



Responsible for tracking:



\* Document path

\* Document hash

\* Document version

\* Processing status



Conceptually:



```text

DocumentTracker

&#x20;      │

&#x20;      ├── change-detection.md

&#x20;      │      hash: A81F...

&#x20;      │

&#x20;      ├── authentication.md

&#x20;      │      hash: C91B...

&#x20;      │

&#x20;      └── architecture.md

&#x20;             hash: 7F21...

```



\---



\## KnowledgeRetrievalService



Responsible for:



\* Receiving a knowledge query

\* Performing vector similarity search

\* Returning relevant knowledge documents/chunks

\* Applying basic metadata filtering when necessary



It should not modify the knowledge base.



\---



\# 25. Qdrant Collection



A dedicated Qdrant collection should be created for URLCheck knowledge.



For example:



```text

urlcheck\_knowledge

```



The collection contains:



```text

Vector

\+

Document content

\+

Metadata

```



Each document chunk corresponds to one vector.



Example:



```text

Qdrant

└── urlcheck\_knowledge

&#x20;   ├── architecture.md#chunk1

&#x20;   ├── architecture.md#chunk2

&#x20;   ├── change-detection.md#chunk1

&#x20;   ├── change-detection.md#chunk2

&#x20;   └── authentication.md#chunk1

```



\---



\# 26. Updating Documents



When a document changes, old chunks belonging to that document must be removed or replaced.



Example:



```text

Old:

change-detection.md

&#x20;├── chunk 1

&#x20;├── chunk 2

&#x20;└── chunk 3

```



After modification:



```text

Delete old chunks



↓



New:

change-detection.md

&#x20;├── chunk 1

&#x20;├── chunk 2

&#x20;├── chunk 3

&#x20;└── chunk 4

```



The new document hash is then recorded.



This prevents stale information from remaining in Qdrant.



\---



\# 27. Deployment Strategy



The knowledge files are included in the backend build:



```text

src/main/resources/

&#x20;       ↓

Maven build

&#x20;       ↓

Spring Boot JAR / Docker image

&#x20;       ↓

Deployment

```



Therefore the backend and its knowledge documentation always correspond to the same Git version.



Example:



```text

Git commit

&#x20;  │

&#x20;  ├── Java implementation

&#x20;  ├── AI implementation

&#x20;  └── Knowledge documents

```



This reduces the possibility of the AI describing an older implementation.



\---



\# 28. Recommended Ingestion Trigger



The ingestion operation should NOT automatically regenerate embeddings on every application startup.



Instead, it should be treated as a separate operation.



Possible implementation:



```text

POST /api/ai/knowledge/ingest

```



or a deployment/admin command.



The exact mechanism can be decided during implementation.



The important architectural rule is:



```text

Application startup

≠

Knowledge ingestion

```



This allows frequent server restarts without unnecessary embedding calls.



\---



\# 29. Development Workflow



During development:



```text

1\. Modify knowledge document

&#x20;       ↓

2\. Run ingestion

&#x20;       ↓

3\. Hash changes detected

&#x20;       ↓

4\. Only modified document is embedded

&#x20;       ↓

5\. Qdrant updated

```



If the backend is restarted without modifying knowledge:



```text

Restart backend

&#x20;       ↓

No embedding

&#x20;       ↓

Existing Qdrant vectors remain

```



\---



\# 30. Production Workflow



Production deployment:



```text

Developer

&#x20;  ↓

Modify code/knowledge

&#x20;  ↓

Git commit

&#x20;  ↓

Build backend

&#x20;  ↓

Deploy

&#x20;  ↓

Run knowledge ingestion

&#x20;  ↓

Only changed documents embedded

&#x20;  ↓

Qdrant updated

&#x20;  ↓

Application ready

```



\---



\# 31. Error Handling



The ingestion process should handle:



\### Embedding API failure



If embedding generation fails:



```text

Document

&#x20;  ↓

Embedding failed

&#x20;  ↓

Do NOT mark document as successfully processed

```



The system should be able to retry ingestion later.



\### Qdrant unavailable



If Qdrant cannot be reached:



```text

Qdrant connection failed

&#x20;      ↓

log error

&#x20;      ↓

do not delete existing knowledge

```



Existing vectors should not be destroyed simply because an update failed.



\### Partial ingestion failure



If one document fails:



```text

document A → success

document B → success

document C → failure

document D → success

```



The system should not need to re-embed A, B, and D.



Only C should remain pending.



\---



\# 32. Security



Qdrant credentials must not be hard-coded.



Configuration should use environment variables.



Conceptually:



```text

QDRANT\_URL

QDRANT\_API\_KEY

```



The actual values should be supplied through the deployment environment.



They must not be committed to Git.



\---



\# 33. Cost Optimization



The primary cost optimization mechanism is document hashing.



Without hashing:



```text

10 restarts

×

20 documents

=

200 potential embedding operations

```



With hashing:



```text

10 restarts

×

0 changed documents

=

0 embedding operations

```



If one document changes:



```text

10 restarts

\+

1 changed document

=

1 document re-embedded

```



The exact number of embedding requests depends on how documents are chunked and how the embedding provider batches requests, but the architectural principle remains the same:



> \*\*Embedding work should depend on changed knowledge, not server restart frequency.\*\*



\---



\# 34. Future Improvements



The following can be added later if necessary:



\## 34.1 Better retrieval



\* Hybrid keyword + vector search

\* Reranking

\* Query rewriting

\* Metadata filtering



\## 34.2 Better ingestion



\* Automatic deployment ingestion

\* Incremental document synchronization

\* Document version management

\* Batch embedding



\## 34.3 Knowledge management



\* Admin UI

\* Runtime knowledge editing

\* Knowledge approval workflow

\* Multiple knowledge collections



These are deliberately excluded from the initial implementation.



\---



\# 35. Final Architecture



The complete AI architecture is:



```text

&#x20;                             USER

&#x20;                               │

&#x20;                               ↓

&#x20;                        AI Controller

&#x20;                               │

&#x20;                               ↓

&#x20;                          AI Agent

&#x20;                               │

&#x20;             ┌─────────────────┼─────────────────┐

&#x20;             │                 │                 │

&#x20;             ↓                 ↓                 ↓

&#x20;          Memory              RAG              Tools

&#x20;             │                 │                 │

&#x20;             │                 ↓                 │

&#x20;             │            Retrieval             │

&#x20;             │                 │                 │

&#x20;             │                 ↓                 │

&#x20;             │             Qdrant                │

&#x20;             │                 │                 │

&#x20;             │          Relevant chunks          │

&#x20;             │                 │                 │

&#x20;             └─────────────────┼─────────────────┘

&#x20;                               │

&#x20;                               ↓

&#x20;                             LLM

&#x20;                               │

&#x20;                               ↓

&#x20;                            Answer





Knowledge Ingestion

──────────────────────────────────────────────────



src/main/resources/ai/knowledge/

&#x20;             │

&#x20;             ↓

&#x20;      Markdown documents

&#x20;             │

&#x20;             ↓

&#x20;         SHA-256 hash

&#x20;             │

&#x20;             ↓

&#x20;     ┌───────┴────────┐

&#x20;     │                │

&#x20; unchanged          changed

&#x20;     │                │

&#x20;     ↓                ↓

&#x20;    skip       chunk + embed

&#x20;                      │

&#x20;                      ↓

&#x20;                   Qdrant

```



\---



\# 36. Design Principles



The implementation should follow these principles:



1\. \*\*Knowledge files are the source of truth.\*\*

2\. \*\*Qdrant is the persistent search index, not the primary source of knowledge.\*\*

3\. \*\*Ingestion is separate from application startup.\*\*

4\. \*\*Document hashes prevent unnecessary embedding.\*\*

5\. \*\*Only changed documents should be re-embedded.\*\*

6\. \*\*Server restarts must not cause unnecessary embedding API calls.\*\*

7\. \*\*RAG, memory, and tools have different responsibilities.\*\*

8\. \*\*Start with simple vector similarity retrieval.\*\*

9\. \*\*Do not introduce reranking or hybrid search until retrieval quality requires it.\*\*

10\. \*\*Knowledge documentation should be version-controlled together with the implementation.\*\*

11\. \*\*Qdrant credentials must be supplied through environment configuration.\*\*

12\. \*\*The initial implementation should prioritize reliability and simplicity over advanced RAG features.\*\*



\---



\# 37. Implementation Scope for V1



The first implementation should contain only:



```text

1\. Markdown knowledge files

2\. Knowledge document loader

3\. SHA-256 hashing

4\. Document change detection

5\. Document chunking

6\. Embedding generation

7\. Qdrant integration

8\. Incremental ingestion

9\. Basic vector similarity retrieval

10\. Integration with existing Spring AI agent

```



The initial system does not need:



```text

❌ Reranker

❌ Hybrid search

❌ Query rewriting

❌ Knowledge admin UI

❌ Runtime document editing

❌ Automatic file watcher

❌ Multiple vector databases

```



This keeps the RAG feature small while providing a solid foundation for future expansion.



