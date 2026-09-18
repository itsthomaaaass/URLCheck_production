import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import {
  chatWithAssistant,
  deleteConversation,
  fetchConversation,
  fetchConversations,
  type AiConfirmation,
  type AiConversationSummary
} from "./api";
import { renderMarkdown } from "./markdown";

interface ChatMessage {
  role: "user" | "assistant";
  text: string;
  /** True when this bubble is a failure, so it can be styled as one. */
  error?: boolean;
}

/**
 * The assistant, as a button in the bottom-right corner that opens a chat
 * panel. The panel starts closed so it never covers the URL list unasked.
 *
 * Inside, the panel is laid out like a desktop chat client: the user's past
 * conversations down the left, the open thread and its composer on the right.
 * The history rail is shown by default and can be folded away.
 *
 * Starting a new chat does not call the backend. The conversation is opened by
 * the first message, which is what names the thread, and which keeps an
 * abandoned "new chat" from spending one of the few conversations a user keeps.
 *
 * Deleting a URL goes through the backend's confirmation handshake: the first
 * answer only proposes the deletion, and the rows that are removed when the user
 * confirms come from the backend's stored proposal, not from this component.
 */
export default function AiChat({ onUrlsChanged }: { onUrlsChanged: () => void }) {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [confirmation, setConfirmation] = useState<AiConfirmation | null>(null);
  const [conversationId, setConversationId] = useState<number | null>(null);
  const [conversations, setConversations] = useState<AiConversationSummary[]>([]);
  const [historyOpen, setHistoryOpen] = useState(true);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [openingThread, setOpeningThread] = useState(false);
  const endRef = useRef<HTMLDivElement | null>(null);

  const loadConversations = useCallback(async () => {
    try {
      setConversations(await fetchConversations());
      setHistoryError(null);
    } catch (err) {
      setHistoryError(err instanceof Error ? err.message : "无法读取历史会话");
    }
  }, []);

  // Keep the newest message in view as it arrives.
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "nearest" });
  }, [messages, confirmation, busy, openingThread]);

  // The history is read when the panel is opened rather than on page load: a
  // user who never opens the assistant never asks for it.
  useEffect(() => {
    if (!open) {
      return;
    }
    setHistoryLoading(true);
    void loadConversations().finally(() => setHistoryLoading(false));
  }, [open, loadConversations]);

  useEffect(() => {
    if (!open) {
      return;
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setOpen(false);
      }
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [open]);

  async function ask(message: string, confirmationToken?: string) {
    setBusy(true);
    try {
      const reply = await chatWithAssistant(message, conversationId, confirmationToken);
      setMessages((current) => [...current, { role: "assistant", text: reply.message }]);
      setConfirmation(reply.confirmation);
      if (reply.conversationId !== null) {
        setConversationId(reply.conversationId);
      }
      // The assistant may have created or deleted a URL. An answer that only
      // asks for confirmation has changed nothing yet, so the list is left
      // alone until the deletion actually happens.
      if (reply.confirmation === null) {
        onUrlsChanged();
      }
    } catch (err) {
      setMessages((current) => [
        ...current,
        {
          role: "assistant",
          text: err instanceof Error ? err.message : "请求失败",
          error: true
        }
      ]);
    } finally {
      setBusy(false);
      // A first message opens a conversation and a failed one still stores the
      // question, so the history is refreshed either way. What the answer to a
      // failure does not carry is the id of a conversation it just opened;
      // that thread is reached from the list instead.
      void loadConversations();
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const message = draft.trim();
    if (message === "" || busy || confirmation !== null) {
      return;
    }
    setDraft("");
    setMessages((current) => [...current, { role: "user", text: message }]);
    await ask(message);
  }

  async function handleConfirm() {
    if (confirmation === null || busy) {
      return;
    }
    const token = confirmation.token;
    setConfirmation(null);
    setMessages((current) => [...current, { role: "user", text: "确认删除" }]);
    await ask("", token);
  }

  function handleCancelConfirm() {
    setConfirmation(null);
    setMessages((current) => [
      ...current,
      { role: "assistant", text: "已取消，没有删除任何 URL。" }
    ]);
  }

  /** An empty thread. The first message opens it, so nothing is sent here. */
  function startNewChat() {
    setConversationId(null);
    setMessages([]);
    setConfirmation(null);
    setDraft("");
  }

  async function openThread(id: number) {
    if (id === conversationId || openingThread) {
      return;
    }
    setOpeningThread(true);
    // A confirmation belongs to the thread it was proposed in, so switching
    // threads drops the prompt rather than carrying it across.
    setConfirmation(null);
    try {
      const thread = await fetchConversation(id);
      setConversationId(thread.id);
      setMessages(
        thread.messages.map((message): ChatMessage => ({
          role: message.role === "USER" ? "user" : "assistant",
          text: message.content
        }))
      );
    } catch (err) {
      setHistoryError(err instanceof Error ? err.message : "无法打开该会话");
    } finally {
      setOpeningThread(false);
    }
  }

  async function handleDeleteThread(summary: AiConversationSummary) {
    if (busy) {
      return;
    }
    if (!window.confirm(`确定删除会话「${summary.title}」？删除后无法恢复。`)) {
      return;
    }
    try {
      await deleteConversation(summary.id);
      if (summary.id === conversationId) {
        startNewChat();
      }
      await loadConversations();
    } catch (err) {
      setHistoryError(err instanceof Error ? err.message : "无法删除该会话");
    }
  }

  const openTitle =
    conversationId === null
      ? "新对话"
      : conversations.find((item) => item.id === conversationId)?.title ?? "AI 助手";

  return (
    <div className="ai-widget">
      {open && (
        <section className="ai-panel" aria-label="AI 助手">
          {historyOpen && (
            <aside className="ai-sidebar" aria-label="会话历史">
              <div className="ai-sidebar-head">
                <button type="button" className="ai-new" onClick={startNewChat}>
                  <PlusIcon />
                  新对话
                </button>
              </div>

              {historyError && <p className="ai-history-note error">{historyError}</p>}

              <ul className="ai-history">
                {historyLoading && conversations.length === 0 && (
                  <li className="ai-history-note">加载中…</li>
                )}
                {!historyLoading && conversations.length === 0 && !historyError && (
                  <li className="ai-history-note">还没有历史会话</li>
                )}
                {conversations.map((item) => (
                  <li
                    key={item.id}
                    className={`ai-history-item${item.id === conversationId ? " active" : ""}`}
                  >
                    <button
                      type="button"
                      className="ai-history-open"
                      onClick={() => openThread(item.id)}
                      title={item.title}
                    >
                      {item.title}
                    </button>
                    <button
                      type="button"
                      className="ai-history-delete"
                      onClick={() => handleDeleteThread(item)}
                      aria-label={`删除会话：${item.title}`}
                      title="删除会话"
                    >
                      ×
                    </button>
                  </li>
                ))}
              </ul>

              <p className="ai-sidebar-foot">只保留最近使用的会话，更早的会自动删除。</p>
            </aside>
          )}

          <div className="ai-main">
            <header className="ai-head">
              <button
                type="button"
                className="ai-toggle"
                onClick={() => setHistoryOpen((current) => !current)}
                aria-expanded={historyOpen}
                aria-label={historyOpen ? "收起会话历史" : "展开会话历史"}
                title={historyOpen ? "收起会话历史" : "展开会话历史"}
              >
                <PanelIcon />
              </button>
              <span className="ai-title" title={openTitle}>
                {openTitle}
              </span>
              <button
                type="button"
                className="ai-close"
                onClick={() => setOpen(false)}
                aria-label="关闭"
              >
                ×
              </button>
            </header>

            <div
              className={`ai-messages${
                messages.length === 0 && !openingThread && confirmation === null ? " empty" : ""
              }`}
            >
              {openingThread && <p className="ai-hint">加载中…</p>}

              {!openingThread && messages.length === 0 && (
                <p className="ai-hint">
                  用自然语言管理被监控的 URL。例如：
                  <br />
                  「哪些 URL 无法访问？」
                  <br />
                  「添加 https://example.com，名字叫示例」
                  <br />
                  「删掉示例」
                </p>
              )}

              {!openingThread &&
                messages.map((message, index) => (
                  <div
                    key={index}
                    className={`ai-message ${message.role}${message.error ? " error" : ""}`}
                  >
                    {message.role === "assistant" && !message.error
                      ? renderMarkdown(message.text)
                      : message.text}
                  </div>
                ))}

              {confirmation && (
                <div className="ai-confirm">
                  <p>确认删除以下 {confirmation.urls.length} 个 URL？删除后无法恢复。</p>
                  <ul>
                    {confirmation.urls.map((item) => (
                      <li key={item.id}>
                        {item.name}（{item.url}）
                      </li>
                    ))}
                  </ul>
                  <div className="ai-confirm-actions">
                    <button
                      type="button"
                      className="primary-btn"
                      onClick={handleConfirm}
                      disabled={busy}
                    >
                      确认删除
                    </button>
                    <button
                      type="button"
                      className="ghost-btn"
                      onClick={handleCancelConfirm}
                      disabled={busy}
                    >
                      取消
                    </button>
                  </div>
                </div>
              )}

              <div ref={endRef} />
            </div>

            <form className="ai-form" onSubmit={handleSubmit}>
              <input
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
                placeholder={confirmation ? "请先确认或取消删除" : busy ? "思考中…" : "问点什么…"}
                maxLength={2000}
                disabled={busy || confirmation !== null}
                aria-label="发送给 AI 助手的消息"
              />
              <button type="submit" disabled={busy || draft.trim() === "" || confirmation !== null}>
                发送
              </button>
            </form>
          </div>
        </section>
      )}

      <button
        type="button"
        className="ai-fab"
        onClick={() => setOpen((current) => !current)}
        aria-expanded={open}
        aria-label={open ? "收起 AI 助手" : "打开 AI 助手"}
      >
        {open ? "×" : <BubbleIcon />}
      </button>
    </div>
  );
}

function BubbleIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path
        fill="currentColor"
        d="M12 3C6.9 3 2.8 6.3 2.8 10.4c0 2.3 1.3 4.4 3.4 5.8-.1 1-.6 2-1.4 2.9-.2.2 0 .6.3.5 1.9-.4 3.3-1.2 4.2-1.9.9.2 1.8.3 2.7.3 5.1 0 9.2-3.3 9.2-7.4S17.1 3 12 3Z"
      />
    </svg>
  );
}

/** The history rail's toggle: the usual "panel" mark of three rules. */
function PanelIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
      <path
        fill="currentColor"
        d="M1.75 2.5h12.5v1.5H1.75zM1.75 7.25h12.5v1.5H1.75zM1.75 12h12.5v1.5H1.75z"
      />
    </svg>
  );
}

/** The "new chat" mark: a plus. */
function PlusIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
      <path
        fill="currentColor"
        d="M7.25 1.75h1.5v5.5h5.5v1.5h-5.5v5.5h-1.5v-5.5h-5.5v-1.5h5.5z"
      />
    </svg>
  );
}
