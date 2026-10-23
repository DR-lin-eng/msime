//! 用户主动选择的搜狗分类与词库订阅；输入线程不执行网络请求。

use super::collections::{CollectionSource, DictionaryCollectionsStore, MAX_COLLECTIONS};
use super::personal::PersonalDictionaryStore;
use super::scel::MAX_SCEL_BYTES;
use crate::file_lock::{self, PrivateDirectory, ScopedFileLock};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

pub const GROUPS: &[(u32, &str)] = &[
    (97, "计算机"),
    (132, "医学医药"),
    (1, "自然科学"),
    (31, "人文科学"),
    (76, "社会科学"),
    (96, "工程技术"),
    (127, "农林渔畜"),
    (167, "城市地名"),
    (436, "电子游戏"),
    (154, "艺术设计"),
    (367, "运动休闲"),
    (389, "生活"),
    (403, "娱乐"),
];
const MAX_HTML_BYTES: usize = 2 * 1024 * 1024;
const DAY: u64 = 24 * 60 * 60;

#[derive(Debug, thiserror::Error)]
#[error("{0}")]
pub struct Error(pub &'static str);
type Result<T> = std::result::Result<T, Error>;
fn storage(_: impl std::fmt::Display) -> Error {
    Error("sogou_storage")
}
fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(tag = "command", rename_all = "snake_case", deny_unknown_fields)]
pub enum Action {
    Status,
    Catalog {
        group: u32,
        page: u32,
    },
    Configure {
        groups: Vec<u32>,
        dictionaries: Vec<u32>,
        automatic: bool,
    },
    Install {
        id: u32,
    },
    SetEnabled {
        id: u32,
        enabled: bool,
    },
    Remove {
        id: u32,
    },
    Check,
    Automatic,
    Synchronize,
    Pending,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(default, deny_unknown_fields)]
pub struct Settings {
    pub groups: Vec<u32>,
    pub dictionaries: Vec<u32>,
    pub automatic: bool,
    pub excluded: Vec<u32>,
    pub revision: u64,
    pub checked_at: u64,
    pub phase: String,
    pub error: Option<String>,
    pub limited: bool,
}
impl Default for Settings {
    fn default() -> Self {
        Self {
            groups: vec![],
            dictionaries: vec![],
            automatic: true,
            excluded: vec![],
            revision: 0,
            checked_at: 0,
            phase: "idle".into(),
            error: None,
            limited: false,
        }
    }
}
#[derive(Clone, Debug, Serialize)]
pub struct Group {
    pub id: u32,
    pub name: String,
}
#[derive(Clone, Debug, Serialize)]
pub struct Entry {
    pub id: u32,
    pub name: String,
}
#[derive(Clone, Debug, Serialize)]
pub struct Catalog {
    pub entries: Vec<Entry>,
    pub has_more: bool,
    pub group: u32,
    pub page: u32,
}
#[derive(Clone, Debug, Serialize)]
pub struct Installed {
    pub id: u32,
    pub name: String,
    pub enabled: bool,
    pub entries: usize,
}
#[derive(Clone, Debug, Serialize)]
pub struct Status {
    pub settings: Settings,
    pub groups: Vec<Group>,
    pub installed: Vec<Installed>,
    pub max_dictionaries: usize,
    pub pending_count: usize,
}

#[derive(Clone)]
pub struct Store {
    root: PathBuf,
}
impl Store {
    pub fn new(root: impl AsRef<Path>) -> Self {
        Self {
            root: root.as_ref().to_owned(),
        }
    }
    fn directory(&self) -> Result<PrivateDirectory> {
        let root = file_lock::open_private_directory(&self.root).map_err(storage)?;
        match file_lock::create_private_directory_at(&root, "SogouDictionaries".as_ref()) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(error) => return Err(storage(error)),
        }
        file_lock::open_private_directory_at(&root, "SogouDictionaries".as_ref()).map_err(storage)
    }
    fn locked(&self) -> Result<(PrivateDirectory, ScopedFileLock)> {
        let directory = self.directory()?;
        let file = file_lock::open_private_lock_file_at(&directory, "settings.lock".as_ref())
            .map_err(storage)?;
        let lock = ScopedFileLock::exclusive(file).map_err(storage)?;
        Ok((directory, lock))
    }
    fn read(directory: &PrivateDirectory) -> Result<Settings> {
        let file = match file_lock::open_private_file_at(directory, "settings.json".as_ref()) {
            Ok(file) => file,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Settings::default()),
            Err(e) => return Err(storage(e)),
        };
        let mut bytes = Vec::new();
        file.take(65537).read_to_end(&mut bytes).map_err(storage)?;
        if bytes.len() > 65536 {
            return Err(Error("sogou_corrupt"));
        }
        let value: Settings = serde_json::from_slice(&bytes).map_err(|_| Error("sogou_corrupt"))?;
        Self::validate(&value)?;
        Ok(value)
    }
    fn validate(value: &Settings) -> Result<()> {
        if value.groups.len() > 8
            || value.dictionaries.len() > MAX_COLLECTIONS
            || value.excluded.len() > 256
            || value
                .groups
                .iter()
                .any(|id| !GROUPS.iter().any(|(known, _)| known == id))
            || value
                .dictionaries
                .iter()
                .chain(&value.excluded)
                .any(|id| *id == 0)
        {
            return Err(Error("sogou_invalid"));
        }
        Ok(())
    }
    fn write(directory: &PrivateDirectory, value: &Settings) -> Result<()> {
        let bytes = serde_json::to_vec(value).map_err(storage)?;
        file_lock::write_private_file_at(directory, "settings.json".as_ref(), &bytes)
            .map_err(storage)
    }
    fn collections(&self) -> DictionaryCollectionsStore {
        DictionaryCollectionsStore::new(
            &self.root,
            PersonalDictionaryStore::new(self.root.join("PersonalDictionary")),
        )
    }
    fn view(&self, settings: Settings) -> Result<Status> {
        let metadata = self.collections().metadata().map_err(storage)?;
        let pending_count = self.pending_count()?;
        let installed = metadata
            .into_iter()
            .filter_map(|item| {
                if let CollectionSource::Sogou { dictionary_id, .. } = item.source {
                    Some(Installed {
                        id: dictionary_id,
                        name: item.name,
                        enabled: item.enabled,
                        entries: item.entry_count,
                    })
                } else {
                    None
                }
            })
            .collect();
        Ok(Status {
            settings,
            installed,
            pending_count,
            max_dictionaries: MAX_COLLECTIONS,
            groups: GROUPS
                .iter()
                .map(|(id, name)| Group {
                    id: *id,
                    name: (*name).into(),
                })
                .collect(),
        })
    }
    pub fn pending_count(&self) -> Result<usize> {
        self.collections().pending_count().map_err(storage)
    }
    pub fn status(&self) -> Result<Status> {
        let (directory, _lock) = self.locked()?;
        self.view(Self::read(&directory)?)
    }
    pub fn configure(
        &self,
        groups: Vec<u32>,
        dictionaries: Vec<u32>,
        automatic: bool,
    ) -> Result<Status> {
        let (directory, _lock) = self.locked()?;
        let mut settings = Self::read(&directory)?;
        settings.groups = groups
            .into_iter()
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect();
        settings.dictionaries = dictionaries
            .into_iter()
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect();
        settings.automatic = automatic;
        Self::validate(&settings)?;
        settings
            .excluded
            .retain(|id| !settings.dictionaries.contains(id));
        settings.revision = settings
            .revision
            .checked_add(1)
            .ok_or(Error("sogou_invalid"))?;
        settings.checked_at = 0;
        settings.phase = "idle".into();
        settings.error = None;
        Self::write(&directory, &settings)?;
        self.view(settings)
    }
    pub fn install(&self, id: u32) -> Result<Status> {
        if id == 0 {
            return Err(Error("sogou_invalid"));
        }
        let (directory, _lock) = self.locked()?;
        let mut settings = Self::read(&directory)?;
        if !settings.dictionaries.contains(&id) {
            settings.dictionaries.push(id);
        }
        settings.excluded.retain(|existing| *existing != id);
        Self::validate(&settings)?;
        settings.revision = settings
            .revision
            .checked_add(1)
            .ok_or(Error("sogou_invalid"))?;
        settings.checked_at = 0;
        Self::write(&directory, &settings)?;
        self.view(settings)
    }
    pub fn set_enabled(&self, id: u32, enabled: bool) -> Result<Status> {
        let (directory, _lock) = self.locked()?;
        let collections = self.collections();
        let item = collections.load().map_err(storage)?.collections.into_iter().find(|item|
            matches!(item.source, CollectionSource::Sogou { dictionary_id, .. } if dictionary_id == id))
            .ok_or(Error("sogou_not_found"))?;
        collections
            .set_enabled(&item.id.to_string(), enabled)
            .map_err(storage)?;
        self.view(Self::read(&directory)?)
    }
    pub fn remove(&self, id: u32) -> Result<Status> {
        let (directory, _lock) = self.locked()?;
        let mut settings = Self::read(&directory)?;
        if id == 0 || settings.excluded.len() >= 256 {
            return Err(Error("sogou_invalid"));
        }
        settings.dictionaries.retain(|existing| *existing != id);
        if !settings.excluded.contains(&id) {
            settings.excluded.push(id);
        }
        settings.revision = settings
            .revision
            .checked_add(1)
            .ok_or(Error("sogou_invalid"))?;
        Self::write(&directory, &settings)?;
        let collections = self.collections();
        if let Some(item) = collections.load().map_err(storage)?.collections.into_iter().find(|item|
            matches!(item.source, CollectionSource::Sogou { dictionary_id, .. } if dictionary_id == id)) {
            collections.delete(&item.id.to_string()).map_err(storage)?;
        }
        self.view(settings)
    }
    pub fn due(&self) -> Result<bool> {
        let status = self.status()?;
        let settings = status.settings;
        Ok(settings.automatic
            && (!settings.groups.is_empty()
                || !settings.dictionaries.is_empty()
                || !status.installed.is_empty())
            && now().saturating_sub(settings.checked_at) >= DAY)
    }
    pub fn check(&self, remote: &impl Source, automatic: bool) -> Result<Status> {
        let directory = self.directory()?;
        let job = file_lock::open_private_lock_file_at(&directory, "download.lock".as_ref())
            .map_err(storage)?;
        let _job = ScopedFileLock::exclusive(job).map_err(storage)?;
        if automatic && !self.due()? {
            return self.status();
        }
        let initial = {
            let (directory, _lock) = self.locked()?;
            let mut settings = Self::read(&directory)?;
            settings.checked_at = now();
            settings.phase = "checking".into();
            settings.error = None;
            settings.limited = false;
            Self::write(&directory, &settings)?;
            settings
        };
        let outcome = self.download(remote, &initial);
        let (directory, _lock) = self.locked()?;
        let mut settings = Self::read(&directory)?;
        if settings.revision == initial.revision {
            settings.phase = "current".into();
            if let Err(error) = outcome {
                settings.error = Some(error.to_string());
                settings.phase = "failed".into();
            }
            Self::write(&directory, &settings)?;
        }
        self.view(settings)
    }
    fn download(&self, remote: &impl Source, settings: &Settings) -> Result<()> {
        let mut wanted: BTreeSet<u32> = settings.dictionaries.iter().copied().collect();
        // 分组只跟踪最新上传的十项；已安装的老词库继续检查，避免每次抓取全站。
        for group in &settings.groups {
            let page = catalog(remote, *group, 1)?;
            wanted.extend(page.entries.into_iter().map(|entry| entry.id));
        }
        let collections = self.collections();
        let installed = collections.load().map_err(storage)?;
        wanted.extend(installed.collections.iter().filter_map(|item| {
            if let CollectionSource::Sogou { dictionary_id, .. } = item.source {
                Some(dictionary_id)
            } else {
                None
            }
        }));
        wanted.retain(|id| !settings.excluded.contains(id));
        let mut failure = None;
        for id in wanted {
            // 配置可能在网络请求期间被修改；提交集合前再次检查同一代次。
            if self.status()?.settings.revision != settings.revision {
                return Ok(());
            }
            let result = (|| {
                let bytes = remote.dictionary(id)?;
                let digest = hex::encode(Sha256::digest(&bytes));
                let (directory, _lock) = self.locked()?;
                let mut current = Self::read(&directory)?;
                if current.revision != settings.revision {
                    return Ok(());
                }
                let view = collections.load().map_err(storage)?;
                let existing = view.collections.iter().find(|item|
                    matches!(item.source, CollectionSource::Sogou { dictionary_id, .. } if dictionary_id == id));
                if existing.is_none() && view.collections.len() >= MAX_COLLECTIONS {
                    current.limited = true;
                    Self::write(&directory, &current)?;
                    return Err(Error("sogou_limit"));
                }
                if existing.is_some_and(|item| matches!(&item.source, CollectionSource::Sogou { sha256, .. } if sha256 == &digest)) { return Ok(()); }
                collections
                    .install_sogou(id, &bytes)
                    .map_err(|_| Error("sogou_invalid_dictionary"))?;
                Ok(())
            })();
            if let Err(error) = result {
                failure = Some(error);
            }
        }
        failure.map_or(Ok(()), Err)
    }
}

/// 测试可以注入合成数据；生产端点与重定向的来源固定，不接受宿主传来的任意 URL。
pub trait Source {
    fn page(&self, group: u32, page: u32) -> Result<String>;
    fn dictionary(&self, id: u32) -> Result<Vec<u8>>;
}
pub struct Official {
    client: reqwest::blocking::Client,
}
impl Official {
    pub fn new() -> Result<Self> {
        let client = reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(30))
            .connect_timeout(Duration::from_secs(10))
            .user_agent("MSIME dictionary subscriptions")
            .redirect(reqwest::redirect::Policy::custom(|attempt| {
                if attempt.previous().len() < 4 && trusted(attempt.url()) {
                    attempt.follow()
                } else {
                    attempt.error("sogou_origin")
                }
            }))
            .build()
            .map_err(|_| Error("sogou_network"))?;
        Ok(Self { client })
    }
    fn get(&self, url: reqwest::Url, limit: usize) -> Result<Vec<u8>> {
        if !trusted(&url) {
            return Err(Error("sogou_origin"));
        }
        let response = self
            .client
            .get(url)
            .send()
            .and_then(|r| r.error_for_status())
            .map_err(|_| Error("sogou_network"))?;
        if response
            .content_length()
            .is_some_and(|size| size > limit as u64)
        {
            return Err(Error("sogou_too_large"));
        }
        let mut bytes = Vec::new();
        response
            .take(limit as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| Error("sogou_network"))?;
        if bytes.len() > limit {
            return Err(Error("sogou_too_large"));
        }
        Ok(bytes)
    }
}
fn trusted(url: &reqwest::Url) -> bool {
    url.scheme() == "https"
        && url.username().is_empty()
        && url.password().is_none()
        && url.port().is_none()
        && matches!(
            url.host_str(),
            Some("pinyin.sogou.com" | "download.pinyin.sogou.com")
        )
}
impl Source for Official {
    fn page(&self, group: u32, page: u32) -> Result<String> {
        let url = format!("https://pinyin.sogou.com/dict/cate/index/{group}/updatetime/{page}")
            .parse()
            .map_err(|_| Error("sogou_invalid"))?;
        String::from_utf8(self.get(url, MAX_HTML_BYTES)?)
            .map_err(|_| Error("sogou_catalog_changed"))
    }
    fn dictionary(&self, id: u32) -> Result<Vec<u8>> {
        if id == 0 {
            return Err(Error("sogou_invalid"));
        }
        let url = format!("https://pinyin.sogou.com/d/dict/download_cell.php?id={id}&name=msime")
            .parse()
            .map_err(|_| Error("sogou_invalid"))?;
        self.get(url, MAX_SCEL_BYTES)
    }
}
pub fn catalog(source: &impl Source, group: u32, page: u32) -> Result<Catalog> {
    if !GROUPS.iter().any(|(id, _)| *id == group) || !(1..=1000).contains(&page) {
        return Err(Error("sogou_invalid"));
    }
    parse_catalog(&source.page(group, page)?, group, page)
}
fn parse_catalog(html: &str, group: u32, page: u32) -> Result<Catalog> {
    if html.len() > MAX_HTML_BYTES {
        return Err(Error("sogou_too_large"));
    }
    let mut entries = BTreeMap::new();
    let mut has_more = false;
    let mut tail = html;
    while let Some(start) = tail.find("<a ") {
        tail = tail
            .get(start + 3..)
            .ok_or(Error("sogou_catalog_changed"))?;
        let Some((tag, rest)) = tail.split_once('>') else {
            break;
        };
        let Some((label, rest)) = rest.split_once("</a>") else {
            break;
        };
        tail = rest;
        let Some((_, value)) = tag.split_once("href=") else {
            continue;
        };
        let Some(quote) = value.chars().next().filter(|c| *c == '\'' || *c == '"') else {
            continue;
        };
        let Some(href) = value
            .get(1..)
            .and_then(|s| s.split_once(quote))
            .map(|(v, _)| v)
        else {
            continue;
        };
        let href = href.replace("&amp;", "&");
        has_more |= href == format!("/dict/cate/index/{group}/updatetime/{}", page + 1);
        if let Some(id) = href
            .strip_prefix("/dict/detail/index/")
            .and_then(|value| value.split(['/', '?', '#']).next())
            .and_then(|s| s.parse::<u32>().ok())
            .filter(|id| *id != 0)
        {
            let name: String = label
                .chars()
                .scan(false, |in_tag, c| {
                    if c == '<' {
                        *in_tag = true;
                    }
                    let keep = !*in_tag;
                    if c == '>' {
                        *in_tag = false;
                    }
                    Some(keep.then_some(c))
                })
                .flatten()
                .filter(|c| !c.is_control())
                .take(80)
                .collect();
            let name = name
                .trim()
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
            if !name.is_empty() {
                entries.insert(id, Entry { id, name });
            }
        }
    }
    if entries.is_empty() || entries.len() > 64 {
        return Err(Error("sogou_catalog_changed"));
    }
    Ok(Catalog {
        entries: entries.into_values().collect(),
        has_more,
        group,
        page,
    })
}

#[cfg(test)]
mod tests;
