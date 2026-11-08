/** Live scroll metrics for stick-to-bottom; do not use a lagged virtualizer totalSize. */

export function isNearBottom(
  scrollTop: number,
  scrollHeight: number,
  clientHeight: number,
  threshold = 100,
): boolean {
  return scrollHeight - scrollTop - clientHeight < threshold;
}

export function stickScrollToBottom(element: {
  scrollTop: number;
  scrollHeight: number;
  clientHeight: number;
}): void {
  element.scrollTop = Math.max(0, element.scrollHeight - element.clientHeight);
}
