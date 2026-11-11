/** Port of deepseek-harness `formatRunDuration` + turn-process completion labels. */

export interface RunDurationPart {
  readonly text: string;
  readonly numeric: boolean;
}

export function formatRunDuration(ms: number): RunDurationPart[] {
  const total = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor(total / 60) % 60;
  const seconds = total % 60;
  const parts: RunDurationPart[] = [];
  if (hours > 0) {
    parts.push({ text: String(hours), numeric: true }, { text: "小时", numeric: false });
  }
  if (total >= 60) {
    parts.push({ text: String(minutes), numeric: true }, { text: "分", numeric: false });
  }
  parts.push({ text: String(seconds), numeric: true }, { text: "秒", numeric: false });
  return parts;
}

export function formatRunDurationLabel(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return "已完成";
  return `已完成，用时 ${formatRunDuration(ms).map((part) => part.text).join("")}`;
}
