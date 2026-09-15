// Lane F: honest boot-sync status. A failed first pull is not always
// "offline" — say what actually happened so a real failure can be told
// apart from airplane mode. Pure function, tested below.
export function bootFailureText(error: unknown, online: boolean): string {
  if (!online) return "Offline — changes saved locally";
  const message = error instanceof Error ? error.message : String(error);
  const status = /(\d{3})/.exec(message)?.[1];
  if (status) return `Sync unavailable (HTTP ${status}) — retrying`;
  return "Sync unavailable (network) — retrying";
}
