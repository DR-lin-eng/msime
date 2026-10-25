use msime_engine::{assets, Command, RuntimePaths, Session, SessionOptions};
use rusqlite::Connection;
fn fixture() -> (tempfile::TempDir, RuntimePaths) {
    let root = tempfile::tempdir().unwrap();
    let paths = RuntimePaths {
        resources: root.path().join("resources"),
        user_data: root.path().join("user"),
        cache: root.path().join("cache"),
        dictionaries: root.path().join("dictionaries"),
    };
    for p in [
        &paths.resources,
        &paths.user_data,
        &paths.cache,
        &paths.dictionaries,
    ] {
        std::fs::create_dir_all(p).unwrap();
    }
    std::fs::create_dir_all(paths.resources.join("helpcodes")).unwrap();
    std::fs::write(paths.resources.join("helpcodes/helpcode.txt"), "你=ab\n").unwrap();
    let db = Connection::open(paths.dictionary(assets::MAIN_DICTIONARY)).unwrap();
    for (key, word, weight) in [
        ("jia", "甲", 100),
        ("yi", "乙", 100),
        ("ni", "丙", 100),
        ("ni", "丁", 90),
    ] {
        let t = format!("tbl_1_{}", key.as_bytes()[0] as char);
        db.execute_batch(&format!(
            "CREATE TABLE IF NOT EXISTS {t}(key TEXT,jp TEXT,value TEXT,weight INTEGER)"
        ))
        .unwrap();
        db.execute(
            &format!("INSERT INTO {t} VALUES(?1,?2,?3,?4)"),
            (key, &key[..1], word, weight),
        )
        .unwrap();
    }
    db.execute_batch("CREATE TABLE wubi86(key TEXT,value TEXT,weight INTEGER)")
        .unwrap();
    msime_engine::ensure_english_schema(&paths.dictionary(assets::ENGLISH_DICTIONARY)).unwrap();
    (root, paths)
}
fn session(paths: &RuntimePaths) -> Session {
    let mut opts = SessionOptions::new(paths.clone());
    opts.frequency.mode = msime_engine::FrequencyAdjustmentMode::Disabled;
    Session::new(opts).unwrap()
}
fn pick(s: &mut Session, key: &str, word: &str) {
    for b in key.bytes() {
        s.character(b, false);
    }
    let snap = s.snapshot();
    let i = snap.candidates.iter().position(|w| w.word == word).unwrap();
    s.select(i);
}
fn leader(s: &mut Session, context: &str) -> String {
    s.command(Command::Cancel);
    s.reset_context();
    s.set_rescoring_context(context);
    for b in b"ni" {
        s.character(*b, false);
    }
    let word = s.snapshot().candidates[0].word.to_string();
    s.command(Command::Cancel);
    word
}
#[test]
fn adaptive_transaction_probe() {
    let (_root, paths) = fixture();
    let mut s = session(&paths);
    let context = format!("甲{}", "的".repeat(100));
    let cold = leader(&mut s, &context);
    for _ in 0..64 {
        s.reset_context();
        pick(&mut s, "jia", "甲");
        pick(&mut s, "ni", "丁");
        s.reset_context();
        pick(&mut s, "yi", "乙");
        pick(&mut s, "ni", "丙");
    }
    let learned = leader(&mut s, &context);
    s.set_personal_context_enabled(false);
    let disabled = leader(&mut s, &context);
    msime_engine::flush_personal_learning();
    drop(s);
    msime_engine::close_cached_databases();
    let mut s = session(&paths);
    let reopened = leader(&mut s, &context);
    println!("cold={cold}; learned_long={learned}; learning_off={disabled}; reopened={reopened}; context_characters={}",context.chars().count());
    assert_eq!(cold, "丙");
    assert_eq!(disabled, "丙");
}

#[test]
fn learned_ranking_survives_restart_and_respects_pin_and_learning_switch() {
    let (_root, paths) = fixture();
    let mut s = session(&paths);
    for _ in 0..64 {
        s.reset_context();
        pick(&mut s, "jia", "甲");
        pick(&mut s, "ni", "丁");
        s.reset_context();
        pick(&mut s, "yi", "乙");
        pick(&mut s, "ni", "丙");
    }
    let context = format!("甲{}", "的".repeat(100));
    assert_eq!(leader(&mut s, &context), "丁");
    msime_engine::flush_personal_learning();
    drop(s);
    msime_engine::close_cached_databases();
    let mut s = session(&paths);
    assert_eq!(leader(&mut s, &context), "丁");
    s.set_personal_context_enabled(false);
    assert_eq!(leader(&mut s, &context), "丙");
    s.set_personal_context_enabled(true);
    assert_eq!(leader(&mut s, &context), "丁");
    s.set_rescoring_context(&context);
    for b in b"ni" {
        s.character(*b, false);
    }
    let index = s
        .snapshot()
        .candidates
        .iter()
        .position(|row| row.word == "丙")
        .unwrap();
    s.pin(index);
    assert_eq!(leader(&mut s, &context), "丙");
    println!("learned=丁; reopened=丁; learning_off=丙; pinned=丙");
}

#[test]
fn disabled_learning_never_trains_or_creates_a_personal_journal() {
    let (_root, paths) = fixture();
    let mut opts = SessionOptions::new(paths.clone());
    opts.learning = false;
    let mut s = Session::new(opts).unwrap();
    for _ in 0..24 {
        s.reset_context();
        pick(&mut s, "jia", "甲");
        pick(&mut s, "ni", "丁");
    }
    assert_eq!(leader(&mut s, &format!("甲{}", "的".repeat(100))), "丙");
    msime_engine::flush_personal_learning();
    assert!(!paths.user(assets::USER_JOURNAL).exists());
}
