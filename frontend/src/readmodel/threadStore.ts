import { useMemo, useSyncExternalStore } from "react";
import type { ChatItem } from "@/types/chat";

export interface ViewItem {
  itemId: string;
  orderKey: number;
  contentVersion: number;
  item: ChatItem;
  details: Record<string, { bytes: number; encoding: string }>;
  localPhase?: Submission["phase"];
}
export interface ProjectionStatus {
  syncState: string; activeRun: string | null; execSeq: number; inboxSeq: number;
  revision: number; pendingCount: number;
  busy?: boolean;
  commands?: { commandId: string; turnId: string; runId: string | null; status: string; reason?: string }[];
}
export interface Submission {
  requestId: string; text: string; phase: "sending" | "accepted" | "uncertain" | "completed" | "rejected";
  commandId?: string; turnId?: string; error?: string;
}
interface Page {
  items: ViewItem[]; snapshotToken: string; nextCursor: string | null;
  hasMore: boolean; eventCursor: string; projectionUpdatedAt: number;
}
interface Response { page: Page; status: ProjectionStatus }
export interface ThreadState {
  items: ViewItem[]; status?: ProjectionStatus; loading: boolean; error?: string;
  hasMore: boolean; fetching: boolean; trimmed: boolean;
  submission?: Submission;
}

export function submissionBlocked(state: ThreadState) {
  return state.loading || !state.status || state.status.syncState !== "ready" || !!state.status.busy
    || !!state.status.activeRun || (state.status.pendingCount > 0)
    || !!(state.submission && !["completed", "rejected"].includes(state.submission.phase));
}

/** 待发送气泡与权威历史分开；收到同一 turn 的用户消息后移除本地副本。 */
export function displayItems(state: ThreadState): ViewItem[] {
  const submission = state.submission;
  if (!submission || state.items.some(entry => entry.item.kind === "message" && entry.item.role === "user"
      && !!submission.turnId && entry.item.turnId === submission.turnId)) return state.items;
  let preview = submission.text;
  if (preview.length > 1024) {
    let end = 1024;
    const last = preview.charCodeAt(end - 1);
    if (last >= 0xd800 && last <= 0xdbff) end--;
    preview = preview.slice(0, end) + "…（较长内容先显示预览，消息同步后可展开全文）";
  }
  return [...state.items, {
    itemId: "local:" + submission.requestId, orderKey: Number.MAX_SAFE_INTEGER, contentVersion: 0, details: {},
    localPhase: submission.phase,
    item: { kind: "message", messageId: "local:" + submission.requestId, turnId: submission.turnId ?? "",
      runId: "", role: "user", text: preview, partial: false },
  }];
}

/** 历史页与实时更新共用稳定键；较旧页面不能覆盖已经收到的新版本。 */
export function mergeItems(current: ViewItem[], incoming: ViewItem[]): ViewItem[] {
  const map = new Map(current.map(item => [item.itemId, item]));
  for (const item of incoming) {
    if ((map.get(item.itemId)?.contentVersion ?? -1) <= item.contentVersion) map.set(item.itemId, item);
  }
  return [...map.values()].sort((a, b) => a.orderKey - b.orderKey);
}

class ThreadStore {
  state: ThreadState = { items: [], loading: true, hasMore: false, fetching: false, trimmed: false };
  private listeners = new Set<() => void>();
  private source?: EventSource;
  private abort?: AbortController;
  private next?: string | null;
  private pending: ViewItem[] = [];
  private timer?: ReturnType<typeof setTimeout>;
  private stopTimer?: ReturnType<typeof setTimeout>;
  private epoch = 0;
  private cursor?: string;
  private readingHistory = false;
  get active() { return this.listeners.size > 0; }
  dispose() { this.stop(); }
  readonly thread: string;
  constructor(thread: string) {
    this.thread = thread;
    if (typeof sessionStorage !== "undefined") {
      try {
        const saved = sessionStorage.getItem("session-chat.submission." + thread);
        if (saved) {
          const submission = JSON.parse(saved) as Submission;
          if (typeof submission.requestId === "string" && typeof submission.text === "string")
            this.state.submission = { ...submission, phase: submission.phase === "sending" ? "uncertain" : submission.phase };
        }
      } catch { /* 本地暂存不可读时仍可通过服务端历史恢复，不把它当作执行事实。 */ }
    }
  }
  snapshot = () => this.state;
  private publish(patch: Partial<ThreadState>) {
    this.state = { ...this.state, ...patch };
    const submission = this.state.submission;
    if (submission) {
      // 框架使用 requestId 作为持久 commandId；刷新中断回执时，也能从服务端事实找回接收结果。
      const command = this.state.status?.commands?.find(command => command.commandId === (submission.commandId ?? submission.requestId));
      if (command && ["queued", "running", "finishing"].includes(command.status)) {
        this.state = { ...this.state, submission: { ...submission, commandId: command.commandId,
          turnId: command.turnId, phase: "accepted", error: undefined } };
      } else if (command) {
        const confirmed = this.state.items.some(entry => entry.item.kind === "message" && entry.item.role === "user"
          && entry.item.turnId === command.turnId);
        this.state = { ...this.state, submission: confirmed ? undefined : { ...submission,
          commandId: command.commandId, turnId: command.turnId,
          phase: command.status === "rejected" ? "rejected" : "completed", error: command.reason } };
      }
    }
    if (typeof sessionStorage !== "undefined") {
      try {
        const key = "session-chat.submission." + this.thread;
        if (this.state.submission) sessionStorage.setItem(key, JSON.stringify(this.state.submission));
        else sessionStorage.removeItem(key);
      } catch { /* 存储配额不足不影响当前页面的提交锁与消息回显。 */ }
    }
    this.listeners.forEach(listener => listener());
  }

  /** 同步取得提交锁，拦截同一帧内的连按；锁直到任务终态，而非 HTTP 回执。 */
  beginSubmission(text: string): Submission | undefined {
    if (submissionBlocked(this.state)) return;
    this.readingHistory = false;
    const submission: Submission = { requestId: crypto.randomUUID(), text, phase: "sending" };
    this.publish({ submission }); return submission;
  }
  retrySubmission(): Submission | undefined {
    const submission = this.state.submission;
    if (!submission || submission.phase !== "uncertain") return;
    const retry: Submission = { ...submission, phase: "sending", error: undefined };
    this.publish({ submission: retry }); return retry;
  }
  accepted(requestId: string, commandId: string, turnId: string) {
    if (this.state.submission?.requestId !== requestId) return;
    this.publish({ submission: { ...this.state.submission, commandId, turnId, phase: "accepted" } });
  }
  uncertain(requestId: string, error: string) {
    if (this.state.submission?.requestId !== requestId) return;
    this.publish({ submission: { ...this.state.submission, phase: "uncertain", error } });
  }
  rejected(requestId: string, error: string) {
    if (this.state.submission?.requestId !== requestId) return;
    this.publish({ submission: { ...this.state.submission, phase: "rejected", error } });
  }
  subscribe = (listener: () => void) => {
    clearTimeout(this.stopTimer); this.listeners.add(listener);
    if (!this.abort) void this.start();
    return () => {
      this.listeners.delete(listener);
      if (!this.listeners.size) this.stopTimer = setTimeout(() => this.stop(), 100);
    };
  };
  private stop() {
    this.epoch++; this.abort?.abort(); this.abort = undefined; this.source?.close(); this.source = undefined;
    clearTimeout(this.timer); this.timer = undefined; this.pending = [];
  }
  private flush = () => {
    clearTimeout(this.timer); this.timer = undefined;
    if (!this.pending.length) return;
    let items = mergeItems(this.state.items, this.pending); this.pending = [];
    // 实时阅读尾部时淘汰最旧页；历史阅读时不自动驱逐，批量到达后由窗口上限兜底。
    const trimmed = items.length > 300;
    if (trimmed) items = this.readingHistory ? items.slice(0, 300) : items.slice(-300);
    this.publish({ items, trimmed: this.state.trimmed || trimmed });
  };
  private async start() {
    const epoch = ++this.epoch;
    const abort = new AbortController(); this.abort = abort;
    this.publish({ loading: true, error: undefined });
    try {
      const response = await fetch(`/api/v2/threads/${encodeURIComponent(this.thread)}/view`, { signal: abort.signal });
      if (!response.ok) throw new Error("暂时无法读取会话，请重新加载");
      const data = await response.json() as Response;
      if (epoch !== this.epoch) return;
      this.readingHistory = false;
      this.next = data.page.nextCursor; this.cursor = data.page.eventCursor;
      this.publish({ items: data.page.items, status: data.status, loading: false, hasMore: data.page.hasMore, trimmed: false });
      const source = new EventSource(`/api/v2/threads/${encodeURIComponent(this.thread)}/events?after=${encodeURIComponent(this.cursor)}`);
      this.source = source;
      source.addEventListener("item", event => {
        if (epoch !== this.epoch) return;
        const value = JSON.parse((event as MessageEvent).data) as { cursor: string; item: ViewItem };
        this.cursor = value.cursor;
        this.pending.push(value.item);
        if (!this.timer) this.timer = setTimeout(this.flush, document.hidden ? 500 : 50);
      });
      source.addEventListener("status", event => {
        if (epoch !== this.epoch) return;
        this.flush();
        const status = JSON.parse((event as MessageEvent).data) as ProjectionStatus;
        const ended = this.state.status?.activeRun && !status.activeRun;
        this.publish({ status, error: undefined });
        if (ended) window.dispatchEvent(new Event("thread-catalog-changed"));
      });
      source.addEventListener("reset-required", () => { if (epoch === this.epoch) void this.reload(); });
      source.onerror = () => {
        if (epoch === this.epoch) this.publish({ error: "连接暂时中断，正在按水位续接…" });
      };
      source.onopen = () => { if (epoch === this.epoch) this.publish({ error: undefined }); };
    } catch (error) {
      if (epoch === this.epoch && !abort.signal.aborted)
        this.publish({ loading: false, error: error instanceof Error ? error.message : "读取失败" });
    }
  }
  reload = async () => { this.stop(); await this.start(); };
  /** 审批后的状态核对只归并最新项，不清空旧页和用户正在阅读的滚动位置。 */
  refresh = async () => {
    const epoch = this.epoch;
    try {
      const response = await fetch(`/api/v2/threads/${encodeURIComponent(this.thread)}/view`, { signal: this.abort?.signal });
      if (!response.ok) throw new Error("当前状态暂不可读，请稍后重新查询");
      const data = await response.json() as Response;
      if (epoch !== this.epoch) return;
      this.pending.push(...data.page.items); this.flush(); this.publish({ status: data.status, error: undefined });
    } catch (error) {
      if (epoch === this.epoch && !this.abort?.signal.aborted)
        this.publish({ error: error instanceof Error ? error.message : "查询失败" });
    }
  };
  loadMore = async () => {
    if (!this.next || this.state.fetching) return;
    this.readingHistory = true;
    const epoch = this.epoch; this.publish({ fetching: true });
    try {
      const response = await fetch(`/api/v2/threads/${encodeURIComponent(this.thread)}/view?before=${encodeURIComponent(this.next)}`, { signal: this.abort?.signal });
      if (response.status === 410) { await this.reload(); return; }
      if (!response.ok) throw new Error("更早历史加载失败");
      const data = await response.json() as Response;
      if (epoch !== this.epoch) return;
      this.next = data.page.nextCursor;
      let items = mergeItems(this.state.items, data.page.items);
      const trimmed = items.length > 300;
      // 读取旧历史时保留旧窗口，较新部分可以通过“回到最新”重新取得。
      if (trimmed) items = items.slice(0, 300);
      this.publish({ items, hasMore: data.page.hasMore, trimmed: this.state.trimmed || trimmed });
    } catch (error) {
      if (epoch === this.epoch && !this.abort?.signal.aborted)
        this.publish({ error: error instanceof Error ? error.message : "加载失败" });
    } finally { if (epoch === this.epoch) this.publish({ fetching: false }); }
  };
}

const stores = new Map<string, ThreadStore>();
export function getThreadStore(thread: string) {
  let store = stores.get(thread);
  if (!store) { store = new ThreadStore(thread); stores.set(thread, store); }
  stores.delete(thread); stores.set(thread, store);
  for (const [key, cached] of stores) {
    if (stores.size <= 4) break;
    if (key !== thread && !cached.active) { cached.dispose(); stores.delete(key); }
  }
  return store;
}
export function useThreadView(thread: string) {
  const store = getThreadStore(thread);
  const state = useSyncExternalStore(store.subscribe, store.snapshot);
  const items = useMemo(() => displayItems(state), [state.items, state.submission]);
  return { ...state, items, submissionBlocked: submissionBlocked(state),
    reload: store.reload, refresh: store.refresh, loadMore: store.loadMore };
}
