// Lane A bootstrap (+E/F): render from IndexedDB first (instant repeat
// visits), pull in the background, Service Worker app shell, "/" focuses
// search, boot failures render an error instead of a blank page.
import "./components/keeps-app.js";
import type { KeepsApp } from "./components/keeps-app.js";
import "./components/note-card.js";
import "./components/note-editor.js";
import "./components/drawing-dialog.js";
import { IdbStore } from "./store/idb-store.js";
import { LabelsStore } from "./store/labels.js";
import { SyncEngine } from "./store/sync.js";
import { bootFailureText } from "./sync-status.js";

function renderBootError(message: string): void {
  const root = document.getElementById("app");
  if (!root) return;
  root.innerHTML = "";
  const div = document.createElement("div");
  div.className = "empty";
  div.textContent = `Keeps couldn't start: ${message}`;
  root.appendChild(div);
}

async function main(): Promise<void> {
  const store = new IdbStore();
  await store.ready;

  const root = document.getElementById("app");
  if (!root) throw new Error("missing #app");
  const app = document.createElement("keeps-app") as KeepsApp;
  root.appendChild(app);

  const sync = new SyncEngine(
    store,
    {
      onStatus: (status) => app.setSyncStatus(status),
    },
    store,
  );
  const labels = new LabelsStore();
  await app.connect(store, sync, undefined, labels);
  // Labels converge in the background like notes; failures stay silent
  // (the sidebar renders from cache and every label write retries).
  void labels.pull().catch((error: unknown) => {
    console.error("[keeps] labels boot pull failed:", error);
  });

  document.addEventListener("keydown", (event) => {
    if (event.metaKey || event.ctrlKey || event.altKey) return;
    const active = document.activeElement as HTMLElement | null;
    const typing =
      !!active && /^(INPUT|TEXTAREA|SELECT)$/.test(active.tagName);
    if (event.key === "/" && !typing) {
      event.preventDefault();
      document.querySelector<HTMLInputElement>(".search")?.focus();
    } else if ((event.key === "m" || event.key === "M") && !typing) {
      event.preventDefault();
      app.toggleNav();
    }
  });

  if ("serviceWorker" in navigator) {
    try {
      await navigator.serviceWorker.register("./sw.js");
    } catch {
      // Offline shell is a progressive enhancement; the app works without it.
    }
  }

  // Instant paint from cache, then converge in the background. One slow
  // request (cold edge, waking D1) must not condemn the session: retry once.
  const bootPull = () =>
    sync.pull().catch((error: unknown) => {
      console.error("[keeps] boot pull failed:", error);
      app.querySelector(".sync-status")!.textContent = bootFailureText(
        error,
        navigator.onLine,
      );
    });
  void bootPull().then(() => {
    if (app.querySelector(".sync-status")?.textContent !== "") {
      setTimeout(() => {
        void sync.flush().catch((error: unknown) => {
          console.error("[keeps] boot retry failed:", error);
        });
      }, 3000);
    }
  });
}

main().catch((error: unknown) => {
  renderBootError(error instanceof Error ? error.message : String(error));
});
