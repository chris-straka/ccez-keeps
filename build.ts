// Phase 0 build (+E): bundle the SPA to dist/ and copy static files.
// Lane E additions: manifest, Service Worker, icons.
//
// The JS bundle, its sourcemap, and the stylesheet carry content hashes
// in their filenames (see docs/caching.md): the Service Worker precaches
// the shell cache-first, so stable names would pin returning browsers to
// the old bundle after a deploy.
import { cpSync, mkdirSync, rmSync } from "node:fs";

// dist/ is pure build output: wipe it so hashed files from previous
// builds never accumulate or shadow the fresh bundle.
rmSync("dist", { recursive: true, force: true });
mkdirSync("dist", { recursive: true });

const result = await Bun.build({
  entrypoints: ["web/main.ts"],
  outdir: "dist",
  target: "browser",
  minify: false,
  sourcemap: "external",
});

if (!result.success) {
  for (const log of result.logs) console.error(log);
  process.exit(1);
}

for (const file of [
  "index.html",
  "styles.css",
  "manifest.webmanifest",
  "icon.svg",
  "icon-192.png",
  "icon-512.png",
]) {
  cpSync(`web/${file}`, `dist/${file}`);
}

// Rename dist/<base>.<ext> to dist/<base>.<12-hex>.<ext> (hash of the
// final bytes), returning the new basename.
async function fingerprint(file: string): Promise<string> {
  const bytes = new Uint8Array(await Bun.file(`dist/${file}`).arrayBuffer());
  const digest = Bun.hash(bytes).toString(16).padStart(16, "0").slice(0, 12);
  const strip = file.endsWith(".js.map")
    ? file.slice(0, -7)
    : file.slice(0, file.lastIndexOf("."));
  const ext = file.endsWith(".js.map") ? ".js.map" : file.slice(file.lastIndexOf("."));
  const out = `${strip}.${digest}${ext}`;
  cpSync(`dist/${file}`, `dist/${out}`);
  rmSync(`dist/${file}`);
  return out;
}

await fingerprint("main.js.map");
const jsOut = await fingerprint("main.js");
const cssOut = await fingerprint("styles.css");

let html = await Bun.file("dist/index.html").text();
html = html.replace("./main.js", `./${jsOut}`).replace("./styles.css", `./${cssOut}`);
if (html.includes("./main.js") || html.includes("./styles.css")) {
  throw new Error("build: un-rewritten bundle reference in index.html");
}
await Bun.write("dist/index.html", html);

// Stamp the SW cache name per build as belt-and-braces next to the hashed
// shell entries: any byte-change forces the browser to install the new
// worker, which precaches the new hashed files and drops old caches.
const stamp = Date.now().toString(36);
const sw = await Bun.file("web/sw.js").text();
const stamped = sw
  .replace(/const CACHE = "[^"]*";/, `const CACHE = "ccez-keeps-${stamp}";`)
  .replace("/main.js", `/${jsOut}`)
  .replace("/styles.css", `/${cssOut}`);
if (!stamped.includes(`/${jsOut}`) || !stamped.includes(`/${cssOut}`)) {
  throw new Error("build: APP_SHELL bundle target missing in web/sw.js");
}
if (stamped === sw) throw new Error("build: CACHE stamp target missing in web/sw.js");
await Bun.write("dist/sw.js", stamped);
console.log(`build ok -> dist/ (${jsOut}, ${cssOut})`);
