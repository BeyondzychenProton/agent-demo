import type {
  ProcessActivity,
  ProcessActivitySummary,
  RunningProcessActivity,
} from "@/components/chat/processActivity";

const DONE: Record<ProcessActivity, string> = {
  thinking: "已完成分析",
  read: "已读取文件",
  readImage: "已读取图片",
  write: "已写入文件",
  search: "已搜索代码",
  edit: "修改了文件",
  commands: "执行了命令",
  code: "运行了代码",
  webSearch: "已搜索网页",
  webFetch: "已访问网页",
  subagents: "已协调子智能体",
  plan: "更新了计划",
  questions: "向用户提出了问题",
  tools: "已调用工具",
  context: "已注入上下文",
};

const RUNNING: Record<RunningProcessActivity | "thinking", string> = {
  thinking: "正在分析请求",
  read: "正在读取文件",
  readImage: "正在读取图片",
  write: "正在写入文件",
  search: "正在搜索代码",
  edit: "正在编辑文件",
  commands: "正在运行命令",
  code: "正在运行代码",
  webSearch: "正在搜索网页",
  webFetch: "正在访问网页",
  subagents: "正在协调子智能体",
  plan: "正在更新计划",
  questions: "等待你的操作",
  tools: "正在调用工具",
};

const PREPARE: Record<RunningProcessActivity, string> = {
  read: "准备读取文件",
  readImage: "准备读取图片",
  write: "准备写入文件",
  search: "准备搜索代码",
  edit: "准备编辑文件",
  commands: "准备运行命令",
  code: "准备运行代码",
  webSearch: "准备搜索网页",
  webFetch: "准备访问网页",
  subagents: "准备协调子智能体",
  plan: "准备更新计划",
  questions: "准备提问",
  tools: "准备调用工具",
};

const SHARED_PREFIX = "已";
const COMMA = "，";
const SEPARATOR = " · ";

function continuation(label: string): string {
  return label.charAt(0).toLowerCase() + label.slice(1);
}

/** Port of deepseek-harness `processTitle` (zh). */
export function processTitle(summary: ProcessActivitySummary): string {
  const labels = summary.counts.slice(0, 3).map(({ kind }) => DONE[kind] ?? DONE.tools);
  const first = labels[0];
  if (first === undefined) return DONE.thinking;
  const second = labels[1];
  if (second === undefined) return first;
  if (labels.length === 2) {
    const shared = first.startsWith(SHARED_PREFIX) && second.startsWith(SHARED_PREFIX);
    const right = continuation(shared ? second.slice(SHARED_PREFIX.length) : second);
    return `${first}并${right}`;
  }
  const title = [first, ...labels.slice(1).map(continuation)].join(COMMA);
  return summary.counts.length > 3 ? `${title}等` : title;
}

/**
 * Live ChatGroupSeat ProcessGroupHeader title: activity / prepare label + optional detail.
 */
export function liveProcessTitle(summary: ProcessActivitySummary): string {
  const activity: RunningProcessActivity | "thinking" = summary.running ?? "thinking";
  const label = summary.preparing
    ? PREPARE[activity === "thinking" ? "tools" : activity]
    : RUNNING[activity];
  const detail = summary.runningDetail;
  return detail === "" ? label : `${label}${SEPARATOR}${detail}`;
}
