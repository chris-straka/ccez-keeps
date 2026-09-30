// Service Worker routing (web/sw.js): navigations go network-first so a
// dead Access session redirects to login instead of serving a stale shell
// whose sync 401s forever; hashed assets stay cache-first; /api/* bypasses.
// The worker source runs unmodified via `new Function`, with the three
// ServiceWorker globals (`self`, `caches`, `fetch`) injected as fakes.
import { beforeEach, describe, expect, test } from "bun:test";

const ORIGIN = "https://keeps.cstraka.dev";
const LOGIN_URL = "https://silent-bread-ade3.cloudflareaccess.com/cdn-cgi/access/login/x";

interface FakeResponse {
  ok: boolean;
  url: string;
  body: string;
  clone: () => FakeResponse;
}

function fakeResponse(url: string, body: string): FakeResponse {
  const res: FakeResponse = {
    ok: true,
    url,
    body,
    clone: () => fakeResponse(url, body),
  };
  return res;
}

interface FetchEventStub {
  request: Request;
  respondWith: (response: unknown) => void;
}

type FetchHandler = (event: FetchEventStub) => void;

const listeners = new Map<string, FetchHandler>();
const fakeSelf = {
  location: { origin: ORIGIN },
  addEventListener: (type: string, fn: FetchHandler) => {
    listeners.set(type, fn);
  },
};

let cached = new Map<string, FakeResponse>();
let puts: Array<{ url: string; response: FakeResponse }> = [];
let fetchCalls: string[] = [];
let fetchImpl: (req: Request) => Promise<FakeResponse> = () =>
  Promise.reject(new Error("unexpected fetch"));

const fakeCaches = {
  open: async (_name: string) => ({
    put: async (req: Request, res: FakeResponse) => {
      puts.push({ url: req.url, response: res });
      cached.set(req.url, res);
    },
  }),
  // Like the real Cache API, relative URL strings resolve against the scope.
  match: async (req: Request | string): Promise<FakeResponse | undefined> => {
    const key = typeof req === "string" ? new URL(req, ORIGIN).href : req.url;
    return cached.get(key);
  },
};

const runSw = new Function("self", "caches", "fetch", await Bun.file("web/sw.js").text()) as (
  self: unknown,
  caches: unknown,
  fetchFn: unknown,
) => void;
runSw(fakeSelf, fakeCaches, (req: Request) => {
  fetchCalls.push(req.url);
  return fetchImpl(req);
});

function request(url: string, init: { mode?: string; method?: string } = {}): Request {
  const req = new Request(url, init.method ? { method: init.method } : undefined);
  if (init.mode !== undefined) {
    // The Request constructor rejects mode "navigate"; navigations built
    // by the browser carry it, so stamp it on.
    Object.defineProperty(req, "mode", { value: init.mode });
  }
  return req;
}

function dispatch(req: Request): { called: boolean; response: Promise<unknown> } {
  let resolve!: (value: unknown) => void;
  const response = new Promise<unknown>((r) => {
    resolve = r;
  });
  let called = false;
  const handler = listeners.get("fetch");
  if (!handler) throw new Error("sw.js registered no fetch handler");
  handler({
    request: req,
    respondWith: (r: unknown) => {
      called = true;
      resolve(r);
    },
  });
  return { called, response };
}

const tick = () => new Promise((r) => setTimeout(r, 0));

beforeEach(() => {
  cached = new Map();
  puts = [];
  fetchCalls = [];
  fetchImpl = () => Promise.reject(new Error("unexpected fetch"));
});

describe("sw routing", () => {
  test("navigation hits the network even with a cached entry, then refreshes it", async () => {
    cached.set(`${ORIGIN}/`, fakeResponse(`${ORIGIN}/`, "stale"));
    const fresh = fakeResponse(`${ORIGIN}/`, "fresh");
    fetchImpl = async () => fresh;
    const { called, response } = dispatch(request(`${ORIGIN}/`, { mode: "navigate" }));
    expect(called).toBe(true);
    expect(await response).toBe(fresh);
    expect(fetchCalls).toEqual([`${ORIGIN}/`]);
    await tick();
    expect(puts.map((p) => p.url)).toEqual([`${ORIGIN}/`]);
    expect(cached.get(`${ORIGIN}/`)?.body).toBe("fresh");
  });

  test("navigation falls back to its cached entry when offline", async () => {
    const shell = fakeResponse(`${ORIGIN}/`, "shell");
    cached.set(`${ORIGIN}/`, shell);
    fetchImpl = async () => {
      throw new Error("offline");
    };
    const { called, response } = dispatch(request(`${ORIGIN}/`, { mode: "navigate" }));
    expect(called).toBe(true);
    expect(await response).toBe(shell);
    expect(fetchCalls).toEqual([`${ORIGIN}/`]);
  });

  test("navigation falls back to the precached shell for uncached paths", async () => {
    const shell = fakeResponse(`${ORIGIN}/`, "shell");
    cached.set(`${ORIGIN}/`, shell);
    fetchImpl = async () => {
      throw new Error("offline");
    };
    const { response } = dispatch(request(`${ORIGIN}/notes`, { mode: "navigate" }));
    expect(await response).toBe(shell);
  });

  test("login-redirect responses are returned but never cached", async () => {
    const login = fakeResponse(LOGIN_URL, "login");
    fetchImpl = async () => login;
    const { response } = dispatch(request(`${ORIGIN}/`, { mode: "navigate" }));
    expect(await response).toBe(login);
    await tick();
    expect(puts).toEqual([]);
  });

  test("static assets stay cache-first", async () => {
    const js = fakeResponse(`${ORIGIN}/main.abc.js`, "js");
    cached.set(`${ORIGIN}/main.abc.js`, js);
    const { called, response } = dispatch(request(`${ORIGIN}/main.abc.js`));
    expect(called).toBe(true);
    expect(await response).toBe(js);
    expect(fetchCalls).toEqual([]);
  });

  test("uncached assets fetch and store", async () => {
    const css = fakeResponse(`${ORIGIN}/styles.abc.css`, "css");
    fetchImpl = async () => css;
    const { called, response } = dispatch(request(`${ORIGIN}/styles.abc.css`));
    expect(called).toBe(true);
    expect(await response).toBe(css);
    await tick();
    expect(puts.map((p) => p.url)).toEqual([`${ORIGIN}/styles.abc.css`]);
  });

  test("/api/* is never intercepted", async () => {
    const { called } = dispatch(request(`${ORIGIN}/api/notes?since=0`));
    expect(called).toBe(false);
    expect(fetchCalls).toEqual([]);
  });

  test("non-GET is never intercepted", async () => {
    const { called } = dispatch(request(`${ORIGIN}/api/notes/sync`, { method: "POST" }));
    expect(called).toBe(false);
    expect(fetchCalls).toEqual([]);
  });
});
