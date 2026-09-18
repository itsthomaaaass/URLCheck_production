import { useEffect, useState, type FormEvent } from "react";
import AiChat from "./AiChat";
import {
  checkUrl,
  createUrl,
  deleteUrl,
  fetchCurrentUser,
  fetchTimeline,
  fetchUrls,
  loginUser,
  logoutUser,
  registerUser,
  updateUrl,
  type CheckResult,
  type MonitoredUrl,
  type TimelineEvent,
  type TimelineView,
  type User
} from "./api";

type AuthMode = "login" | "register";

const STATUS_LABELS: Record<CheckResult["status"], string> = {
  UP: "可访问",
  DOWN: "无法访问"
};

function statusLabel(result: CheckResult | undefined, pending: boolean): string {
  if (pending) {
    return "检查中…";
  }
  return result ? STATUS_LABELS[result.status] : "未检查";
}

function statusClass(result: CheckResult | undefined): string {
  return result ? result.status.toLowerCase() : "unknown";
}

/** e.g. "HTTP 200 · 183 ms · 12:34:56" — why a verdict was reached. */
function describeCheck(result: CheckResult | undefined): string {
  if (!result) {
    return "";
  }
  const parts: string[] = [];
  if (result.errorType) {
    parts.push(result.errorType);
  }
  if (result.httpStatus !== null) {
    parts.push(`HTTP ${result.httpStatus}`);
  }
  parts.push(`${result.responseTimeMs} ms`);
  parts.push(new Date(result.checkedAt).toLocaleTimeString());
  return parts.join(" · ");
}

const CHANGE_LABELS: Record<TimelineEvent["changeType"], string> = {
  FIRST_CHECK: "首次检查",
  CONTENT_CHANGED: "内容变化",
  RECOVERED: "已恢复",
  UNAVAILABLE: "无法访问"
};

/** e.g. "HTTP 200 · 183 ms · 2026/9/12 12:34:56" for one timeline node. */
function describeEvent(event: TimelineEvent): string {
  const parts: string[] = [];
  if (event.errorType) {
    parts.push(event.errorType);
  }
  if (event.httpStatus !== null) {
    parts.push(`HTTP ${event.httpStatus}`);
  }
  if (event.responseTimeMs !== null) {
    parts.push(`${event.responseTimeMs} ms`);
  }
  parts.push(new Date(event.detectedAt).toLocaleString());
  return parts.join(" · ");
}

/** One node of the vertical timeline: verdict, change, and why. */
function TimelineNode({ event }: { event: TimelineEvent }) {
  const status = event.status.toLowerCase();
  return (
    <div className="timeline-node">
      <span className={`timeline-dot dot-${status}`} />
      <div className="timeline-body">
        <span className="timeline-head">
          <span className={`status-badge status-${status}`}>
            {STATUS_LABELS[event.status]}
          </span>
          <span className={`change-tag change-${event.changeType.toLowerCase()}`}>
            {CHANGE_LABELS[event.changeType]}
          </span>
        </span>
        <span className="check-detail">{describeEvent(event)}</span>
      </div>
    </div>
  );
}

/**
 * Collapsible body of one URL timeline. Events arrive newest first; the panel
 * renders them oldest first so the fixed first-check node sits at the top.
 * When history has been pruned, that node is pinned and the missing events are
 * called out between it and the retained tail.
 */
function TimelinePanel({
  view,
  loading,
  error
}: {
  view: TimelineView | undefined;
  loading: boolean;
  error: string | undefined;
}) {
  if (loading && !view) {
    return <p className="timeline-note">加载中…</p>;
  }
  if (error) {
    return <p className="timeline-note timeline-error">{error}</p>;
  }
  if (!view || view.events.length === 0) {
    return <p className="timeline-note">暂无记录。</p>;
  }
  const chronological = [...view.events].reverse();
  const anchor = chronological.find((event) => event.changeNo === 1);
  const rest = chronological.filter((event) => event !== anchor);
  const skipped = view.totalCount - chronological.length;
  return (
    <div className="timeline">
      {anchor && <TimelineNode event={anchor} />}
      {anchor && skipped > 0 && (
        <p className="timeline-skip">已跳过 {skipped} 条历史记录</p>
      )}
      {rest.map((event) => (
        <TimelineNode key={event.id} event={event} />
      ))}
    </div>
  );
}

export default function App() {
  const [urls, setUrls] = useState<MonitoredUrl[]>([]);
  const [user, setUser] = useState<User | null>(null);
  const [authMode, setAuthMode] = useState<AuthMode>("login");
  const [authUsername, setAuthUsername] = useState("");
  const [authPassword, setAuthPassword] = useState("");
  const [authBusy, setAuthBusy] = useState(false);
  const [checkingAuth, setCheckingAuth] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [description, setDescription] = useState("");
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editName, setEditName] = useState("");
  const [editUrl, setEditUrl] = useState("");
  const [editDescription, setEditDescription] = useState("");
  const [editBusy, setEditBusy] = useState(false);
  // Check results are never persisted, so they live only in this page and are
  // gone after a reload. Both are keyed by url id.
  const [checks, setChecks] = useState<Record<number, CheckResult>>({});
  const [pendingChecks, setPendingChecks] = useState<number[]>([]);
  // Timeline data is fetched lazily and keyed by url id; it is separate from
  // the manual check, which never writes to the stored history.
  const [timelines, setTimelines] = useState<Record<number, TimelineView>>({});
  const [timelineLoading, setTimelineLoading] = useState<number[]>([]);
  const [timelineErrors, setTimelineErrors] = useState<Record<number, string>>({});
  const [expandedTimelines, setExpandedTimelines] = useState<number[]>([]);

  async function loadUrls() {
    setUrls(await fetchUrls());
  }

  useEffect(() => {
    (async () => {
      try {
        const current = await fetchCurrentUser();
        if (current) {
          setUser(current);
          await loadUrls();
        }
      } catch {
        // stay on the login screen if the session check fails
      } finally {
        setCheckingAuth(false);
      }
    })();
  }, []);

  async function handleAuthSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setAuthBusy(true);
    try {
      const username = authUsername.trim();
      const password = authPassword;
      let nextUser: User;
      if (authMode === "register") {
        await registerUser(username, password);
        nextUser = await loginUser(username, password);
      } else {
        nextUser = await loginUser(username, password);
      }
      setUser(nextUser);
      setAuthUsername("");
      setAuthPassword("");
      await loadUrls();
    } catch (e) {
      setError(e instanceof Error ? e.message : "操作失败");
    } finally {
      setAuthBusy(false);
    }
  }

  async function handleLogout() {
    setError(null);
    try {
      await logoutUser();
    } catch {
      // clear local state even if the session already expired
    }
    setUser(null);
    setUrls([]);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const created = await createUrl(name.trim(), url.trim(), description.trim());
      setUrls((prev) => [created, ...prev]);
      setName("");
      setUrl("");
      setDescription("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "添加失败");
    } finally {
      setSubmitting(false);
    }
  }

  function startEdit(item: MonitoredUrl) {
    setError(null);
    setEditName(item.name);
    setEditUrl(item.url);
    setEditDescription(item.description);
    setEditingId(item.id);
  }

  function cancelEdit() {
    setEditingId(null);
    setError(null);
  }

  async function handleEditSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (editingId === null) {
      return;
    }
    setError(null);
    setEditBusy(true);
    try {
      const updated = await updateUrl(
        editingId,
        editName.trim(),
        editUrl.trim(),
        editDescription.trim()
      );
      setUrls((prev) => prev.map((u) => (u.id === updated.id ? updated : u)));
      // The stored URL may have changed, so an earlier verdict is stale.
      forgetCheck(updated.id);
      setEditingId(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存失败");
    } finally {
      setEditBusy(false);
    }
  }

  async function handleDelete(item: MonitoredUrl) {
    if (!window.confirm(`确定删除“${item.name}”吗？`)) {
      return;
    }
    setError(null);
    try {
      await deleteUrl(item.id);
      setUrls((prev) => prev.filter((u) => u.id !== item.id));
      forgetCheck(item.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "删除失败");
    }
  }

  async function handleCheck(item: MonitoredUrl) {
    setError(null);
    setPendingChecks((prev) => [...prev, item.id]);
    try {
      const result = await checkUrl(item.id);
      setChecks((prev) => ({ ...prev, [item.id]: result }));
    } catch (e) {
      setError(e instanceof Error ? e.message : "检查失败");
    } finally {
      setPendingChecks((prev) => prev.filter((id) => id !== item.id));
    }
  }

  function forgetCheck(id: number) {
    setChecks((prev) => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
    setTimelines((prev) => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
    setTimelineErrors((prev) => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
    setExpandedTimelines((prev) => prev.filter((openId) => openId !== id));
  }

  async function loadTimeline(id: number) {
    setTimelineErrors((prev) => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
    setTimelineLoading((prev) => [...prev, id]);
    try {
      const view = await fetchTimeline(id);
      setTimelines((prev) => ({ ...prev, [id]: view }));
    } catch (e) {
      setTimelineErrors((prev) => ({
        ...prev,
        [id]: e instanceof Error ? e.message : "加载失败"
      }));
    } finally {
      setTimelineLoading((prev) => prev.filter((loadingId) => loadingId !== id));
    }
  }

  function handleTimelineToggle(id: number) {
    if (expandedTimelines.includes(id)) {
      setExpandedTimelines((prev) => prev.filter((openId) => openId !== id));
      return;
    }
    setExpandedTimelines((prev) => [...prev, id]);
    void loadTimeline(id);
  }

  if (checkingAuth) {
    return (
      <main className="container">
        <p className="empty">加载中…</p>
      </main>
    );
  }

  if (!user) {
    return (
      <main className="container auth-box">
        <h1>URL 监控</h1>
        <p className="subtitle">
          {authMode === "login" ? "登录后管理你的 URL" : "创建新账号"}
        </p>
        <form className="auth-form" onSubmit={handleAuthSubmit}>
          <input
            value={authUsername}
            onChange={(e) => setAuthUsername(e.target.value)}
            placeholder="用户名"
            maxLength={50}
            required
          />
          <input
            value={authPassword}
            onChange={(e) => setAuthPassword(e.target.value)}
            placeholder="密码（英文字母、数字、下划线）"
            type="password"
            required
          />
          <div className="auth-actions">
            <button type="submit" className="primary-btn" disabled={authBusy}>
              {authMode === "login"
                ? authBusy
                  ? "登录中…"
                  : "登录"
                : authBusy
                  ? "注册中…"
                  : "注册"}
            </button>
            <button
              type="button"
              className="ghost-btn"
              onClick={() => setAuthMode(authMode === "login" ? "register" : "login")}
            >
              {authMode === "login" ? "没有账号？注册" : "已有账号？登录"}
            </button>
          </div>
        </form>
        {error && <p className="error">{error}</p>}
      </main>
    );
  }

  return (
    <main className="container">
      <header className="topbar">
        <span className="user-name">{user.username}</span>
        <button className="ghost-btn" onClick={handleLogout}>
          退出登录
        </button>
      </header>
      <h1>URL 监控</h1>
      <p className="subtitle">添加并查看被监控的 URL</p>

      <form className="add-form" onSubmit={handleSubmit}>
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="名称（如：GitHub 首页）"
          maxLength={100}
          required
        />
        <input
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          placeholder="https://example.com"
          type="url"
          required
        />
        <button type="submit" disabled={submitting}>
          {submitting ? "添加中…" : "添加"}
        </button>
        <input
          className="add-description"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="描述（可选）"
          maxLength={1000}
        />
      </form>

      {error && <p className="error">{error}</p>}

      {urls.length === 0 ? (
        <p className="empty">还没有 URL，添加一个试试。</p>
      ) : (
        <ul className="url-list">
          {urls.map((item) => (
            <li key={item.id}>
              {editingId === item.id ? (
                <form className="edit-form" onSubmit={handleEditSubmit}>
                  <input
                    value={editName}
                    onChange={(e) => setEditName(e.target.value)}
                    placeholder="名称（如：GitHub 首页）"
                    maxLength={100}
                    required
                  />
                  <input
                    value={editUrl}
                    onChange={(e) => setEditUrl(e.target.value)}
                    placeholder="https://example.com"
                    type="url"
                    required
                  />
                  <input
                    value={editDescription}
                    onChange={(e) => setEditDescription(e.target.value)}
                    placeholder="描述（可选）"
                    maxLength={1000}
                  />
                  <div className="edit-actions">
                    <button type="submit" className="primary-btn" disabled={editBusy}>
                      {editBusy ? "保存中…" : "保存"}
                    </button>
                    <button type="button" className="ghost-btn" onClick={cancelEdit}>
                      取消
                    </button>
                  </div>
                </form>
              ) : (
                <>
                  <div className="url-row">
                    <div className="url-info">
                      <span className="name">{item.name}</span>
                      <span className="check-line">
                        <span
                          className={`status-badge status-${statusClass(checks[item.id])}`}
                        >
                          {statusLabel(checks[item.id], pendingChecks.includes(item.id))}
                        </span>
                        {checks[item.id] && (
                          <span className="check-detail">
                            {describeCheck(checks[item.id])}
                          </span>
                        )}
                      </span>
                      {item.description !== "" && (
                        <span className="description">{item.description}</span>
                      )}
                      <a href={item.url} target="_blank" rel="noreferrer">
                        {item.url}
                      </a>
                    </div>
                    <div className="url-actions">
                      <span className="time">
                        添加于 {new Date(item.createdAt).toLocaleString()}
                      </span>
                      <div className="row-actions">
                        <button
                          className="check-btn"
                          onClick={() => handleCheck(item)}
                          disabled={pendingChecks.includes(item.id)}
                        >
                          {pendingChecks.includes(item.id) ? "检查中…" : "检查"}
                        </button>
                        <button className="edit-btn" onClick={() => startEdit(item)}>
                          编辑
                        </button>
                        <button className="delete-btn" onClick={() => handleDelete(item)}>
                          删除
                        </button>
                      </div>
                    </div>
                  </div>
                  <div className="timeline-section">
                    <button
                      type="button"
                      className="timeline-toggle"
                      onClick={() => handleTimelineToggle(item.id)}
                      aria-expanded={expandedTimelines.includes(item.id)}
                    >
                      <span className={`timeline-caret${expandedTimelines.includes(item.id) ? " open" : ""}`}>▸</span>
                      时间线
                      {item.changeCount > 0 && (
                        <span className="timeline-count">共 {item.changeCount} 条</span>
                      )}
                    </button>
                    {expandedTimelines.includes(item.id) && (
                      <TimelinePanel
                        view={timelines[item.id]}
                        loading={timelineLoading.includes(item.id)}
                        error={timelineErrors[item.id]}
                      />
                    )}
                  </div>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      <AiChat onUrlsChanged={loadUrls} />
    </main>
  );
}
