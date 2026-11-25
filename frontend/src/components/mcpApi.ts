import { apiFetch } from "../auth/api";
export type McpService = {
  id: string; providerKey: string; displayName: string; description: string | null; ownerName: string | null;
  enabled: boolean; controlVersion: number; configured: boolean; endpoint: string | null; transport: string | null;
  authType: string | null; environment: string | null; health: string; lastCheckedAt: string | null;
  lastSuccessAt: string | null; lastLatencyMs: number | null; lastErrorMessage: string | null;
  historicalSnapshot: boolean; discoveredCount: number; openCount: number; loadedCount: number; updatedAt: string | null;
  instance: { id: string; startedAt: string };
};
export type McpTool = {
  id: string; toolKey: string; description: string | null; write: boolean; enabled: boolean; serviceEnabled: boolean;
  present: boolean; controlVersion: number; loadedInCurrentInstance: boolean; definitionChanged: boolean;
  inputSchema?: Record<string, unknown>;
};
export type McpOperation = { id: string; action: string; actorName: string | null; result: string; detail: unknown; createdAt: string };
export type McpPage<T> = { items: T[]; total: number; page: number; size: number };
export type McpList = McpPage<McpService> & { counts: { all: number; enabled: number; disabled: number } };
export async function mcpRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await apiFetch("/api/admin/mcp/services" + path, init);
  const body = await response.json();
  if (!response.ok) throw new Error(body.message || body.error || "MCP 请求失败");
  return body as T;
}
export function mcpWrite<T>(path: string, body?: unknown, method = "POST") {
  return mcpRequest<T>(path, { method, headers: { "Content-Type": "application/json" },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
}
export function toolLoadLabel(t: Pick<McpTool, "present" | "loadedInCurrentInstance" | "definitionChanged">): string {
  if (!t.present) return "已从远端移除";
  if (!t.loadedInCurrentInstance) return "待重启加载";
  return t.definitionChanged ? "待重启更新" : "已加载";
}
export function healthLabel(value: string): string {
  return ({ HEALTHY: "连接正常", FAILED: "连接异常", UNKNOWN: "尚未验证", CHECKING: "正在验证", INTERRUPTED: "检测中断" } as Record<string, string>)[value] || "尚未验证";
}
export function mcpTime(value: string | null): string {
  return value ? new Date(value).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai", hour12: false }) : "—";
}
export function operationLines(op: McpOperation): string[] {
  const d = op.detail && typeof op.detail === "object" ? op.detail as Record<string, unknown> : {};
  if (op.action === "SERVICE_STATUS_CHANGED" || op.action === "TOOL_STATUS_CHANGED")
    return [d.toolKey ? "工具：" + String(d.toolKey) : "服务总开关",
      (d.before ? "开放" : "关闭") + " → " + (d.after ? "开放" : "关闭")];
  if (op.action === "METADATA_UPDATED") {
    const before = (d.before || {}) as Record<string, unknown>, after = (d.after || {}) as Record<string, unknown>;
    return Object.entries({ displayName: "名称", description: "说明", ownerName: "负责人" })
      .filter(([key]) => before[key] !== after[key])
      .map(([key, label]) => label + "：" + (before[key] || "未填写") + " → " + (after[key] || "未填写"));
  }
  if (op.result === "SUPERSEDED") return ["已有更新的检测结果，本次结果未覆盖目录。"];
  if (op.result === "FAILED") return ["连接验证失败，上次成功目录和管理开关保持不变。"];
  return ["新增 " + Number(d.added || 0) + " 个工具，移除 " + Number(d.removed || 0) + " 个工具，定义变化 " + Number(d.changed || 0) + " 个工具。"];
}
