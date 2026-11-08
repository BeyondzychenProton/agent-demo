import type { ViewItem } from "@/readmodel/threadStore";
import type { ToolCardViewModel } from "@/types/chat";

export function pickOldestPending(items: ViewItem[]): ViewItem | null {
  for (const entry of items) {
    if (entry.item.kind !== "tool-card") continue;
    const card = entry.item as ToolCardViewModel;
    if (card.status === "suspended" && card.hitl?.canRespond) return entry;
  }
  return null;
}
