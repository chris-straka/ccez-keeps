//! Headless proof: `KEEPS_SNAPSHOT=/path/shot.png` makes the app write a
//! PNG of its own page after `KEEPS_SNAPSHOT_DELAY` seconds (default 10),
//! then quit if `KEEPS_SNAPSHOT_QUIT` is set. WKWebView renders the image
//! itself, so it works with no Screen Recording permission and with the
//! display asleep (fleet jobs on the Mac mini have neither).
use std::time::Duration;
use tauri::{AppHandle, Manager};

pub fn schedule(app: &AppHandle) {
    let Some(path) = std::env::var_os("KEEPS_SNAPSHOT") else {
        return;
    };
    let delay = std::env::var("KEEPS_SNAPSHOT_DELAY")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(10);
    let quit = std::env::var_os("KEEPS_SNAPSHOT_QUIT").is_some();
    let app = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_secs(delay));
        let Some(window) = app.get_webview_window("main") else {
            return;
        };
        let path = std::path::PathBuf::from(path);
        let done = app.clone();
        let _ = window.with_webview(move |webview| {
            #[cfg(target_os = "macos")]
            unsafe {
                take(webview.inner().cast(), path, move || {
                    if quit {
                        done.exit(0);
                    }
                });
            }
            #[cfg(not(target_os = "macos"))]
            {
                let _ = (webview, path, done, quit);
                eprintln!("[keeps] snapshots are macOS-only");
            }
        });
    });
}

#[cfg(target_os = "macos")]
unsafe fn take(
    webview: *mut objc2_web_kit::WKWebView,
    path: std::path::PathBuf,
    then: impl Fn() + 'static,
) {
    use block2::RcBlock;
    use objc2_app_kit::{NSBitmapImageFileType, NSBitmapImageRep, NSImage};
    use objc2_foundation::{NSDictionary, NSError};

    let Some(webview) = webview.as_ref() else { return };
    let handler = RcBlock::new(move |image: *mut NSImage, error: *mut NSError| {
        let written = (|| -> Result<(), String> {
            if let Some(error) = error.as_ref() {
                return Err(error.localizedDescription().to_string());
            }
            let image = image.as_ref().ok_or("no image")?;
            let tiff = image.TIFFRepresentation().ok_or("no TIFF")?;
            let rep = NSBitmapImageRep::imageRepWithData(&tiff).ok_or("no bitmap")?;
            let png = rep
                .representationUsingType_properties(NSBitmapImageFileType::PNG, &NSDictionary::new())
                .ok_or("no PNG")?;
            std::fs::write(&path, png.to_vec()).map_err(|e| e.to_string())
        })();
        match written {
            Ok(()) => eprintln!("[keeps] snapshot written to {}", path.display()),
            Err(e) => eprintln!("[keeps] snapshot failed: {e}"),
        }
        then();
    });
    webview.takeSnapshotWithConfiguration_completionHandler(None, &handler);
}
