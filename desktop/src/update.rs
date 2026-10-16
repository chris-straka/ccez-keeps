//! Self-update from GitHub releases (latest.json, signed with the Keeps
//! updater key; release.yml builds it). Checks at launch and every six
//! hours, installs in the background, and switches over when it won't
//! interrupt: right away if the window is closed, else the next time it
//! is. The relaunch stays in the Dock with its window closed.
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use tauri::{AppHandle, Manager};
use tauri_plugin_updater::UpdaterExt;

const EVERY: Duration = Duration::from_secs(6 * 60 * 60);

/// Set by a relaunch after an update so the new copy starts hidden.
pub const START_HIDDEN_ENV: &str = "KEEPS_START_HIDDEN";

static READY: AtomicBool = AtomicBool::new(false);

pub fn spawn(app: &AppHandle) {
    // Dev builds and verification runs never update themselves.
    if cfg!(debug_assertions) || std::env::var_os("KEEPS_NO_UPDATE").is_some() {
        return;
    }
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        loop {
            if let Err(e) = check_once(&app).await {
                eprintln!("[keeps] update check failed: {e}");
            }
            if READY.load(Ordering::SeqCst) {
                return;
            }
            tokio::time::sleep(EVERY).await;
        }
    });
}

/// Manual check (app menu). Same path as the background one.
pub fn check_now(app: &AppHandle) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        if let Err(e) = check_once(&app).await {
            eprintln!("[keeps] update check failed: {e}");
        }
    });
}

async fn check_once(app: &AppHandle) -> Result<(), String> {
    if READY.load(Ordering::SeqCst) {
        return Ok(());
    }
    let updater = app.updater().map_err(|e| e.to_string())?;
    let Some(update) = updater.check().await.map_err(|e| e.to_string())? else {
        return Ok(());
    };
    eprintln!("[keeps] installing {} (have {})", update.version, update.current_version);
    update
        .download_and_install(|_, _| {}, || {})
        .await
        .map_err(|e| e.to_string())?;
    READY.store(true, Ordering::SeqCst);
    let hidden = app
        .get_webview_window("main")
        .map(|w| !w.is_visible().unwrap_or(true))
        .unwrap_or(true);
    if hidden {
        relaunch_hidden(app);
    }
    Ok(())
}

/// The window just closed: a waiting update takes over now.
pub fn on_window_hidden(app: &AppHandle) {
    if READY.load(Ordering::SeqCst) {
        relaunch_hidden(app);
    }
}

fn relaunch_hidden(app: &AppHandle) {
    // The relaunched process inherits the environment.
    std::env::set_var(START_HIDDEN_ENV, "1");
    app.restart();
}
