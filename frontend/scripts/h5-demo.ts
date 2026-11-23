import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Plugin } from "vite";
import type { ProjectionStatus, ViewItem } from "../src/readmodel/threadStore.ts";
import type { ChatItem, ToolCardViewModel } from "../src/types/chat.ts";

const demoUser = { userId: 1, name: "演示用户", phone: "13800000000", admin: false };
const cookieName = "haizhuo_h5_demo";
const agentId = "chat";

type Command = NonNullable<ProjectionStatus["commands"]>[number];
type DemoThread = {
  id: string;
  name: string;
  updatedAt: string;
  items: ViewItem[];
  commands: Map<string, Command>;
  subscribers: Set<ServerResponse>;
  approval?: ViewItem & { item: ToolCardViewModel };
  booked?: boolean;
};

function createThread(id: string, name: string): DemoThread {
  return { id, name, updatedAt: new Date().toISOString(), items: [], commands: new Map(), subscribers: new Set() };
}

function projection(thread: DemoThread): ProjectionStatus {
  return {
    syncState: "ready", activeRun: null, execSeq: thread.items.length, inboxSeq: thread.commands.size,
    revision: thread.items.length, pendingCount: Number(!!thread.approval), commands: [...thread.commands.values()],
  };
}

function emit(thread: DemoThread, event: string, data: unknown) {
  for (const subscriber of thread.subscribers) subscriber.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
}

function addItem(thread: DemoThread, item: ChatItem) {
  const itemId = item.kind === "message" ? item.messageId : item.toolCallId;
  const entry = { itemId, item, orderKey: thread.items.length + 1, contentVersion: 1, details: {} };
  thread.items.push(entry);
  emit(thread, "item", { cursor: String(entry.orderKey), item: entry });
  return entry;
}

function message(thread: DemoThread, turnId: string, role: "user" | "assistant", text: string) {
  addItem(thread, { kind: "message", turnId, runId: turnId, messageId: randomUUID(), role, text });
}

function reply(thread: DemoThread, turnId: string, text: string, hasBooking: boolean) {
  if (/查看|查询|我的会议/.test(text) && /会议/.test(text)) {
    message(thread, turnId, "assistant", hasBooking
      ? "你的演示日程里有一场会议：周三 14:00–15:00，海卓会议室，项目进度同步。"
      : "你的演示日程里暂时没有会议。可以试试「安排一场会议」。");
  } else if (/安排|预约|预订/.test(text) && /会议/.test(text)) {
    message(thread, turnId, "assistant", "为你找到一个可用时段：周三 14:00–15:00，海卓会议室。请确认是否使用这组演示信息预约。");
    const item: ToolCardViewModel = {
      kind: "tool-card", toolCallId: randomUUID(), turnId, runId: turnId,
      name: "reserve_meeting", displayTitle: "周三 14:00–15:00 · 海卓会议室", status: "suspended",
      hitl: { requestId: randomUUID(), askPrompt: "确认预约演示会议？", decision: null, canRespond: true, interactionStatus: "pending" },
    };
    thread.approval = { ...addItem(thread, item), item };
  } else {
    message(thread, turnId, "assistant", "我可以帮你安排会议、查询已有日程。试试「安排一场会议」或「查看我的会议」。");
  }
}

function seedHistory() {
  const threads = new Map<string, DemoThread>();
  const records = [
    ["demo-room", "查询上周会议", "查看我上周的会议", "上周三 14:00 在海卓会议室开过一次项目进度同步会。"],
    ["demo-plan", "准备周会提纲", "帮我准备一份项目周会提纲", "可以按三项准备：本周进展、待协调事项和下周计划。"],
  ];
  for (const [id, title, prompt, answer] of records) {
    const thread = createThread(id, title);
    message(thread, id, "user", prompt);
    message(thread, id, "assistant", answer);
    threads.set(id, thread);
  }
  return threads;
}

function json(response: ServerResponse, status: number, body?: unknown) {
  response.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
  response.end(body === undefined ? undefined : JSON.stringify(body));
}

async function readObject(request: IncomingMessage): Promise<Record<string, unknown>> {
  let body = "";
  for await (const chunk of request) {
    body += chunk;
    if (body.length > 64_000) throw new Error("Demo request too large");
  }
  return JSON.parse(body);
}

export function h5Demo(): Plugin {
  return {
    name: "haizhuo-h5-demo",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use(createDemoMiddleware());
    },
  };
}

export function createDemoMiddleware() {
  const tickets = new Map<string, { expiresAt: number; phone: string }>();
  const sessions = new Map<string, typeof demoUser>();
  const threads = seedHistory();

  return (request: IncomingMessage, response: ServerResponse, next: () => void) => {
    const url = new URL(request.url ?? "/", "http://localhost");
    if (url.pathname !== "/__h5-demo" && !/^\/(api|agui)\//.test(url.pathname)) return next();
    void handle(request, response, url).catch(() => {
      if (!response.headersSent) json(response, 400, { error: "演示请求无效" });
      else response.end();
    });
  };

  async function handle(request: IncomingMessage, response: ServerResponse, url: URL) {
    const { pathname: path } = url;
    const method = request.method;
    const cookie = request.headers.cookie?.split(";").map(value => value.trim())
      .find(value => value.startsWith(cookieName + "="))?.slice(cookieName.length + 1);

    if (path === "/__h5-demo" && method === "GET") {
      for (const [key, entry] of tickets) if (entry.expiresAt <= Date.now()) tickets.delete(key);
      const ticket = randomUUID();
      tickets.set(ticket, { expiresAt: Date.now() + 60_000, phone: demoUser.phone });
      response.writeHead(302, { Location: `/?view=h5#ticket=${ticket}`, "Cache-Control": "no-store" });
      response.end();
      return;
    }
    if (path === "/api/auth/mantianxing/exchange" && method === "POST") {
      const { ticket } = await readObject(request);
      if (typeof ticket !== "string") return json(response, 400);
      const entry = tickets.get(ticket);
      if (!entry || entry.expiresAt <= Date.now()) return json(response, 401);
      tickets.delete(ticket);
      if (entry.phone !== demoUser.phone) return json(response, 403);
      if (cookie) sessions.delete(cookie);
      const session = randomUUID();
      sessions.set(session, demoUser);
      response.setHeader("Set-Cookie", `${cookieName}=${session}; Path=/; HttpOnly; SameSite=Lax`);
      return json(response, 204);
    }
    const user = cookie && sessions.get(cookie);
    if (!cookie || !user) return json(response, 401);
    if (path === "/api/auth/me") return json(response, 200, user);
    if (path === "/api/auth/logout" && method === "POST") {
      sessions.delete(cookie);
      response.setHeader("Set-Cookie", `${cookieName}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0`);
      return json(response, 204);
    }
    if (path === "/agui/run/info") {
      return json(response, 200, {
        version: "2.0.0", agents: { chat: { id: agentId, name: "海卓大脑", description: "H5 演示" } },
        mode: "sse", threadEndpoints: { list: true }, telemetryDisabled: true,
      });
    }
    if (path === "/agui/run/threads" && method === "GET") {
      const limit = Number(url.searchParams.get("limit") ?? 20);
      const offset = Number(url.searchParams.get("cursor") ?? 0);
      const saved = [...threads.values()].filter(thread => thread.items.length)
        .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
      return json(response, 200, {
        threads: saved.slice(offset, offset + limit).map(({ id, name, updatedAt }) => ({ id, name, updatedAt, agentId })),
        nextCursor: offset + limit < saved.length ? String(offset + limit) : null,
      });
    }
    const endpoint = /^\/api\/v2\/threads\/([^/]+)\/(view|events|interactions)$/.exec(path);
    if (endpoint && method === "GET") {
      const threadId = decodeURIComponent(endpoint[1]);
      const thread = threads.get(threadId) ?? createThread(threadId, "");
      threads.set(threadId, thread);
      if (endpoint[2] === "interactions") return json(response, 200, { items: thread.approval ? [thread.approval] : [], nextCursor: null });
      if (endpoint[2] === "view") {
        return json(response, 200, { page: {
          items: thread.items, eventCursor: String(thread.items.length), hasMore: false, nextCursor: null,
          snapshotToken: "demo", projectionUpdatedAt: Date.now(),
        }, status: projection(thread) });
      }
      response.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" });
      response.write(": connected\n\n");
      for (const item of thread.items.slice(Number(url.searchParams.get("after") ?? 0))) {
        response.write(`event: item\ndata: ${JSON.stringify({ cursor: String(item.orderKey), item })}\n\n`);
      }
      response.write(`event: status\ndata: ${JSON.stringify(projection(thread))}\n\n`);
      thread.subscribers.add(response);
      response.on("close", () => thread.subscribers.delete(response));
      return;
    }
    const turn = /^\/api\/sessions\/([^/]+)\/turns$/.exec(path);
    if (turn && method === "POST") {
      const { request_id: commandId, message: text } = await readObject(request);
      if (typeof commandId !== "string" || typeof text !== "string" || !text.trim()) return json(response, 400);
      const threadId = decodeURIComponent(turn[1]);
      const thread = threads.get(threadId) ?? createThread(threadId, "");
      threads.set(threadId, thread);
      const prior = thread.commands.get(commandId);
      if (prior) return json(response, 202, prior);
      if (thread.approval || [...thread.commands.values()].some(command => command.status === "queued")) return json(response, 409);
      const turnId = randomUUID();
      const command = { commandId, turnId, runId: turnId, status: "queued" };
      thread.commands.set(commandId, command);
      thread.name ||= text.trim().slice(0, 36);
      thread.updatedAt = new Date().toISOString();
      message(thread, turnId, "user", text);
      json(response, 202, command);
      setTimeout(() => {
        reply(thread, turnId, text, [...threads.values()].some(value => value.booked));
        command.status = "completed";
        emit(thread, "status", projection(thread));
      }, 500);
      return;
    }
    const decision = /^\/api\/threads\/([^/]+)\/(interactions|turns)\/([^/]+)\/(answer|cancel)$/.exec(path);
    if (decision && method === "POST") {
      const thread = threads.get(decodeURIComponent(decision[1]));
      const pending = thread?.approval;
      if (!thread || !pending) return json(response, 409);
      const cancel = decision[4] === "cancel";
      const expectedId = cancel ? pending.item.turnId : pending.item.hitl?.requestId;
      if (decision[3] !== expectedId) return json(response, 409);
      const answer = cancel ? "cancel" : (await readObject(request)).decision;
      if (thread.approval !== pending) return json(response, 409);
      if (answer !== "approve" && answer !== "reject" && answer !== "cancel") return json(response, 400);
      thread.approval = undefined;
      if (answer === "approve") thread.booked = true;
      const resolved: ViewItem = {
        ...pending, contentVersion: pending.contentVersion + 1,
        item: { ...pending.item, status: answer === "approve" ? "completed" : "interrupted",
          hitl: undefined },
      };
      thread.items[pending.orderKey - 1] = resolved;
      emit(thread, "item", { cursor: String(thread.items.length), item: resolved });
      message(thread, pending.item.turnId, "assistant", answer === "approve"
        ? "演示预约成功：周三 14:00–15:00，海卓会议室，主题「项目进度同步」。"
        : "已取消本次演示预约。");
      emit(thread, "status", projection(thread));
      return json(response, 200, { accepted: true });
    }
    json(response, 404, { error: "此接口不在 H5 演示范围内" });
  }
}
