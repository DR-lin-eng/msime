//! 所有宿主通过现有词库 JSON 请求访问同一份搜狗订阅。
use crate::HostOptions;
use msime_client_services::dictionary::sogou::{self, Action, Official, Store};
use serde_json::Value;
use std::path::Path;

pub(crate) fn request(options: &HostOptions, action: &Action) -> Result<Value, String> {
    let root = options
        .preferences_directory
        .as_deref()
        .filter(|p| Path::new(p).is_absolute())
        .ok_or("sogou_invalid")?;
    if matches!(action, Action::Synchronize) {
        return crate::dictionary::synchronize_collections_with_budget(
            options,
            std::time::Duration::from_millis(100),
        );
    }
    let store = Store::new(root);
    if matches!(action, Action::Pending) {
        return store
            .pending_count()
            .map(|count| serde_json::json!({"pending_count": count}))
            .map_err(|e| e.to_string());
    }
    let mut download = false;
    let mut automatic = false;
    let mut status = match action {
        Action::Synchronize | Action::Pending => unreachable!(),
        Action::Status => store.status(),
        Action::Catalog { group, page } => {
            return sogou::catalog(&Official::new().map_err(|e| e.to_string())?, *group, *page)
                .and_then(|v| serde_json::to_value(v).map_err(|_| sogou::Error("sogou_invalid")))
                .map_err(|e| e.to_string());
        }
        Action::Configure {
            groups,
            dictionaries,
            automatic,
        } => {
            let value = store.configure(groups.clone(), dictionaries.clone(), *automatic);
            download = *automatic && (!groups.is_empty() || !dictionaries.is_empty());
            value
        }
        Action::Install { id } => {
            download = true;
            store.install(*id)
        }
        Action::SetEnabled { id, enabled } => store.set_enabled(*id, *enabled),
        Action::Remove { id } => store.remove(*id),
        Action::Check => {
            download = true;
            store.status()
        }
        Action::Automatic => {
            automatic = true;
            download = store.due().map_err(|e| e.to_string())?;
            store.status()
        }
    }
    .map_err(|e| e.to_string())?;
    let activation_options = options.clone();
    if download {
        std::thread::Builder::new()
            .name("sogou-dictionaries".into())
            .spawn(move || {
                if let Ok(remote) = Official::new() {
                    let _ = store.check(&remote, automatic);
                }
                activate(&activation_options);
            })
            .map_err(|_| "sogou_storage")?;
        status.settings.phase = "checking".into();
    }
    if status.pending_count > 0 {
        activate(options);
    }
    serde_json::to_value(status).map_err(|_| "sogou_invalid".into())
}

/// 桌面沿用已有维护租约；繁忙时由下一次设置状态轮询重试，不打断组字。
fn activate(options: &HostOptions) {
    #[cfg(all(
        not(test),
        any(target_os = "linux", target_os = "macos", target_os = "windows")
    ))]
    {
        use std::collections::HashSet;
        use std::sync::{Mutex, OnceLock};
        static RUNNING: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();
        let Some(root) = options.preferences_directory.clone() else {
            return;
        };
        let tasks = RUNNING.get_or_init(Mutex::default);
        let Ok(mut running) = tasks.lock() else {
            return;
        };
        if !running.insert(root.clone()) {
            return;
        }
        let options = options.clone();
        let key = root.clone();
        if std::thread::Builder::new()
            .name("sogou-activate".into())
            .spawn(move || {
                if let Ok(document) = serde_json::to_value(options) {
                    for _ in 0..50 {
                        let action =
                            serde_json::json!({"operation":"sogou","command":"synchronize"});
                        match crate::settings_dictionary::request(&document, &action, || {}) {
                            Ok(result)
                                if result["pending_count"]
                                    .as_u64()
                                    .is_some_and(|count| count > 0) => {}
                            _ => break,
                        }
                    }
                }
                if let Ok(mut running) = tasks.lock() {
                    running.remove(&key);
                }
            })
            .is_err()
        {
            running.remove(&root);
        }
    }
    #[cfg(any(
        test,
        not(any(target_os = "linux", target_os = "macos", target_os = "windows"))
    ))]
    let _ = options;
}

#[cfg(test)]
mod tests;
