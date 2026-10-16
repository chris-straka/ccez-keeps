//! Device-token sign-in, the same flow as the Android app (plan-android.md,
//! docs/access.md): the browser opens `/api/devices/code` once, Access logs
//! it in, the Worker 302s to `keeps://enroll?code=...`, and the app trades
//! the single-use code for its own device token at `/api/devices/exchange`.
//! The browser is never needed again; the token lives in a mode-600 file
//! in the app's config folder (same idea as the `keeps` CLI's token file),
//! never in the page.
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use tauri::{AppHandle, Emitter, Manager};

/// Production Worker. `KEEPS_BASE_URL` points a build at another one
/// (local `wrangler dev`, a test server).
pub const DEFAULT_BASE_URL: &str = "https://keeps.ccez.uk";

pub fn base_url() -> String {
    std::env::var("KEEPS_BASE_URL")
        .ok()
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| DEFAULT_BASE_URL.to_string())
        .trim_end_matches('/')
        .to_string()
}

/// What the app keeps about its enrollment. The token is the credential;
/// the id and name let Sign Out revoke this device server-side.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Credential {
    pub token: String,
    pub device_id: String,
    pub device_name: String,
}

/// Where the credential lives: `~/Library/Application Support/uk.ccez.keeps/device.json`.
fn credential_path(app: &AppHandle) -> Option<PathBuf> {
    app.path()
        .app_config_dir()
        .ok()
        .map(|dir| dir.join("device.json"))
}

pub fn load(app: &AppHandle) -> Option<Credential> {
    let text = std::fs::read_to_string(credential_path(app)?).ok()?;
    serde_json::from_str(&text).ok()
}

fn save(app: &AppHandle, cred: &Credential) -> Result<(), String> {
    let path = credential_path(app).ok_or("no config folder")?;
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let text = serde_json::to_string(cred).map_err(|e| e.to_string())?;
    write_private(&path, &text).map_err(|e| e.to_string())
}

/// Write a file only its owner can read: the token opens every note.
fn write_private(path: &std::path::Path, text: &str) -> std::io::Result<()> {
    use std::io::Write;
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(path)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        file.set_permissions(std::fs::Permissions::from_mode(0o600))?;
    }
    file.write_all(text.as_bytes())
}

fn forget(app: &AppHandle) {
    if let Some(path) = credential_path(app) {
        let _ = std::fs::remove_file(path);
    }
}

/// The name this Mac shows in the Devices panel, e.g. "Mac app on Chris's Mac mini".
pub fn device_name() -> String {
    let computer = std::process::Command::new("scutil")
        .args(["--get", "ComputerName"])
        .output()
        .ok()
        .filter(|out| out.status.success())
        .map(|out| String::from_utf8_lossy(&out.stdout).trim().to_string())
        .filter(|name| !name.is_empty());
    let name = match computer {
        Some(c) => format!("Mac app on {c}"),
        None => "Mac app".to_string(),
    };
    // The Worker caps names at 120 chars.
    name.chars().take(120).collect()
}

/// The browser page that starts enrollment; it ends at `keeps://enroll`.
pub fn sign_in_url(base: &str) -> String {
    let mut url = url::Url::parse(&format!("{base}/api/devices/code")).expect("base url");
    url.query_pairs_mut().append_pair("to", "keeps://enroll");
    url.to_string()
}

/// The single-use code out of `keeps://enroll?code=...`, if that is what
/// the link is. Anything else (another host, no code) is not ours.
pub fn enroll_code(link: &str) -> Option<String> {
    let url = url::Url::parse(link).ok()?;
    if url.scheme() != "keeps" || url.host_str() != Some("enroll") {
        return None;
    }
    url.query_pairs()
        .find(|(k, _)| k == "code")
        .map(|(_, v)| v.into_owned())
        .filter(|v| !v.is_empty())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ExchangeReply {
    device_id: String,
    token: String,
}

async fn exchange(code: &str, device_name: &str) -> Result<Credential, String> {
    let res = crate::api::client()
        .post(format!("{}/api/devices/exchange", base_url()))
        .json(&serde_json::json!({ "code": code, "deviceName": device_name }))
        .send()
        .await
        .map_err(|e| format!("network: {e}"))?;
    let status = res.status();
    if !status.is_success() {
        let text = res.text().await.unwrap_or_default();
        return Err(match status.as_u16() {
            410 => "That sign-in link expired. Try again.".to_string(),
            404 => "That sign-in link was already used. Try again.".to_string(),
            _ => format!("Sign-in failed (HTTP {status}): {text}"),
        });
    }
    let reply: ExchangeReply = res.json().await.map_err(|e| e.to_string())?;
    Ok(Credential {
        token: reply.token,
        device_id: reply.device_id,
        device_name: device_name.to_string(),
    })
}

/// Bring the window forward (it may be hidden: closing only hides it).
pub fn show_main(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

/// A `keeps://` link arrived (the browser finished sign-in, or someone
/// opened one by hand). Enroll links sign this Mac in; the page hears
/// `keeps:signed-in` and syncs at once.
pub fn handle_link(app: &AppHandle, link: &str) {
    show_main(app);
    let Some(code) = enroll_code(link) else { return };
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        match exchange(&code, &device_name()).await {
            Ok(cred) => match save(&app, &cred) {
                Ok(()) => {
                    eprintln!("[keeps] signed in as {}", cred.device_name);
                    let _ = app.emit("keeps:signed-in", account_of(Some(&cred)));
                }
                Err(e) => {
                    let _ = app.emit("keeps:sign-in-failed", format!("Couldn't save sign-in: {e}"));
                }
            },
            Err(e) => {
                eprintln!("[keeps] sign-in failed: {e}");
                let _ = app.emit("keeps:sign-in-failed", e);
            }
        }
    });
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Account {
    signed_in: bool,
    device_name: Option<String>,
}

fn account_of(cred: Option<&Credential>) -> Account {
    Account {
        signed_in: cred.is_some(),
        device_name: cred.map(|c| c.device_name.clone()),
    }
}

/// Open the browser at the enrollment page.
pub fn start_sign_in(app: &AppHandle) -> Result<(), String> {
    use tauri_plugin_opener::OpenerExt;
    app.opener()
        .open_url(sign_in_url(&base_url()), None::<&str>)
        .map_err(|e| e.to_string())
}

/// Revoke this device (best effort: offline still signs out locally),
/// then drop the token. Notes stay in the local store.
pub async fn sign_out(app: &AppHandle) {
    if let Some(cred) = load(app) {
        let _ = crate::api::client()
            .post(format!("{}/api/devices/revoke", base_url()))
            .bearer_auth(&cred.token)
            .json(&serde_json::json!({ "deviceId": cred.device_id }))
            .send()
            .await;
    }
    forget(app);
    let _ = app.emit("keeps:signed-out", account_of(None));
}

#[tauri::command]
pub fn sign_in(app: AppHandle) -> Result<(), String> {
    start_sign_in(&app)
}

#[tauri::command(rename = "sign_out")]
pub async fn sign_out_command(app: AppHandle) -> Result<(), String> {
    sign_out(&app).await;
    Ok(())
}

#[tauri::command]
pub fn account(app: AppHandle) -> Account {
    account_of(load(&app).as_ref())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sign_in_url_targets_the_enroll_callback() {
        assert_eq!(
            sign_in_url("https://keeps.ccez.uk"),
            "https://keeps.ccez.uk/api/devices/code?to=keeps%3A%2F%2Fenroll"
        );
    }

    #[test]
    fn enroll_code_reads_only_enroll_links() {
        assert_eq!(enroll_code("keeps://enroll?code=abc_123-x").as_deref(), Some("abc_123-x"));
        assert_eq!(enroll_code("keeps://enroll?x=1&code=a%2Bb").as_deref(), Some("a+b"));
        assert_eq!(enroll_code("keeps://enroll"), None);
        assert_eq!(enroll_code("keeps://enroll?code="), None);
        assert_eq!(enroll_code("keeps://other?code=abc"), None);
        assert_eq!(enroll_code("https://enroll?code=abc"), None);
        assert_eq!(enroll_code("not a url"), None);
    }

    #[test]
    fn device_name_fits_the_worker_cap() {
        let name = device_name();
        assert!(name.starts_with("Mac app"));
        assert!(name.chars().count() <= 120);
    }

    #[test]
    fn private_file_is_owner_only() {
        let dir = std::env::temp_dir().join(format!("keeps-auth-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("device.json");
        write_private(&path, "{}").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&path).unwrap().permissions().mode();
            assert_eq!(mode & 0o777, 0o600);
        }
        std::fs::remove_dir_all(dir).unwrap();
    }
}
