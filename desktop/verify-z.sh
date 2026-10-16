#!/bin/zsh
# Install Keeps.app on z and prove it: signed-out first launch, sign-in
# through the real keeps://enroll deep link + exchange, notes shown,
# quitting a browser leaves it running, and offline launch still shows notes.
export PATH="$HOME/.cargo/bin:$HOME/.bun/bin:$HOME/.local/bin:/opt/homebrew/bin:$PATH"
A="$FLEET_ARTIFACTS"; W="${TMPDIR:-/tmp}/keeps-verify-$$"; mkdir -p "$W/in" "$W/x"
log() { echo "== $*" | tee -a "$A/verify.txt"; }
# Usage: verify-z.sh /path/to/Keeps.app.tar.gz (the updater tarball;
# fleet jobs on z get it with `tailscale file get`).
TARBALL="${1:?Keeps.app.tar.gz path}"
[ -f "$TARBALL" ] || { log "no $TARBALL"; exit 1; }
tar -xzf "$TARBALL" -C "$W/x" || exit 1
pkill -f /Applications/Keeps.app/Contents/MacOS/ 2>/dev/null; sleep 1
rm -rf /Applications/Keeps.app && cp -R "$W/x/Keeps.app" /Applications/ || exit 1
xattr -dr com.apple.quarantine /Applications/Keeps.app 2>/dev/null
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f /Applications/Keeps.app
log "installed $(plutil -extract CFBundleShortVersionString raw /Applications/Keeps.app/Contents/Info.plist)"
BIN=/Applications/Keeps.app/Contents/MacOS/keeps-desktop
CONF="$HOME/Library/Application Support/uk.ccez.keeps"

if [ -n "$FRESH" ]; then rm -f "$CONF/device.json"; fi
if [ ! -f "$CONF/device.json" ]; then
  log "A: first launch, signed out"
  KEEPS_NO_UPDATE=1 KEEPS_SNAPSHOT="$A/1-signed-out.png" KEEPS_SNAPSHOT_DELAY=8 KEEPS_SNAPSHOT_QUIT=1 "$BIN" > "$A/run-a.log" 2>&1
  log "A exit $?"
fi

log "B: browser up, app up, sign in through keeps://enroll"
BRAVE_DIR="$W/brave"
"/Applications/Brave Browser.app/Contents/MacOS/Brave Browser" --user-data-dir="$BRAVE_DIR" --no-first-run --no-default-browser-check about:blank > /dev/null 2>&1 &
BRAVE=$!
sleep 4
KEEPS_NO_UPDATE=1 KEEPS_SNAPSHOT="$A/2-signed-in.png" KEEPS_SNAPSHOT_DELAY=${SNAP_DELAY:-25} "$BIN" > "$A/run-b.log" 2>&1 &
KEEPS=$!
sleep 5
if [ ! -f "$CONF/device.json" ]; then
  CODE=$(openssl rand -base64 24 | tr '+/' '-_' | tr -d '=')
  HASH=$(printf '%s' "$CODE" | shasum -a 256 | cut -d' ' -f1)
  NOW=$(( $(date +%s) * 1000 ))
  ( cd ~/SWE/ccez-keeps && wrangler d1 execute ccez-keeps --remote --command \
    "INSERT INTO enroll_codes (id, codeHash, createdAt, used) VALUES ('$(uuidgen | tr A-Z a-z)', '$HASH', $NOW, 0)" > "$W/d1.log" 2>&1 ) \
    && log "minted a single-use enroll code" || { log "D1 insert failed"; cat "$W/d1.log"; }
  open "keeps://enroll?code=$CODE"
  log "opened keeps://enroll"
else
  log "already signed in"
fi
sleep $(( ${SNAP_DELAY:-25} + 3 ))
ls -la "$CONF" | tee -a "$A/verify.txt"
stat -f '%Sp %N' "$CONF/device.json" 2>/dev/null | tee -a "$A/verify.txt"
log "quit the browser (pid $BRAVE)"
kill $BRAVE; sleep 4
pgrep -f "Brave Browser.*$BRAVE_DIR" >/dev/null && log "browser still up?" || log "browser gone"
kill -0 $KEEPS 2>/dev/null && log "Keeps still running after the browser quit (pid $KEEPS)" || log "Keeps NOT running"
ps -o pid,etime,command -p $KEEPS | tee -a "$A/verify.txt"
osascript -e 'quit app "Keeps"' 2>/dev/null || kill $KEEPS
sleep 3; kill -0 $KEEPS 2>/dev/null && kill $KEEPS
log "C: offline launch (API unreachable)"
KEEPS_BASE_URL=http://127.0.0.1:9 KEEPS_NO_UPDATE=1 KEEPS_SNAPSHOT="$A/3-offline.png" KEEPS_SNAPSHOT_DELAY=10 KEEPS_SNAPSHOT_QUIT=1 "$BIN" > "$A/run-c.log" 2>&1
log "C exit $?"
ls "$HOME/Library/Application Support/uk.ccez.keeps" "$HOME/Library/WebKit/uk.ccez.keeps" 2>&1 | head
cat "$CONF/.window-state.json" 2>/dev/null | head -c 400; echo
rm -rf "$W"
ls -la "$A"
