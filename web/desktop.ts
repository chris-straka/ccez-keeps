// Mac app bridge (desktop/, Tauri). The same bundle runs in the browser
// and in the Mac app; in the app, window.__TAURI__ exists and this module
// swaps fetch so every /api/* call goes through Rust (desktop/src/api.rs),
// which adds the device token. Sync, labels and the Devices panel stay
// unchanged: same paths, same JSON, same statuses. Signing in opens the
// browser once (desktop/src/auth.rs); the app hears "keeps:signed-in".

interface ApiReply {
  status: number;
  body: string;
  contentType: string;
}

type Invoke = <T>(cmd: string, args?: Record<string, unknown>) => Promise<T>;
type Listen = (event: string, handler: (event: { payload: unknown }) => void) => Promise<unknown>;

interface TauriGlobal {
  core: { invoke: Invoke };
  event: { listen: Listen };
}

function tauri(): TauriGlobal | null {
  const t = (globalThis as { __TAURI__?: TauriGlobal }).__TAURI__;
  return t?.core?.invoke ? t : null;
}

/** True inside the Mac app. */
export function isDesktop(): boolean {
  return tauri() !== null;
}

/** "/api/..." path and query of a fetch input, or null if not the API. */
export function apiPath(input: RequestInfo | URL): string | null {
  const raw =
    typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
  if (raw.startsWith("/api/")) return raw;
  try {
    const url = new URL(raw);
    // Same-origin absolute URLs only (tauri://localhost/api/...).
    if (url.origin === globalThis.location?.origin && url.pathname.startsWith("/api/")) {
      return url.pathname + url.search;
    }
  } catch {
    // Relative non-API input: not ours.
  }
  return null;
}

// Statuses whose Response must have no body.
const NULL_BODY = new Set([101, 103, 204, 205, 304]);

/** fetch that sends /api/* through `invoke` and everything else to `fallback`. */
export function desktopFetch(invoke: Invoke, fallback: typeof fetch): typeof fetch {
  const fn = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const path = apiPath(input);
    if (path === null) return fallback(input, init);
    const method =
      init?.method ?? (typeof input === "object" && "method" in input ? input.method : "GET");
    const body = typeof init?.body === "string" ? init.body : undefined;
    let reply: ApiReply;
    try {
      reply = await invoke<ApiReply>("api_fetch", { method, path, body });
    } catch (error) {
      // No answer at all (offline, DNS, timeout): what a browser's fetch throws.
      throw new TypeError(`Failed to fetch: ${String(error)}`);
    }
    return new Response(NULL_BODY.has(reply.status) ? null : reply.body, {
      status: reply.status,
      headers: { "Content-Type": reply.contentType },
    });
  };
  return fn as typeof fetch;
}

/**
 * Route /api/* through the app. Call before any store is built. Returns
 * false (and changes nothing) in a browser.
 */
export function installDesktop(): boolean {
  const t = tauri();
  if (!t) return false;
  const fallback = globalThis.fetch.bind(globalThis);
  globalThis.fetch = desktopFetch(t.core.invoke, fallback);
  return true;
}

/** Open the browser to sign this Mac in. False outside the app. */
export function desktopSignIn(): boolean {
  const t = tauri();
  if (!t) return false;
  void t.core.invoke("sign_in").catch((error: unknown) => {
    console.error("[keeps] sign-in failed to start:", error);
  });
  return true;
}

/** Run `handler` whenever the app signs in or out. */
export function onDesktopAccount(handler: (signedIn: boolean) => void): void {
  const t = tauri();
  if (!t) return;
  void t.event.listen("keeps:signed-in", () => handler(true));
  void t.event.listen("keeps:signed-out", () => handler(false));
  void t.event.listen("keeps:sign-in-failed", (event) => {
    console.error("[keeps] sign-in failed:", event.payload);
  });
}
