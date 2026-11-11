/** Port of deepseek-harness `reasoning-text` helpers used by ReasoningRow. */

/** Strip optional signature wrapper; otherwise return the raw reasoning body. */
export function reasoningText(text: string): string {
  if (text[0] !== "<") return text;
  const opening = "<signature>";
  if (opening.startsWith(text)) return "";
  if (!text.startsWith(opening)) return text;
  const closing = "</signature>";
  const end = text.indexOf(closing, opening.length);
  if (end === -1) return "";
  let start = end + closing.length;
  while (start < text.length) {
    if (text[start] === "\n") start += 1;
    else if (text[start] === "\r" && text[start + 1] === "\n") start += 2;
    else break;
  }
  return text.slice(start);
}

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
