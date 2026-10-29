//! A session's lifecycle across threads and the process-level shutdown, through the public API only.
//!
//! A host may create a session on one thread and use or drop it on another (a worker pool, the C ABI). The journal connections a session opens are cached per thread; once the session is gone none of them may stay open, or on Windows the rename in `reset_learned_data` fails. This binary runs apart from the unit tests, so `shutdown` here cannot take a schedule another test is reading.

use std::path::Path;
use std::sync::mpsc;

use msime_engine::assets;
use msime_engine::{reset_learned_data, RuntimePaths, Session, SessionOptions};
use rusqlite::Connection;

const MAIN_SQL: &str = "CREATE TABLE tbl_2_n(key TEXT,jp TEXT,value TEXT,weight INTEGER);
INSERT INTO tbl_2_n VALUES('ni''hao','nh','你好',200),('ni''hao','nh','拟好',100);";

/// Packaged and generation directories apart, as a reset requires, each with the two dictionaries every scheme reads.
fn runtime(root: &Path) -> RuntimePaths {
    let paths = RuntimePaths {
        resources: root.join("resources"),
        user_data: root.join("user"),
        cache: root.join("cache"),
        dictionaries: root.join("dictionaries"),
    };
    for directory in [&paths.resources, &paths.dictionaries] {
        std::fs::create_dir_all(directory).unwrap();
        Connection::open(directory.join(assets::MAIN_DICTIONARY))
            .unwrap()
            .execute_batch(MAIN_SQL)
            .unwrap();
        msime_engine::ensure_english_schema(&directory.join(assets::ENGLISH_DICTIONARY)).unwrap();
    }
    std::fs::create_dir_all(&paths.user_data).unwrap();
    std::fs::create_dir_all(&paths.cache).unwrap();
    paths
}

fn session(paths: &RuntimePaths) -> Session {
    let mut options = SessionOptions::new(paths.clone());
    // The fixture ships no helpcode table.
    options.helpcode = false;
    Session::new(options).unwrap()
}

/// Types `nihao` and picks 拟好, which reads the journal while the candidates are built and records a personal-context transition.
fn pick(session: &mut Session) {
    for byte in b"nihao" {
        assert!(session.character(*byte, false).handled);
    }
    let index = session
        .snapshot()
        .candidates
        .iter()
        .position(|row| row.word == "拟好")
        .expect("拟好 is a candidate");
    assert_eq!(session.select(index).commit.as_deref(), Some("拟好"));
}

/// The personal-context count the journal holds for picks of 拟好.
fn learned_pairs(journal: &Path) -> i64 {
    Connection::open(journal)
        .unwrap()
        .query_row(
            "SELECT coalesce(sum(count),0) FROM personal_bigram WHERE word='拟好'",
            [],
            |row| row.get(0),
        )
        .unwrap()
}

#[test]
fn a_session_used_and_dropped_on_another_thread_leaves_the_journal_free_to_reset() {
    let root = tempfile::tempdir().unwrap();
    let paths = runtime(root.path());
    let journal = paths.user(assets::USER_JOURNAL);

    let (created_tx, created_rx) = mpsc::channel();
    let (finish_tx, finish_rx) = mpsc::channel::<()>();
    let creator_paths = paths.clone();
    // The creating thread uses the session once, hands it over and stays alive, holding whatever journal connection it cached, while the reset runs.
    let creator = std::thread::spawn(move || {
        let mut created = session(&creator_paths);
        pick(&mut created);
        created_tx.send(created).unwrap();
        finish_rx.recv().unwrap();
    });
    let mut moved = created_rx.recv().unwrap();
    let user = std::thread::spawn(move || {
        pick(&mut moved);
        drop(moved);
    });
    user.join().unwrap();
    // The drop on the other thread wrote the queued context.
    assert!(learned_pairs(&journal) >= 1);

    reset_learned_data(&paths).unwrap();
    assert_eq!(
        learned_pairs(&journal),
        0,
        "the reset kept the learned data"
    );

    finish_tx.send(()).unwrap();
    creator.join().unwrap();
}

#[test]
fn shutdown_writes_the_queue_now_and_learning_resumes_after_it() {
    let root = tempfile::tempdir().unwrap();
    let paths = runtime(root.path());
    let journal = paths.user(assets::USER_JOURNAL);
    let mut live = session(&paths);

    // One transition is under a batch, so without shutdown it would wait about 2 s on the writer thread.
    pick(&mut live);
    msime_engine::shutdown();
    let first = learned_pairs(&journal);
    assert!(first >= 1, "shutdown did not write the queued pick");

    // The writer starts again for the next record, and a second shutdown stops it again.
    pick(&mut live);
    msime_engine::shutdown();
    assert!(
        learned_pairs(&journal) > first,
        "the pick after the first shutdown was not written"
    );
    drop(live);
}
