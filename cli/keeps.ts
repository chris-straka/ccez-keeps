#!/usr/bin/env bun
// `keeps`: the owner's notes from a terminal, for agents and for the owner.
//
//   keeps ls [--archived] [--trash] [--label NAME] [--json]
//   keeps show <id...> [--json]
//   keeps export [--label NAME] [--archived]        markdown of every match
//   keeps rm <id...> | --label NAME | --all          trash (30-day retention)
//   keeps archive <id...>
//   keeps add [--title T] [--label NAME] [--color C] <text> | -   (stdin)
//   keeps labels
//   keeps whoami                                     token check
//
// Ids may be unique prefixes. Credentials: KEEPS_TOKEN or
// ~/.config/ccez-keeps/token (a device token, README "Agents");
// KEEPS_URL overrides the server (default https://keeps.ccez.uk).
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import {
  archived,
  findLabel,
  formatList,
  formatMarkdown,
  makeNote,
  minOrder,
  noteText,
  pull,
  push,
  resolveIds,
  select,
  tombstone,
  type KeepsClient,
} from "./lib.js";

const USAGE = `usage:
  keeps ls [--archived] [--trash] [--label NAME] [--json]
  keeps show <id...> [--json]
  keeps export [--label NAME] [--archived]
  keeps rm <id...> | --label NAME | --all
  keeps archive <id...>
  keeps add [--title T] [--label NAME] [--color C] <text> | -
  keeps labels
  keeps whoami`;

interface Args {
  cmd: string;
  rest: string[];
  flags: Map<string, string | true>;
}

function parse(argv: string[]): Args {
  const [cmd = "", ...tail] = argv;
  const rest: string[] = [];
  const flags = new Map<string, string | true>();
  const valued = new Set(["label", "title", "color"]);
  for (let i = 0; i < tail.length; i += 1) {
    const a = tail[i]!;
    if (a.startsWith("--")) {
      const name = a.slice(2);
      if (valued.has(name)) {
        const v = tail[i + 1];
        if (v === undefined) throw new Error(`--${name} needs a value`);
        flags.set(name, v);
        i += 1;
      } else {
        flags.set(name, true);
      }
    } else {
      rest.push(a);
    }
  }
  return { cmd, rest, flags };
}

function str(flags: Args["flags"], name: string): string | undefined {
  const v = flags.get(name);
  return typeof v === "string" ? v : undefined;
}

function loadToken(): string {
  const env = process.env["KEEPS_TOKEN"];
  if (env && env.trim() !== "") return env.trim();
  const path = join(homedir(), ".config", "ccez-keeps", "token");
  try {
    return readFileSync(path, "utf8").trim();
  } catch {
    throw new Error(`no token: set KEEPS_TOKEN or write a device token to ${path}`);
  }
}

async function main(argv: string[]): Promise<number> {
  const { cmd, rest, flags } = parse(argv);
  if (cmd === "" || cmd === "help" || cmd === "--help" || cmd === "-h") {
    console.log(USAGE);
    return cmd === "" ? 2 : 0;
  }
  const c: KeepsClient = {
    base: (process.env["KEEPS_URL"] ?? "https://keeps.ccez.uk").replace(/\/$/, ""),
    token: loadToken(),
    fetch: (url, init) => fetch(url, init),
  };
  const json = flags.has("json");
  const label = str(flags, "label");
  const now = Date.now();

  switch (cmd) {
    case "whoami": {
      const res = await c.fetch(`${c.base}/api/devices`, {
        headers: { Authorization: `Bearer ${c.token}` },
      });
      if (!res.ok) throw new Error(`token rejected: ${res.status}`);
      const body = (await res.json()) as {
        devices: Array<{ id: string; deviceName: string; revoked: boolean }>;
      };
      console.log(`${c.base}: token accepted; ${body.devices.length} device(s) enrolled`);
      for (const d of body.devices) {
        console.log(`  ${d.id.slice(0, 8)}  ${d.deviceName || "(unnamed)"}${d.revoked ? "  revoked" : ""}`);
      }
      return 0;
    }
    case "labels": {
      const snap = await pull(c);
      for (const l of snap.labels) {
        const n = snap.notes.filter((x) => !x.deleted && x.labelIds.includes(l.id)).length;
        console.log(`${l.name}  (${n})`);
      }
      return 0;
    }
    case "ls": {
      const snap = await pull(c);
      const notes = select(snap, {
        archived: flags.has("archived"),
        trash: flags.has("trash"),
        ...(label !== undefined ? { label } : {}),
      });
      if (json) {
        console.log(JSON.stringify(notes.map((n) => ({ ...n, attachments: n.attachments.length }))));
      } else {
        console.log(notes.length === 0 ? "(no notes)" : formatList(notes, snap.labels));
      }
      return 0;
    }
    case "show": {
      if (rest.length === 0) throw new Error("show: give at least one id");
      const snap = await pull(c);
      const notes = resolveIds(snap.notes, rest);
      if (json) console.log(JSON.stringify(notes));
      else console.log(formatMarkdown(notes, snap.labels));
      return 0;
    }
    case "export": {
      const snap = await pull(c);
      const notes = select(snap, {
        archived: flags.has("archived"),
        ...(label !== undefined ? { label } : {}),
      });
      console.log(json ? JSON.stringify(notes) : formatMarkdown(notes, snap.labels));
      return 0;
    }
    case "rm":
    case "archive": {
      const snap = await pull(c);
      let targets: typeof snap.notes;
      if (cmd === "rm" && flags.has("all")) {
        targets = select(snap, { archived: flags.has("archived") });
      } else if (cmd === "rm" && label !== undefined && rest.length === 0) {
        targets = select(snap, { label, archived: flags.has("archived") });
      } else {
        if (rest.length === 0) throw new Error(`${cmd}: give ids, or rm --label NAME / --all`);
        targets = resolveIds(
          snap.notes.filter((n) => !n.deleted),
          rest,
        );
      }
      if (targets.length === 0) {
        console.log("nothing to do");
        return 0;
      }
      const rows = targets.map((n) => (cmd === "rm" ? tombstone(n, now) : archived(n, now)));
      const applied = await push(
        c,
        cmd === "rm" ? [] : rows,
        cmd === "rm" ? rows : [],
        snap.cursor,
      );
      console.log(`${cmd === "rm" ? "trashed" : "archived"} ${applied} of ${targets.length}`);
      for (const n of targets) console.log(`  ${n.id.slice(0, 8)}  ${n.title.trim() || noteText(n).slice(0, 60)}`);
      return applied === targets.length ? 0 : 1;
    }
    case "add": {
      let body = rest.join(" ");
      if (body === "-" || (body === "" && !process.stdin.isTTY)) {
        body = await Bun.stdin.text();
      }
      body = body.trimEnd();
      const title = str(flags, "title") ?? "";
      if (body === "" && title === "") throw new Error("add: nothing to save");
      const snap = await pull(c);
      let labelIds: string[] = [];
      if (label !== undefined) {
        const l = findLabel(snap.labels, label);
        if (!l) throw new Error(`no label named ${label} (keeps labels)`);
        labelIds = [l.id];
      }
      const note = makeNote(
        {
          title,
          body,
          labelIds,
          ...(str(flags, "color") !== undefined ? { color: str(flags, "color")! } : {}),
        },
        crypto.randomUUID(),
        now,
        minOrder(snap.notes),
      );
      const applied = await push(c, [note], [], snap.cursor);
      console.log(applied === 1 ? `saved ${note.id.slice(0, 8)}` : "server kept an older copy");
      return applied === 1 ? 0 : 1;
    }
    default:
      console.error(`unknown command: ${cmd}\n${USAGE}`);
      return 2;
  }
}

main(process.argv.slice(2)).then(
  (code) => process.exit(code),
  (err: unknown) => {
    console.error(err instanceof Error ? err.message : String(err));
    process.exit(1);
  },
);
