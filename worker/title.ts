// Note titles (owner, 2026-11-10: "I should not be allowed to set the
// title"): clients send a saved note's text to POST /api/title and get a
// few words back from Muse (muse-spark-1.3-contributor, its key a wrangler
// secret, MUSE_API_KEY). The client writes the title into its note and
// syncs it like any edit. Unset key = 503; the note just stays untitled.

export const TITLE_MODEL = "muse-spark-1.3-contributor";
const MUSE_URL = "https://api.meta.ai/v1/chat/completions";
/** Text sent for a title: the opening of the note is enough. */
const MAX_TEXT = 4000;

export type Titler = (text: string) => Promise<string | null>;

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

export function museTitler(
  key: string,
  fetchImpl: typeof fetch = fetch,
): Titler {
  return async (text) => {
    const res = await fetchImpl(MUSE_URL, {
      method: "POST",
      headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: TITLE_MODEL,
        reasoning_effort: "minimal",
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
    if (!res.ok) return null;
    const data = (await res.json()) as {
      choices?: Array<{ message?: { content?: string } }>;
    };
    return cleanTitle(data.choices?.[0]?.message?.content ?? "");
  };
}
