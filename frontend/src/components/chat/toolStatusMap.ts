import type { ToolCardStatus } from "@/types/chat";

export type TraceVisual = "pending" | "running" | "done" | "error" | "hitl" | "muted";

export type TracePresentation = {
  visual: TraceVisual;
  defaultOpen: boolean;
  label: string;
};

const LABELS: Record<ToolCardStatus, string> = {
  generating_arguments: "接收参数",
  requested: "已请求",
  running: "执行中",
  completed: "完成",
  failed: "失败",
  suspended: "待审批",
  interrupted: "已中断",
};

export function mapToolStatus(status: ToolCardStatus): TracePresentation {
  switch (status) {
    case "generating_arguments":
    case "requested":
      return { visual: "pending", defaultOpen: true, label: LABELS[status] };
    case "running":
      return { visual: "running", defaultOpen: true, label: LABELS[status] };
    case "completed":
      return { visual: "done", defaultOpen: false, label: LABELS[status] };
    case "failed":
      return { visual: "error", defaultOpen: true, label: LABELS[status] };
    case "suspended":
      return { visual: "hitl", defaultOpen: true, label: LABELS[status] };
    case "interrupted":
      return { visual: "muted", defaultOpen: false, label: LABELS[status] };
  }
}
