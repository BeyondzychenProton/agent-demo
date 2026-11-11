export function formatDuration(
  durationMs: number | undefined | null,
): string | null {
  if (durationMs == null) {
    return null;
  }
  if (durationMs < 1000) {
    return `${durationMs}ms`;
  }
  if (durationMs < 60_000) {
    const seconds = durationMs / 1000;
    return `${Math.round(seconds * 10) / 10}s`;
  }
  const minutes = Math.floor(durationMs / 60_000);
  const seconds = Math.floor((durationMs % 60_000) / 1000);
  return `${minutes}分${seconds}秒`;
}
