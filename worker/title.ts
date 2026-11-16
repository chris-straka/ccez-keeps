// Note titles (owner, 2026-11-10: "I should not be allowed to set the
// title"): clients send a saved note's text to POST /api/title and get a
// few words back from DeepSeek (deepseek-flash, thinking off; its key the
// wrangler secret DEEPSEEK_API_KEY). The client writes the title into its
// note and syncs it like any edit. Unset key = 503; the note just stays
// untitled.

/** DeepSeek's chat completions and model: the one place they live. */
export const DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";
export const TITLE_MODEL = "deepseek-flash";
/** Text sent for a title: the opening of the note is enough. */
const MAX_TEXT = 4000;

export type Titler = (text: string) => Promise<string | null>;

/** Why DeepSeek gave no title, worded for whoever reads Workers Logs. */
export function deepseekProblem(status: number): string {
  switch (status) {
    case 401:
      return "DeepSeek rejected the key (401): check the Worker's DEEPSEEK_API_KEY secret";
    case 402:
      return "DeepSeek's balance is used up (402): top up at platform.deepseek.com";
    case 429:
      return "DeepSeek is rate-limiting (429): too many title requests at once";
    default:
      return `DeepSeek answered ${status}`;
  }
}

/** A failed answer from DeepSeek, with its HTTP status. */
export class TitleError extends Error {
  readonly status: number;
  constructor(status: number) {
    super(deepseekProblem(status));
    this.name = "TitleError";
    this.status = status;
  }
}

/** A model's title as one short line: no quotes, no full stop, no "Title:". */
export function cleanTitle(raw: string): string | null {
  const line = (raw.trim().split(/\n/)[0] ?? "")
    .replace(/^(title|titre)\s*:\s*/i, "")
    .replace(/^["'“«*#\s]+|["'”»*\s]+$/g, "")
    .replace(/[.。]$/, "")
    .trim();
  if (!line || line.length > 80) return null;
  return line;
}

/** Titles from DeepSeek. Null when the answer had no usable title; throws
 * TitleError when DeepSeek refused (wrong key, empty balance, rate limit). */
export function deepseekTitler(
  key: string,
  fetchImpl: typeof fetch = fetch,
): Titler {
  return async (text) => {
    const res = await fetchImpl(DEEPSEEK_URL, {
      method: "POST",
      headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: TITLE_MODEL,
        thinking: { type: "disabled" },
        messages: [
          {
            role: "system",
            content:
              "Write a title for this note in two to six words, in the note's own language. " +
              "Reply with the title only: no quotes, no full stop.",
          },
          { role: "user", content: text.slice(0, MAX_TEXT) },
        ],
      }),
    });
    if (!res.ok) throw new TitleError(res.status);
    const data = (await res.json()) as {
      choices?: Array<{ message?: { content?: unknown } }>;
    };
    const content = data.choices?.[0]?.message?.content;
    return typeof content === "string" ? cleanTitle(content) : null;
  };
}

/** The Worker's titler: none (titles 503) without a key. */
export function titlerFor(key: string | undefined): Titler | undefined {
  return key ? deepseekTitler(key) : undefined;
}
