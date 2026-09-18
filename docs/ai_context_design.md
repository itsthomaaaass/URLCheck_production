# URLCheck AI Assistant — Conversation History & Memory Design

## 1. Overview

### 1.1 Purpose

This document specifies the design for adding **conversation history** and **conversation memory** to the existing URLCheck AI Assistant.

The current AI Assistant processes each user prompt independently:

```text
User Input
    ↓
AI Assistant
    ↓
LLM
    ↓
Response
```

As a result, the assistant does not have sufficient conversational context to correctly understand follow-up questions such as:

```text
User: What is URL monitoring?
AI: URL monitoring periodically checks a URL...

User: How often should I do it?
```

The second message does not inherently contain enough information to determine what "it" refers to.

This feature will introduce:

1. **Conversation** — represents an individual chat session/thread.
2. **Conversation History** — permanently stores messages belonging to conversations.
3. **Conversation Memory** — provides relevant recent conversation context to the LLM through Spring AI.
4. **Conversation/User Association** — ensures users can only access their own conversations.

The existing AI agent, tools, deletion functionality, URL monitoring functionality, authentication, and other application features should remain unchanged.

---

# 2. Goals

## 2.1 Primary Goals

The feature should:

* Allow users to have multiple independent conversations.
* Assign every conversation a unique `conversationId`.
* Persist conversation history in MySQL.
* Associate conversations with the authenticated user.
* Allow the frontend to retrieve previous conversations.
* Allow users to continue an existing conversation.
* Provide conversational context to the LLM.
* Use Spring AI's built-in `ChatMemory` facilities instead of implementing custom LLM memory logic.
* Keep the complete conversation history separate from the limited context supplied to the LLM.
* Prevent users from accessing conversations belonging to other users.

## 2.2 Non-Goals

This feature does not initially include:

* RAG / vector databases.
* Automatic long-term user profiling.
* Semantic memory.
* Summarization of very long conversations.
* AI-generated conversation titles.
* Changes to existing URL monitoring functionality.
* Changes to existing AI tools.
* Changes to authentication/session architecture.

These can be added later.

---

# 3. Design Principles

The implementation follows three important principles.

### 3.1 History and Memory are different

**Conversation history** is the complete record of a conversation.

**Conversation memory** is the subset of conversation information supplied to the LLM.

Therefore:

```text
                Complete History
                     │
                     │
                  MySQL
                     │
                     │ relevant/recent messages
                     ▼
                Chat Memory
                     │
                     ▼
                    LLM
```

The application should not assume that every message in the database needs to be sent to the LLM.

---

### 3.2 MySQL is the source of truth for application history

The application's persistent conversation history should be stored in MySQL.

Spring AI `ChatMemory` should be treated as the mechanism used to provide conversational context to the model, rather than as the application's primary UI history database.

---

### 3.3 Conversation identity is independent from HTTP session identity

The existing authenticated user session identifies **who the user is**.

The `conversationId` identifies **which conversation the user is interacting with**.

```text
HTTP Session
     │
     ▼
   userId
     │
     ├───────────────┐
     │               │
     ▼               ▼
Conversation A   Conversation B
```

The HTTP session should therefore not be used directly as the conversation ID.

---

# 4. High-Level Architecture

The existing AI folder:

```text
ai/
├─ agent/
├─ config/
├─ controller/
├─ deletion/
└─ tool/
```

will be extended to:

```text
ai/
├─ agent/
├─ config/
├─ controller/
├─ conversation/
├─ message/
├─ deletion/
└─ tool/
```

The responsibilities are:

```text
agent/
    AI agent orchestration and interaction with ChatClient

config/
    Spring AI and ChatMemory configuration

controller/
    HTTP/API endpoints

conversation/
    Conversation lifecycle and ownership

message/
    Persistent conversation messages

deletion/
    Existing deletion-related AI functionality

tool/
    Existing AI tools and URLCheck application tools
```

A separate `memory/` package is not required initially because Spring AI's memory mechanism can be configured under `config/`.

---

# 5. Logical System Architecture

The complete logical architecture will be:

```text
                         ┌──────────────┐
                         │   Frontend   │
                         └──────┬───────┘
                                │
                                │
                    message + conversationId
                                │
                                ▼
                    ┌────────────────────┐
                    │    AiController    │
                    └─────────┬──────────┘
                              │
                              ▼
                    ┌────────────────────┐
                    │       AiAgent      │
                    └─────────┬──────────┘
                              │
                              ▼
                       ┌─────────────┐
                       │  ChatClient │
                       └──────┬──────┘
                              │
               ┌──────────────┼───────────────┐
               │              │               │
               ▼              ▼               ▼
        Chat Memory      Existing Tools    LLM
               │              │
               │              ▼
               │        URLCheck Services
               │              │
               ▼              ▼
          Conversation       MySQL
            context
```

Conversation persistence exists alongside the AI request flow:

```text
                         User Request
                              │
                              ▼
                     AiController
                              │
                              ▼
                 Verify conversation ownership
                              │
                              ▼
                     AiAgent
                              │
               ┌──────────────┴──────────────┐
               │                             │
               ▼                             ▼
       Store user message              ChatClient
               │                             │
               ▼                             ▼
             MySQL                    ChatMemory Advisor
                                             │
                                             ▼
                                            LLM
                                             │
                                             ▼
                                      AI response
                                             │
                                             ▼
                                     Store AI message
                                             │
                                             ▼
                                           MySQL
```

---

# 6. Data Model

## 6.1 Conversation

A conversation represents one independent chat thread.

Suggested table:

```text
conversation
-------------------------
id
user_id
title
created_at
updated_at
```

### Fields

| Field        | Description                       |
| ------------ | --------------------------------- |
| `id`         | Unique conversation identifier    |
| `user_id`    | Owner of the conversation         |
| `title`      | Human-readable conversation title |
| `created_at` | Creation timestamp                |
| `updated_at` | Last activity timestamp           |

The `user_id` references the existing user/account system.

Relationship:

```text
User 1 ───────── N Conversation
```

---

## 6.2 Message

A message represents one message within a conversation.

Suggested table:

```text
message
-------------------------
id
conversation_id
role
content
created_at
```

### Fields

| Field             | Description                                          |
| ----------------- | ---------------------------------------------------- |
| `id`              | Unique message identifier                            |
| `conversation_id` | Conversation containing the message                  |
| `role`            | `USER`, `ASSISTANT`, or other supported message role |
| `content`         | Message text                                         |
| `created_at`      | Message creation timestamp                           |

Relationship:

```text
Conversation 1 ───────── N Message
```

Example:

```text
Conversation 101
│
├── Message 1
│   USER: What is URL monitoring?
│
├── Message 2
│   ASSISTANT: URL monitoring periodically checks...
│
├── Message 3
│   USER: How often should I check it?
│
└── Message 4
    ASSISTANT: The appropriate frequency depends...
```

---

# 7. Conversation ID

The `conversationId` is the identifier used to distinguish independent conversations.

Example:

```text
User 42
│
├── Conversation 101
│
├── Conversation 102
│
└── Conversation 103
```

The frontend sends the ID when continuing an existing conversation:

```json
{
  "conversationId": 101,
  "message": "How often should I check it?"
}
```

For a new conversation, the frontend can omit the ID or explicitly request creation of a new conversation.

The backend creates the conversation and returns its ID.

---

# 8. User and Conversation Security

Every conversation belongs to exactly one user.

The backend must verify ownership before allowing access.

Request:

```text
conversationId = 101
```

Authenticated session:

```text
userId = 42
```

Backend checks:

```text
Does conversation 101 belong to user 42?
```

If:

```text
YES → continue
NO  → reject request
```

The frontend must not be trusted to establish ownership.

The backend should always derive the authenticated `userId` from the existing session/authentication mechanism.

---

# 9. Conversation API

Endpoint names follow the existing `/api/ai` prefix (the AI controller is
mapped at `/api/ai`):

```text
POST   /api/ai/conversations
GET    /api/ai/conversations
GET    /api/ai/conversations/{id}
DELETE /api/ai/conversations/{id}

POST   /api/ai/chat          (existing endpoint, extended with conversationId)
```

All new endpoints must carry the same
`@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")`
gating as the existing `AiController`, so a deployment with the assistant off
exposes none of them.

## 9.1 Create Conversation

```text
POST /api/ai/conversations
```

Creates a new conversation for the authenticated user.

Response:

```json
{
  "id": 101,
  "title": "New conversation"
}
```

---

## 9.2 List Conversations

```text
GET /api/ai/conversations
```

Returns conversations belonging to the current user.

Example:

```json
[
  {
    "id": 101,
    "title": "URL monitoring",
    "updatedAt": "2026-09-17T18:30:00"
  },
  {
    "id": 102,
    "title": "Troubleshooting",
    "updatedAt": "2026-09-16T14:20:00"
  }
]
```

---

## 9.3 Get Conversation History

```text
GET /api/ai/conversations/{id}
```

Returns the messages belonging to the specified conversation, after verifying ownership.

Example:

```json
{
  "id": 101,
  "title": "URL monitoring",
  "messages": [
    {
      "role": "USER",
      "content": "What is URL monitoring?"
    },
    {
      "role": "ASSISTANT",
      "content": "URL monitoring periodically checks..."
    }
  ]
}
```

---

# 10. Chat Request

The existing AI chat endpoint (`POST /api/ai/chat`) should be extended to
accept a conversation ID.

The existing request record `ChatRequest(String message, String confirmationToken)`
in `AiController` must be **extended** with `conversationId`, not replaced: the
deletion-confirmation flow (`confirmationToken`) must keep working unchanged.

Example:

```json
{
  "conversationId": 101,
  "message": "How often should I check it?"
}
```

The request flow becomes:

```text
1. Receive request

2. Obtain authenticated userId

3. Validate conversationId

4. Verify conversation belongs to user

5. Store USER message

6. Call AiAgent

7. Pass conversationId to Spring AI

8. Spring AI retrieves conversational memory

9. LLM generates response

10. Store ASSISTANT message

11. Return response
```

---

# 11. Spring AI Memory

Spring AI should provide the actual conversational memory mechanism.

The application should use:

```text
ChatMemory
```

and:

```text
MessageChatMemoryAdvisor
```

Both exist in the Spring AI version this project uses (2.0.1, see
`backend/pom.xml`), under `org.springframework.ai.chat.memory` and
`org.springframework.ai.chat.client.advisor`. `ChatMemory.CONVERSATION_ID` is
the parameter key that selects a conversation.

The conceptual configuration is:

```text
ChatClient
    │
    └── MessageChatMemoryAdvisor
                │
                ▼
            ChatMemory
```

The advisor must be **registered on the `ChatClient` itself** — for example
when the client is built in `AiConfig` (or a new `ChatMemoryConfig`):

```java
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

// Register the advisor as a default so every prompt passes through it.
ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();

ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .build();
```

Expose the `ChatMemory` as a bean too, so conversation deletion can clear it
(§17) and a restart can repopulate it from MySQL history (§13).

The assistant request then supplies:

```text
ChatMemory.CONVERSATION_ID
```

For example:

```java
chatClient.prompt()
    .user(userMessage)
    .advisors(advisor -> advisor
        .param(
            ChatMemory.CONVERSATION_ID,
            conversationId
        )
    )
    .call()
    .content();
```

The conversation ID acts as the lookup key for the appropriate memory.

Setting only the parameter without registering the advisor applies no memory:
the request snippet above works only together with the `defaultAdvisors(...)`
wiring. And once the advisor is registered, **every** prompt must supply a
conversation ID (the controller creates a conversation first when the client
sent none), because the advisor requires it.

---

# 12. Memory vs Persistent History

The system should conceptually maintain:

```text
                   MySQL
                     │
             Complete history
                     │
                     ▼
              ChatMemory
                     │
             Recent/relevant
               context
                     │
                     ▼
                    LLM
```

For example, a conversation may contain:

```text
100 messages
```

but the LLM may only receive:

```text
the most recent N messages
```

This avoids continuously increasing the LLM prompt size.

Spring AI's `MessageWindowChatMemory` can be used when a bounded message window is desired.

---

# 13. Synchronization Between History and Memory

The application should ensure that both user and assistant messages are represented consistently.

Conceptually:

```text
User message
    │
    ├──► Persistent History
    │
    └──► Chat Memory

AI response
    │
    ├──► Persistent History
    │
    └──► Chat Memory
```

However, the exact synchronization mechanism should follow the Spring AI version and configured `ChatMemoryRepository`.

Note on restarts: an in-memory `ChatMemory` is empty after a backend restart
while the MySQL history survives. Either accept cold restarts (memory is a
bounded cache of recent context), or repopulate memory from the stored
history when a conversation is continued — e.g. feed the most recent
persisted messages into `ChatMemory` before calling the model.

The important architectural requirement is:

```text
Persistent history = application record

ChatMemory = LLM context mechanism
```

The implementation should avoid maintaining a completely separate custom memory system unless a future requirement makes it necessary.

---

# 14. Recommended Package Responsibilities

## `ai/agent`

Existing responsibility remains unchanged.

Example:

```text
AiAgent
```

(The existing `com.urlcheck.ai.agent.AiAgent` stays in place.)

Responsible for AI interaction/orchestration.

It should not become responsible for:

* database persistence
* user authentication
* conversation ownership
* frontend conversation management

---

## `ai/conversation`

Responsible for:

* Creating conversations
* Finding conversations
* Updating conversation metadata
* Checking ownership
* Deleting conversations
* Managing conversation lifecycle

Example:

```text
Conversation
ConversationService
ConversationMapper
```

---

## `ai/message`

Responsible for:

* Creating messages
* Retrieving messages
* Persisting user/assistant messages
* Mapping database records to application objects

Example:

```text
ChatMessage
ChatMessageService
ChatMessageMapper
```

---

## `ai/config`

Responsible for:

* ChatClient configuration
* ChatMemory configuration
* Spring AI advisors
* LLM configuration

Example:

```text
AiConfig
ChatMemoryConfig
```

---

## `ai/controller`

Responsible for:

* Receiving HTTP requests
* Extracting authenticated user information
* Calling services
* Returning HTTP responses

It should not contain LLM memory logic or SQL logic.

---

# 15. Request Lifecycle

A complete chat request should follow this sequence:

```text
Frontend
   │
   │ POST /api/ai/chat
   │ conversationId=101
   │ message="How often should I check it?"
   ▼
AiController
   │
   │ obtain userId from session
   ▼
ConversationService
   │
   │ verify:
   │ conversation 101 belongs to user 42
   ▼
MessageService
   │
   │ store USER message
   ▼
AiAgent
   │
   ▼
ChatClient
   │
   │ conversationId = "101"
   ▼
MessageChatMemoryAdvisor
   │
   ▼
ChatMemory
   │
   │ retrieve previous context
   ▼
LLM
   │
   ▼
AI response
   │
   ▼
MessageService
   │
   │ store ASSISTANT message
   ▼
AiController
   │
   ▼
Frontend
```

---

# 16. Example Conversation

Initial request:

```text
User:
What is URL monitoring?
```

The application creates:

```text
conversationId = 101
```

History:

```text
Conversation 101
├── USER: What is URL monitoring?
└── ASSISTANT: URL monitoring periodically checks...
```

Next request:

```text
User:
How often should I check it?
```

Frontend sends:

```json
{
  "conversationId": 101,
  "message": "How often should I check it?"
}
```

Spring AI receives:

```text
Conversation ID: 101

Previous context:
User: What is URL monitoring?
Assistant: URL monitoring periodically checks...

Current message:
How often should I check it?
```

The LLM can therefore understand that "it" refers to URL monitoring.

---

# 17. Conversation Deletion

The existing `deletion` functionality should be extended rather than replaced.

Deleting a conversation should normally delete:

```text
Conversation
    │
    └── Messages
```

The backend must verify ownership before deletion.

Conceptually:

```text
DELETE /api/ai/conversations/101
        │
        ▼
verify user owns conversation
        │
        ▼
delete messages
        │
        ▼
delete conversation
```

The implementation should also ensure that stale memory associated with the
conversation cannot continue to affect future requests: when a conversation is
deleted, clear its entry from `ChatMemory` (`ChatMemory.clear(conversationId)`,
or `ChatMemoryRepository.deleteByConversationId(conversationId)` when a
repository-backed memory is used).

The existing app-owned confirmation handshake (`PendingDeletions` and the
`confirmationToken` in the chat request) remains unchanged and stays outside
the model: even though conversation memory now lets the model remember its
earlier deletion proposals, a confirmation is still resolved by the backend
from the batch the session holds, never by the model.

---

# 18. Error Handling

Suggested cases:

### Conversation does not exist

```text
404 Not Found
```

### Conversation belongs to another user

Preferably:

```text
404 Not Found
```

rather than exposing whether another user's conversation exists.

### Missing conversation ID

If the API requires an existing conversation:

```text
400 Bad Request
```

If missing ID means "create a new conversation", the backend may create one automatically.

### Empty message

```text
400 Bad Request
```

---

# 19. Initial Implementation Scope

The first implementation should contain only:

```text
Conversation
    ↓
Message History
    ↓
Spring AI ChatMemory
    ↓
ChatClient
    ↓
LLM
```

Do not initially implement:

```text
RAG
Long-term semantic memory
Automatic summarization
Complex memory retrieval
AI-generated conversation titles
```

These can be added independently later.

---

# 20. Future AI Architecture

After conversation memory is working, the AI architecture can evolve into:

```text
                         AI Assistant
                              │
                         ChatClient
                              │
          ┌───────────────────┼───────────────────┐
          │                   │                   │
          ▼                   ▼                   ▼
   Conversation          RAG / Knowledge       Tools
      Memory                   │                   │
          │                    ▼                   ▼
          ▼                Vector Store       URLCheck
   Recent Context         Documentation       Services
          │                                        │
          └───────────────────┬────────────────────┘
                              │
                              ▼
                             LLM
```

The three components have different responsibilities:

### Conversation Memory

```text
"What were we talking about?"
```

### RAG

```text
"What does the documentation say?"
```

### Tools

```text
"What is the actual state of this user's URLCheck data?"
```

This separation should be maintained as the AI feature becomes more sophisticated.

---

# 21. Final Folder Structure

Recommended structure after this feature:

```text
ai/
├─ agent/
│  └─ AiAgent.java
│
├─ config/
│  ├─ AiConfig.java
│  ├─ AiProperties.java
│  └─ ChatMemoryConfig.java
│
├─ controller/
│  ├─ AiController.java
│  └─ ConversationController.java
│
├─ conversation/
│  ├─ Conversation.java
│  ├─ ConversationService.java
│  └─ ConversationMapper.java
│
├─ message/
│  ├─ ChatMessage.java
│  ├─ ChatMessageService.java
│  └─ ChatMessageMapper.java
│
├─ deletion/
│
└─ tool/
```

The core dependency direction should remain:

```text
Controller
    ↓
Service / Agent
    ↓
Spring AI / Domain Services
    ↓
Repository / Mapper
    ↓
MySQL
```

rather than allowing controllers or the AI agent to directly manipulate database records.

---

# 22. Summary

The feature introduces two related but distinct concepts:

```text
Conversation History
        │
        │ complete, persistent
        ▼
       MySQL


Conversation Memory
        │
        │ recent/relevant context
        ▼
       LLM
```

A `conversationId` connects the frontend request, persistent conversation history, and Spring AI memory.

The authenticated `userId` determines ownership:

```text
HTTP Session
     ↓
   userId
     ↓
Conversation
     ↓
Messages
```

The existing AI agent remains responsible for interacting with the LLM and existing tools, while conversation and message modules handle persistent chat data.

This architecture provides a clean foundation for subsequently adding **RAG and URLCheck-specific AI tools without restructuring the existing application**.

---

# 23. Retention and Failure Behaviour

The sections above describe the architecture. This one records the decisions the
implementation had to make, and the reasoning, because they are the parts where
a reader would otherwise have to guess.

## 23.1 How many conversations are kept

A user keeps at most `app.ai.max-conversations` conversations, `5` by default and
set with `AI_MAX_CONVERSATIONS`. The cap is **per user**, not per deployment: a
global cap would let one user's activity delete another user's conversation.

"The most recent conversations" means the most recently *used*, not the most
recently created. Ordering is `conversation.updated_at DESC, id DESC`, and
`updated_at` is refreshed on every turn. A thread the user returns to after a
month therefore becomes recent again, and what gets dropped is the thread they
have actually stopped using - least recently used, not first in first out.

Enforcement lives in one method, `ConversationService.enforceRetention`, reached
from the two moments the set can grow:

```text
open a conversation   ->  enforceRetention
every chat turn       ->  touch -> enforceRetention
```

The conversations past the cap are deleted, oldest activity first. Their messages
are not deleted separately: `chat_message.conversation_id` is
`ON DELETE CASCADE`, so the history of a dropped conversation goes with it.

MySQL has no `DELETE ... RETURNING`, so the rows to drop are read first
(`findIdsBeyondRetention`) and then deleted by id. That read is also what makes
the deletion safe to explain to the rest of the application: the ids of the
conversations that went are published as `ConversationsEvictedEvent`, and
`ConversationMemory` listens for it and discards their window. A conversation
that no longer exists must not keep answering through memory that outlived it.

A configured cap below `1` is clamped up to `1`, since a cap of zero would delete
the conversation being opened.

## 23.2 What happens when the provider fails

**Decision: the user's message is kept, and the turn is left without an answer.**

The question is written to MySQL before the model is called. If the provider call
then fails, that row stays. The failure is not a reason to discard something the
user typed, and because the assistant's answer is written in a second, separate
transaction, the alternative would be a turn that is half-recorded - a question
and an answer that exist or vanish together, which is harder to reason about than
an unanswered question.

What follows from that:

```text
Provider call fails
        |
        +-- the USER message stays in chat_message
        +-- no ASSISTANT message is written
        +-- the request still answers 503, as before
        +-- the next turn replays the unanswered question to the model
```

The last point is worth stating plainly: the memory window is rebuilt from the
stored history before every turn, so the unanswered question is inside the
context the model next sees. The user does not have to repeat themselves, and
nothing is silently lost.

Two things the failure path deliberately does not do. It does not retry on the
user's behalf beyond `app.ai.max-retries`, which the provider client already
handles. And it does not write a placeholder message such as "the assistant could
not answer": the history is the record of what was actually said, and the client
shows the error from the `503` itself.

A turn that fails is still a turn the user took, so `updated_at` has already been
refreshed and the retention cap has already been applied. Retention does not
depend on the provider being reachable.

## 23.3 Decisions this document left open

Four details had no answer in the sections above. They are recorded here so the
implementation is not the only place the reasoning exists.

**Titles.** Section 2.2 rules out AI-generated titles, so a conversation is named
after the first user message: the first 60 code points, whitespace collapsed to
single spaces, with an ellipsis when it was cut. A title is a handle in a list,
not a summary, and naming it from the user's own words costs no second provider
call. A conversation created without a first message is titled `新对话`, matching
the language of the rest of the assistant's user-facing text.

**Table name.** Section 6.2 calls the table `message` while section 21 calls the
Java type `ChatMessage`. The table is `chat_message`, so the row and the type it
is read into carry the same name, and a table called `message` does not sit in the
schema as a word with no meaning of its own.

**History to memory.** Section 13 offered either accepting cold restarts or
repopulating memory from the stored history, and did not choose. The window is
repopulated, and it is rebuilt from MySQL before *every* turn rather than being
accumulated in memory. That makes a restart, a second instance and a conversation
dropped by retention all equally harmless, because the context the model sees can
never be staler or fuller than the record it was derived from. The cost is the
one indexed read per turn.

**Memory is not persisted.** Consistent with section 3.2, `ChatMemory` remains a
window over the record rather than a second copy of it. Only the messages of the
current conversation, bounded by `app.ai.memory-max-messages`, are ever held.

One thing that did *not* change: the confirmation handshake (section 17). A
confirmation is still resolved by the backend from the batch the session holds and
never returns to the model. The system prompt was reworded to say that the app
answers the confirmation, instead of the now-misleading claim that a later "yes"
reaches the model with no memory of the request - the model does remember the
request, it simply never sees the reply.
