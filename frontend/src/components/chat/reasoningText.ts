/** Port of deepseek-harness `reasoning-text` helpers used by ReasoningRow. */

/** First line of the block (settled summary). */
export function firstLine(text: string): string {
  const newline = text.indexOf("\n");
  return newline === -1 ? text : text.slice(0, newline);
}

/**
 * While streaming, advance the collapsed preview only when a paragraph's first
 * line has completed (DeepSeek ReasoningRow behavior).
 */
export function latestCompletedParagraphFirstLine(text: string): string {
  let summary = "";
  let paragraphStart = 0;
  const separator = /\r?\n(?:[\t ]*\r?\n)+/g;
  while (true) {
    const nextParagraph = separator.exec(text);
    const paragraphEnd =
      nextParagraph === null ? text.length : nextParagraph.index + nextParagraph[0].indexOf("\n");
    const newline = text.indexOf("\n", paragraphStart);
    if (newline !== -1 && newline <= paragraphEnd) {
      const candidate = text.slice(paragraphStart, newline).trim();
      if (candidate !== "") summary = candidate;
    }
    if (nextParagraph === null) return summary;
    paragraphStart = nextParagraph.index + nextParagraph[0].length;
  }
}

export function reasoningSummary(text: string, running: boolean): string {
  const raw = running ? latestCompletedParagraphFirstLine(text) : firstLine(text);
  return raw.replace(/\*\*/g, "").trim();
}
