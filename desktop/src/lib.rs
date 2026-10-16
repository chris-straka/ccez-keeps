//! Keeps as its own Mac app (owner, 2026-10-14: closing the browser closed
//! the PWA too). The window loads the same web build as keeps.ccez.uk,
//! bundled, so it opens and edits offline; IndexedDB persists in the
//! app's own WebKit store. Sync goes through Rust with a device token
//! (api.rs, auth.rs), so no browser has to stay open, or be open at all
//! after sign-in. Closing the window keeps the app in the Dock, like
//! other Mac apps; Cmd+Q quits.
mod api;
mod auth;
mod snapshot;
mod update;

use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::{AppHandle, Manager, RunEvent, WindowEvent};

const MENU_SIGN_IN: &str = "keeps.sign-in";
const MENU_SIGN_OUT: &str = "keeps.sign-out";
const MENU_UPDATES: &str = "keeps.updates";

/// The standard Mac menu (Edit gives the page copy, paste and undo), with
/// the account and update items in the app menu under About.
fn build_menu(app: &AppHandle) -> tauri::Result<Menu<tauri::Wry>> {
    let menu = Menu::default(app)?;
    let items = menu.items()?;
    if let Some(app_menu) = items.first().and_then(|item| item.as_submenu()) {
        let sign_in = MenuItem::with_id(app, MENU_SIGN_IN, "Sign In with Browser…", true, None::<&str>)?;
        let sign_out = MenuItem::with_id(app, MENU_SIGN_OUT, "Sign Out", true, None::<&str>)?;
        let updates = MenuItem::with_id(app, MENU_UPDATES, "Check for Updates", true, None::<&str>)?;
        let separator = PredefinedMenuItem::separator(app)?;
        app_menu.insert_items(&[&updates, &separator, &sign_in, &sign_out], 1)?;
    }
    Ok(menu)
}

fn on_menu(app: &AppHandle, id: &str) {
    match id {
        MENU_SIGN_IN => {
            if let Err(e) = auth::start_sign_in(app) {
                eprintln!("[keeps] couldn't open the browser: {e}");
            }
        }
        MENU_SIGN_OUT => {
            let app = app.clone();
            tauri::async_runtime::spawn(async move { auth::sign_out(&app).await });
        }
        MENU_UPDATES => update::check_now(app),
        _ => {}
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        // keeps:// (sign-in callback): the bundler registers the scheme
        // from plugins.deep-link in tauri.conf.json.
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        // Size, position and maximized state survive relaunches. The
        // window starts hidden and setup shows it, so no restored launch
        // flashes at the default size first; visibility is not restored.
        .plugin(
            tauri_plugin_window_state::Builder::new()
                .with_state_flags(
                    tauri_plugin_window_state::StateFlags::all()
                        - tauri_plugin_window_state::StateFlags::VISIBLE,
                )
                .build(),
        )
        .invoke_handler(tauri::generate_handler![
            api::api_fetch,
            auth::sign_in,
            auth::sign_out_command,
            auth::account,
        ])
        .menu(build_menu)
        .on_menu_event(|app, event| on_menu(app, event.id().as_ref()))
        .on_window_event(|window, event| {
            // Close hides: the app (and its sync) keeps running in the
            // Dock, and clicking the Dock icon brings the window back.
            if let WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == "main" {
                    api.prevent_close();
                    let _ = window.hide();
                    update::on_window_hidden(window.app_handle());
                }
            }
        })
        .setup(|app| {
            let handle = app.handle().clone();
            {
                use tauri_plugin_deep_link::DeepLinkExt;
                let links = handle.clone();
                app.deep_link().on_open_url(move |event| {
                    for url in event.urls() {
                        auth::handle_link(&links, url.as_str());
                    }
                });
                // A link that launched the app (it wasn't running).
                if let Ok(Some(urls)) = app.deep_link().get_current() {
                    for url in urls {
                        auth::handle_link(&handle, url.as_str());
                    }
                }
            }
            if std::env::var_os(update::START_HIDDEN_ENV).is_some() {
                std::env::remove_var(update::START_HIDDEN_ENV);
            } else {
                auth::show_main(&handle);
            }
            update::spawn(&handle);
            snapshot::schedule(&handle);
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building Keeps");

    app.run(|app, event| {
        // Dock icon clicked with no window showing.
        #[cfg(target_os = "macos")]
        if let RunEvent::Reopen { .. } = event {
            auth::show_main(app);
        }
        #[cfg(not(target_os = "macos"))]
        let _ = (app, event);
    });
}
