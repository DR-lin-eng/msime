use super::*;
use crate::dictionary::scel::tests::{group, ScelBuilder};
use std::cell::{Cell, RefCell};

struct Fake {
    bytes: RefCell<Vec<u8>>,
    calls: Cell<usize>,
    page: RefCell<String>,
}
impl Fake {
    fn new() -> Self {
        Self {
            bytes: RefCell::new(ScelBuilder::sample().build()),
            calls: Cell::new(0),
            page: RefCell::new("<a href=\"/dict/detail/index/101\">合成词库</a>".into()),
        }
    }
}
impl Source for Fake {
    fn page(&self, _: u32, _: u32) -> Result<String> {
        Ok(self.page.borrow().clone())
    }
    fn dictionary(&self, _: u32) -> Result<Vec<u8>> {
        self.calls.set(self.calls.get() + 1);
        Ok(self.bytes.borrow().clone())
    }
}
#[test]
fn sogou_default_is_offline_and_configuration_survives_restart() {
    let root = tempfile::tempdir().unwrap();
    let store = Store::new(root.path());
    let source = Fake::new();
    assert!(!store.due().unwrap());
    store.check(&source, true).unwrap();
    assert_eq!(source.calls.get(), 0);
    store.configure(vec![97, 97], vec![101], true).unwrap();
    let reopened = Store::new(root.path());
    assert_eq!(reopened.status().unwrap().settings.groups, [97]);
    assert!(reopened.due().unwrap());
    let status = reopened.check(&source, true).unwrap();
    assert_eq!(status.installed.len(), 1);
    assert_eq!(status.installed[0].entries, 3);
    assert!(!reopened.due().unwrap());
    reopened.check(&source, true).unwrap();
    assert_eq!(source.calls.get(), 1);
}
#[test]
fn sogou_replaces_by_origin_preserves_disabled_and_rejects_bad_download() {
    let root = tempfile::tempdir().unwrap();
    let store = Store::new(root.path());
    let source = Fake::new();
    store.install(101).unwrap();
    store.check(&source, false).unwrap();
    let before = store.collections().load().unwrap().collections[0].clone();
    store.set_enabled(101, false).unwrap();
    *source.bytes.borrow_mut() = ScelBuilder::new(vec![group(&[4, 5], &["你好"])]).build();
    let updated = store.check(&source, false).unwrap();
    assert_eq!(updated.installed[0].entries, 1);
    assert!(!updated.installed[0].enabled);
    let after = store.collections().load().unwrap().collections[0].clone();
    assert_eq!(before.id, after.id);
    assert_ne!(before.source, after.source);
    *source.bytes.borrow_mut() = b"<html>blocked</html>".to_vec();
    assert!(store
        .check(&source, false)
        .unwrap()
        .settings
        .error
        .is_some());
    assert_eq!(store.collections().load().unwrap().collections[0], after);
}
#[test]
fn sogou_remove_excludes_group_and_does_not_reinstall() {
    let root = tempfile::tempdir().unwrap();
    let store = Store::new(root.path());
    let source = Fake::new();
    store.configure(vec![97], vec![101], true).unwrap();
    store.check(&source, false).unwrap();
    store.remove(101).unwrap();
    store.check(&source, false).unwrap();
    assert!(store.status().unwrap().installed.is_empty());
    assert_eq!(source.calls.get(), 1);
    assert!(store.status().unwrap().settings.dictionaries.is_empty());
}
#[test]
fn sogou_invalid_rows_do_not_partially_replace_and_are_third_party() {
    let root = tempfile::tempdir().unwrap();
    let store = Store::new(root.path());
    let source = Fake::new();
    store.install(101).unwrap();
    store.check(&source, false).unwrap();
    let before = store.collections().load().unwrap().collections[0].clone();
    *source.bytes.borrow_mut() =
        ScelBuilder::new(vec![group(&[0, 1], &["测试"]), group(&[99, 1], &["坏词"])]).build();
    assert!(store
        .check(&source, false)
        .unwrap()
        .settings
        .error
        .is_some());
    assert_eq!(store.collections().load().unwrap().collections[0], before);
    let words = vec![super::super::personal::PersonalWord {
        kind: super::super::personal::PersonalWordKind::Pinyin,
        key: "ce'shi".into(),
        value: "测试".into(),
        weight: 10000,
    }];
    assert_eq!(
        store
            .collections()
            .third_party_matches(&words)
            .unwrap()
            .third_party_matches,
        Some(vec![0])
    );
}
#[test]
fn sogou_catalog_handles_tags_entities_pagination_and_refuses_challenges() {
    let html = "<a href='/dict/detail/index/9?rf=test'><b>合成</b>&amp;词库</a><a href=\"/dict/cate/index/97/updatetime/2\">下一页</a>";
    let catalog = parse_catalog(html, 97, 1).unwrap();
    assert_eq!(catalog.entries[0].id, 9);
    assert_eq!(catalog.entries[0].name, "合成&词库");
    assert!(catalog.has_more);
    assert!(parse_catalog("<html>访问验证</html>", 97, 1).is_err());
    assert!(super::catalog(&Fake::new(), 0, 1).is_err());
}
#[test]
fn sogou_origin_limits_and_invalid_configurations_are_rejected() {
    for url in [
        "http://pinyin.sogou.com/x",
        "https://pinyin.sogou.com.evil.test/x",
        "https://user@pinyin.sogou.com/x",
        "https://pinyin.sogou.com:123/x",
        "https://localhost/x",
    ] {
        assert!(!trusted(&url.parse().unwrap()), "{url}");
    }
    assert!(trusted(&"https://pinyin.sogou.com/dict/".parse().unwrap()));
    let root = tempfile::tempdir().unwrap();
    let store = Store::new(root.path());
    assert!(store.configure(vec![0], vec![], true).is_err());
    assert!(store.install(0).is_err());
    assert!(store.configure(vec![], (1..=33).collect(), true).is_err());
}
#[test]
fn sogou_configuration_changed_during_download_cannot_commit_old_selection() {
    struct Change {
        root: PathBuf,
    }
    impl Source for Change {
        fn page(&self, _: u32, _: u32) -> Result<String> {
            unreachable!()
        }
        fn dictionary(&self, _: u32) -> Result<Vec<u8>> {
            Store::new(&self.root).configure(vec![], vec![], false)?;
            Ok(ScelBuilder::sample().build())
        }
    }
    let root = tempfile::tempdir().unwrap();
    let store = Store::new(root.path());
    store.install(101).unwrap();
    store
        .check(
            &Change {
                root: root.path().into(),
            },
            false,
        )
        .unwrap();
    assert!(store.status().unwrap().installed.is_empty());
    assert!(!store.status().unwrap().settings.automatic);
}
#[test]
fn sogou_corrupt_settings_are_not_overwritten() {
    let root = tempfile::tempdir().unwrap();
    let store = Store::new(root.path());
    store.status().unwrap();
    let path = root.path().join("SogouDictionaries/settings.json");
    std::fs::write(&path, "bad").unwrap();
    assert!(store.configure(vec![97], vec![], true).is_err());
    assert_eq!(std::fs::read(&path).unwrap(), b"bad");
}

#[test]
#[ignore = "从搜狗官方读取公开目录及词库，需要联网"]
fn sogou_live_official_catalog_and_scel_install() {
    let source = Official::new().unwrap();
    let page = catalog(&source, 97, 1).unwrap();
    assert!(!page.entries.is_empty());
    let bytes = source.dictionary(15117).unwrap();
    let root = tempfile::tempdir().unwrap();
    let collections = Store::new(root.path()).collections();
    let result = collections.install_sogou(15117, &bytes).unwrap();
    assert!(result.collections[0].entry_count > 1000);
    println!(
        "official_catalog_entries={} scel_bytes={} installed_entries={}",
        page.entries.len(),
        bytes.len(),
        result.collections[0].entry_count
    );
}
