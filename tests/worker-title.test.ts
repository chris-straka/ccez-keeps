// Note titles (worker/title.ts): POST /api/title answers a few words for a
// note's text, only for enrolled devices or an Access session.
import { beforeEach, describe, expect, test } from "bun:test";
import { Database } from "bun:sqlite";
import { drizzle } from "drizzle-orm/bun-sqlite";
import { enrollDevice } from "../worker/devices.js";
import { createApp } from "../worker/index.js";
import type { NotesDb } from "../worker/notes.js";
import {
  cleanTitle,
  DEEPSEEK_URL,
  deepseekTitler,
  TitleError,
  titlerFor,
} from "../worker/title.js";

const DDL = `CREATE TABLE device_tokens (
  id text PRIMARY KEY NOT NULL,
  tokenHash text NOT NULL UNIQUE,
  deviceName text DEFAULT '' NOT NULL,
  createdAt integer DEFAULT 0 NOT NULL,
  lastSeenAt integer DEFAULT 0 NOT NULL,
  revoked integer DEFAULT 0 NOT NULL
);`;

let db: NotesDb;
const asked: string[] = [];
const make = (titler?: (t: string) => Promise<string | null>) =>
  createApp({
    db,
    assetsFetch: () => new Response("stub", { status: 404 }),
    access: { teamDomain: "team.example.com", aud: "aud" },
    titler,
  });

beforeEach(() => {
  const sqlite = new Database(":memory:");
  sqlite.exec(DDL);
  db = drizzle(sqlite);
  asked.length = 0;
});

const post = (app: ReturnType<typeof make>, token: string | null, body: unknown) =>
  app.request("http://localhost/api/title", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });

describe("POST /api/title", () => {
  test("names a note's text for an enrolled device", async () => {
    const app = make(async (t) => {
      asked.push(t);
      return "Liste de courses";
    });
    const { token } = await enrollDevice(db, "phone");
    const res = await post(app, token, { text: "lait, œufs, farine" });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ title: "Liste de courses" });
    expect(asked).toEqual(["lait, œufs, farine"]);
  });

  test("refuses callers with neither a device token nor Access", async () => {
    const app = make(async () => "x");
    expect((await post(app, null, { text: "hi" })).status).toBe(401);
  });

  test("503 without a key, 400 without text", async () => {
    const { token } = await enrollDevice(db, "phone");
    const off = await post(make(), token, { text: "hi" });
    expect(off.status).toBe(503);
    expect(((await off.json()) as { error: string }).error).toContain("DEEPSEEK_API_KEY");
    expect((await post(make(async () => "x"), token, { text: " " })).status).toBe(400);
  });
});

describe("POST /api/title when DeepSeek refuses", () => {
  test("502 with DeepSeek's reason, and a log line saying it", async () => {
    const { token } = await enrollDevice(db, "phone");
    const warned: string[] = [];
    const warn = console.warn;
    console.warn = (...args: unknown[]) => void warned.push(args.join(" "));
    try {
      const res = await post(
        make(async () => {
          throw new TitleError(402);
        }),
        token,
        { text: "lait" },
      );
      expect(res.status).toBe(502);
      expect(((await res.json()) as { error: string }).error).toBe(
        "DeepSeek's balance is used up (402): top up at platform.deepseek.com",
      );
    } finally {
      console.warn = warn;
    }
    expect(warned).toEqual([
      "[keeps] title failed: DeepSeek's balance is used up (402): top up at platform.deepseek.com",
    ]);
  });
});

const KEY = "sk-test-fake";
const reply = (content: unknown, status = 200) =>
  new Response(JSON.stringify({ choices: [{ message: { content } }] }), { status });

describe("deepseekTitler", () => {
  test("asks deepseek-flash with thinking off and cleans the answer", async () => {
    let url = "";
    let headers: Record<string, string> = {};
    let sent: Record<string, unknown> = {};
    const fake = (async (u: string, init: RequestInit) => {
      url = u;
      headers = init.headers as Record<string, string>;
      sent = JSON.parse(String(init.body));
      return reply('"Réunion de lundi."');
    }) as unknown as typeof fetch;
    expect(await deepseekTitler(KEY, fake)("ordre du jour")).toBe("Réunion de lundi");
    expect(url).toBe("https://api.deepseek.com/chat/completions");
    expect(url).toBe(DEEPSEEK_URL);
    expect(headers.Authorization).toBe(`Bearer ${KEY}`);
    expect(sent.model).toBe("deepseek-flash");
    expect(sent.thinking).toEqual({ type: "disabled" });
    expect(sent.tools).toBeUndefined();
    expect(sent.reasoning_effort).toBeUndefined();
    const messages = sent.messages as Array<{ role: string; content: string }>;
    expect(messages.map((m) => m.role)).toEqual(["system", "user"]);
    expect(messages[1]?.content).toBe("ordre du jour");
  });

  test("null when the answer holds no title", async () => {
    const empty = (async () => reply(null)) as unknown as typeof fetch;
    expect(await deepseekTitler(KEY, empty)("x")).toBeNull();
    const none = (async () => new Response("{}")) as unknown as typeof fetch;
    expect(await deepseekTitler(KEY, none)("x")).toBeNull();
  });

  test("words 401, 402 and 429 for the logs", async () => {
    const cases: Array<[number, RegExp]> = [
      [401, /rejected the key \(401\).*DEEPSEEK_API_KEY/],
      [402, /balance is used up \(402\): top up at platform\.deepseek\.com/],
      [429, /rate-limiting \(429\)/],
      [500, /DeepSeek answered 500/],
    ];
    for (const [status, words] of cases) {
      const fake = (async () => new Response("{}", { status })) as unknown as typeof fetch;
      const err = await deepseekTitler(KEY, fake)("x").catch((e: unknown) => e);
      expect(err).toBeInstanceOf(TitleError);
      expect((err as TitleError).status).toBe(status);
      expect((err as TitleError).message).toMatch(words);
    }
  });

  test("no key, no titler (the route answers 503)", () => {
    expect(titlerFor(undefined)).toBeUndefined();
    expect(titlerFor("")).toBeUndefined();
    expect(typeof titlerFor(KEY)).toBe("function");
  });

  test("cleanTitle drops labels and refuses long answers", () => {
    expect(cleanTitle("Titre : Voyage à Lyon")).toBe("Voyage à Lyon");
    expect(cleanTitle("x".repeat(90))).toBeNull();
  });
});
