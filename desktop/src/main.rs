// No console window on Windows release builds; the Mac app has none anyway.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    keeps_desktop_lib::run()
}
