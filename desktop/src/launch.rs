//! Start at Login (macOS): the app manages its own login item as a
//! LaunchAgent plist — the same file that used to be installed by hand
//! (`uk.ccez.keeps.login`, opening the installed app hidden at login).
//! Off by default: no plist, no launch. The toggle lives in the app menu
//! (Keeps → Start at Login) and in the web Settings view; both go
//! through the two commands here so they can't disagree.
use std::path::{Path, PathBuf};
use tauri::{AppHandle, Manager};

/// App-menu id of the checkbox.
pub const MENU_ID: &str = "keeps.autostart";
/// Same label the hand-installed agent used, so the toggle adopts it.
const LABEL: &str = "uk.ccez.keeps.login";
/// What a login launch opens (hidden; update::START_HIDDEN_ENV).
const APP: &str = "/Applications/Keeps.app";

pub fn plist_path_in(home: &Path) -> PathBuf {
    home.join("Library/LaunchAgents")
        .join(format!("{LABEL}.plist"))
}

fn plist_path() -> Option<PathBuf> {
    std::env::var_os("HOME").map(|h| plist_path_in(Path::new(&h)))
}

/// The agent: open the installed app hidden at every login.
pub fn plist_xml(app: &str) -> String {
    format!(
        "\
<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">
<plist version=\"1.0\">
<dict>
  <key>Label</key><string>{LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/open</string><string>-g</string><string>-j</string>
    <string>--env</string><string>KEEPS_START_HIDDEN=1</string>
    <string>-a</string><string>{app}</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>LimitLoadToSessionType</key><string>Aqua</string>
</dict>
</plist>
"
    )
}

/// On iff the agent file exists (a fresh machine has none: off).
pub fn status_in(home: &Path) -> bool {
    plist_path_in(home).exists()
}

pub fn is_enabled() -> bool {
    std::env::var_os("HOME")
        .map(|h| status_in(Path::new(&h)))
        .unwrap_or(false)
}

fn uid() -> Result<String, String> {
    let out = std::process::Command::new("id")
        .arg("-u")
        .output()
        .map_err(|e| format!("couldn't read the user id: {e}"))?;
    if !out.status.success() {
        return Err("couldn't read the user id".to_string());
    }
    Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
}

fn launchctl(args: &[&str]) -> Result<(), String> {
    let out = std::process::Command::new("launchctl")
        .args(args)
        .output()
        .map_err(|e| format!("launchctl failed: {e}"))?;
    if out.status.success() {
        return Ok(());
    }
    let err = String::from_utf8_lossy(&out.stderr).trim().to_string();
    Err(if err.is_empty() {
        "launchctl failed".to_string()
    } else {
        err
    })
}

/// Write or remove the agent file; launchctl load/unload follows.
fn write_plist(path: &Path) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("couldn't create {}: {e}", parent.display()))?;
    }
    std::fs::write(path, plist_xml(APP))
        .map_err(|e| format!("couldn't write {}: {e}", path.display()))
}

fn remove_plist(path: &Path) -> Result<(), String> {
    match std::fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(format!("couldn't remove {}: {e}", path.display())),
    }
}

pub fn set_enabled(enabled: bool) -> Result<(), String> {
    let path = plist_path().ok_or("couldn't find the home directory")?;
    let id = uid()?;
    if enabled {
        if !Path::new(APP).exists() {
            return Err("Keeps.app isn't in /Applications, so it can't start at login".to_string());
        }
        write_plist(&path)?;
        // Bootout first: bootstrap over a loaded agent fails.
        let _ = launchctl(&["bootout", &format!("gui/{id}/{LABEL}")]);
        launchctl(&["bootstrap", &format!("gui/{id}"), &path.to_string_lossy()])?;
    } else {
        let _ = launchctl(&["bootout", &format!("gui/{id}/{LABEL}")]);
        remove_plist(&path)?;
    }
    Ok(())
}

/// The app-menu checkbox mirrors the same state after any toggle.
pub fn sync_menu(app: &AppHandle) {
    let enabled = is_enabled();
    if let Some(item) = app.menu().as_ref().and_then(|m| m.get(MENU_ID)) {
        if let Some(check) = item.as_check_menuitem() {
            let _ = check.set_checked(enabled);
        }
    }
}

#[tauri::command]
pub fn autostart_status() -> bool {
    is_enabled()
}

#[tauri::command]
pub fn autostart_set(app: AppHandle, enabled: bool) -> Result<(), String> {
    set_enabled(enabled)?;
    sync_menu(&app);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("keeps-launch-test-{}-{name}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn agent_opens_the_installed_app_hidden_at_login() {
        let xml = plist_xml(APP);
        assert!(
            xml.contains("<string>uk.ccez.keeps.login</string>"),
            "{xml}"
        );
        assert!(
            xml.contains("<string>KEEPS_START_HIDDEN=1</string>"),
            "{xml}"
        );
        assert!(
            xml.contains("<string>/Applications/Keeps.app</string>"),
            "{xml}"
        );
        assert!(xml.contains("<key>RunAtLoad</key><true/>"), "{xml}");
    }

    #[test]
    fn agent_lives_in_the_user_launch_agents() {
        assert_eq!(
            plist_path_in(Path::new("/Users/x")),
            PathBuf::from("/Users/x/Library/LaunchAgents/uk.ccez.keeps.login.plist")
        );
    }

    #[test]
    fn fresh_machine_starts_off() {
        assert!(!status_in(&tmp("off")));
    }

    #[test]
    fn plist_writes_and_removes() {
        let home = tmp("roundtrip");
        let path = plist_path_in(&home);
        assert!(!status_in(&home));
        write_plist(&path).unwrap();
        assert!(status_in(&home));
        assert!(std::fs::read_to_string(&path)
            .unwrap()
            .contains("RunAtLoad"));
        remove_plist(&path).unwrap();
        assert!(!status_in(&home));
        // Removing twice is fine.
        remove_plist(&path).unwrap();
    }
}
