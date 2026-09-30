// Labels lane: keepalive close-flush + localStorage persistence.
// LabelsStore pushes eagerly on every write, but a tab closed within
// milliseconds of a label edit would strand it (rows lived in memory
// only) — the hide listener re-fires the flush as keepalive, and every
// mutation persists so a reload re-pushes it.
import { describe, expect, test } from "bun:test";
import { LabelsStore } from "../web/store/labels.js";

interface Recorded {
  method: string;
  path: string;
  keepalive?: boolean | undefined;
  body?: unknown;
}

function makeFetch() {
  const requests: Recorded[] = [];
  let cursor = 0;
  const fetchFn = (async (url: string, init?: RequestInit) => {
    const u = new URL(url, "http://x");
    requests.push({
      method: init?.method ?? "GET",
      path: u.pathname + u.search,
      keepalive: init?.keepalive,
      body: init?.body ? JSON.parse(init.body as string) : undefined,
    });
    if (u.pathname === "/api/labels/sync" && init?.method === "POST") {
      cursor += 1;
      return Response.json({ applied: 1, deltas: [], cursor });
    }
    if (u.pathname === "/api/labels") {
      return Response.json({ labels: [], cursor });
    }
    return new Response("not found", { status: 404 });
  }) as unknown as typeof fetch;
  return { requests, fetchFn };
}

function stubStorage() {
  const g = globalThis as unknown as Record<string, unknown>;
  const saved = g["localStorage"];
  const backing = new Map<string, string>();
  g["localStorage"] = {
    getItem: (key: string) => (backing.has(key) ? backing.get(key)! : null),
    setItem: (key: string, value: string) => {
      backing.set(key, value);
    },
    removeItem: (key: string) => {
      backing.delete(key);
    },
    clear: () => backing.clear(),
  };
  return {
    backing,
    restore: () => {
      if (saved === undefined) delete g["localStorage"];
      else g["localStorage"] = saved;
    },
  };
}

function stubHiddenPage() {
  const g = globalThis as unknown as Record<string, unknown>;
  const savedDocument = g["document"];
  const savedWindow = g["window"];
  let visibilityHandler: (() => void) | undefined;
  g["document"] = {
    visibilityState: "hidden",
    addEventListener: (_type: string, fn: () => void) => {
      visibilityHandler = fn;
    },
    removeEventListener: () => {},
  };
  g["window"] = { addEventListener: () => {}, removeEventListener: () => {} };
  return {
    fireHidden: () => visibilityHandler!(),
    restore: () => {
      if (savedDocument === undefined) delete g["document"];
      else g["document"] = savedDocument;
      if (savedWindow === undefined) delete g["window"];
      else g["window"] = savedWindow;
    },
  };
}

const tick = () => new Promise((r) => setTimeout(r, 0));

describe("LabelsStore", () => {
  test("mutations persist; a new store reloads them", async () => {
    const storage = stubStorage();
    try {
      const { fetchFn } = makeFetch();
      const first = new LabelsStore({ fetchFn });
      first.create("Work");
      expect(storage.backing.has("keeps-labels")).toBe(true);
      const second = new LabelsStore({ fetchFn });
      expect(second.all().map((l) => l.name)).toEqual(["Work"]);
    } finally {
      storage.restore();
    }
  });

  test("flush({ keepalive: true }) marks every request keepalive", async () => {
    const { requests, fetchFn } = makeFetch();
    const store = new LabelsStore({ fetchFn });
    store.create("Work");
    await tick(); // let the eager write-flush land, then measure only ours
    requests.length = 0;
    await store.flush({ keepalive: true });
    expect(requests.length).toBeGreaterThan(0);
    expect(requests.every((r) => r.keepalive === true)).toBe(true);
  });

  test("hiding the page triggers a keepalive labels flush", async () => {
    const { requests, fetchFn } = makeFetch();
    const page = stubHiddenPage();
    try {
      const store = new LabelsStore({ fetchFn });
      store.create("Work");
      await tick();
      requests.length = 0;
      page.fireHidden();
      await tick();
      const push = requests.find((r) => r.path === "/api/labels/sync");
      expect(push?.method).toBe("POST");
      expect(push?.keepalive).toBe(true);
    } finally {
      page.restore();
    }
  });
});
