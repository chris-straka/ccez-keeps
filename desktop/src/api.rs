//! The page's `/api/*` calls, made from Rust. web/desktop.ts swaps
//! `fetch` for this command, so the web app's sync code runs unchanged:
//! same paths, same JSON, same statuses. Rust adds the device token, so
//! the page never holds it, and the request is not a cross-origin one
//! (the page is `tauri://localhost`), so the Worker needs no CORS.
use serde::Serialize;
use std::sync::OnceLock;
use std::time::Duration;
use tauri::AppHandle;

pub fn client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .user_agent(concat!("keeps-desktop/", env!("CARGO_PKG_VERSION")))
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(60))
            .build()
            .expect("http client")
    })
}

#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ApiReply {
    pub status: u16,
    pub body: String,
    pub content_type: String,
}

/// Only the Worker's API is reachable through here, never another host.
fn checked_path(path: &str) -> Result<&str, String> {
    if path.starts_with("/api/") && !path.contains("://") && !path.contains("..") {
        Ok(path)
    } else {
        Err(format!("not an API path: {path}"))
    }
}

/// Signed out: answer like the Worker would, without a round trip, so
/// the sync engine shows its sign-in banner.
fn signed_out() -> ApiReply {
    ApiReply {
        status: 401,
        body: r#"{"error":"not signed in"}"#.to_string(),
        content_type: "application/json".to_string(),
    }
}

/// Err means the request never got an answer (offline, DNS, timeout):
/// the page turns it into fetch's TypeError, as a browser would.
#[tauri::command]
pub async fn api_fetch(
    app: AppHandle,
    method: String,
    path: String,
    body: Option<String>,
) -> Result<ApiReply, String> {
    let path = checked_path(&path)?;
    let Some(cred) = crate::auth::load(&app) else {
        return Ok(signed_out());
    };
    let method = reqwest::Method::from_bytes(method.to_uppercase().as_bytes())
        .map_err(|e| e.to_string())?;
    let mut req = client()
        .request(method, format!("{}{}", crate::auth::base_url(), path))
        .bearer_auth(&cred.token);
    if let Some(body) = body {
        req = req
            .header(reqwest::header::CONTENT_TYPE, "application/json")
            .body(body);
    }
    let res = req.send().await.map_err(|e| format!("network: {e}"))?;
    let status = res.status().as_u16();
    let content_type = res
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("application/json")
        .to_string();
    let body = res.text().await.map_err(|e| format!("network: {e}"))?;
    Ok(ApiReply {
        status,
        body,
        content_type,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_api_paths_pass() {
        assert!(checked_path("/api/notes?since=0").is_ok());
        assert!(checked_path("/api/devices/revoke").is_ok());
        assert!(checked_path("/enroll").is_err());
        assert!(checked_path("api/notes").is_err());
        assert!(checked_path("/api/../enroll").is_err());
        assert!(checked_path("/api/x?u=https://evil").is_err());
    }

    #[test]
    fn signed_out_looks_like_the_worker() {
        let reply = signed_out();
        assert_eq!(reply.status, 401);
        assert!(reply.body.contains("not signed in"));
    }
}
