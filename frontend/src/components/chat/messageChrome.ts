const PREVIEW_CHARS = 120;
const PREVIEW_LINES = 2;

/** First two lines, capped at 120 characters. Longer reasoning needs a separate full-body control. */
export function reasoningPreview(text: string): { preview: string; needsDetail: boolean } {
  const body = text.replace(/\s+$/u, "");
  const lines = body.split(/\r?\n/);
  const head = lines.slice(0, PREVIEW_LINES).join("\n");
  const chars = Array.from(head);
  const needsDetail = lines.length > PREVIEW_LINES || chars.length > PREVIEW_CHARS;
  const preview = chars.length > PREVIEW_CHARS ? chars.slice(0, PREVIEW_CHARS).join("") : head;
  return { preview, needsDetail };
}

const LOCAL_PHASE_LABEL: Record<string, string> = {
  sending: "正在发送",
  accepted: "已接收，正在同步",
  uncertain: "接收结果待确认",
  completed: "已完成，正在同步",
  rejected: "提交未被接受",
};

export function messageChrome(input: {
  role: "user" | "assistant" | "reasoning";
  partial?: boolean;
  localPhase?: string | null;
}): { eyebrow: string; showCaret: boolean } {
  if (input.role === "user") {
    const phase = input.localPhase ? LOCAL_PHASE_LABEL[input.localPhase] : undefined;
    return {
      eyebrow: phase ? `用户 · ${phase}` : "用户",
      showCaret: false,
    };
  }
  if (input.role === "reasoning") {
    return {
      eyebrow: input.partial ? "深度思考中…" : "已思考",
      showCaret: !!input.partial,
    };
  }
  return {
    eyebrow: "助手",
    showCaret: !!input.partial,
  };
}
