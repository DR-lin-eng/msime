//! 夜间有界重放，数值模型与当前学习日志绑定，删除或继续学习会使旧缓存失效。

use super::PersonalRanker;
use rusqlite::{params, Connection};
use sha2::{Digest, Sha256};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};

static CANCELLED: AtomicBool = AtomicBool::new(false);

pub fn cancel() {
    CANCELLED.store(true, Ordering::Release);
}

#[derive(Debug, serde::Serialize)]
pub struct Outcome {
    pub status: &'static str,
    pub examples: usize,
    pub epochs: usize,
}

fn fingerprint(connection: &Connection) -> rusqlite::Result<String> {
    let mut digest = Sha256::new();
    let mut statement = connection
        .prepare("SELECT previous,word,count FROM personal_bigram ORDER BY previous,word")?;
    let mut rows = statement.query([])?;
    while let Some(row) = rows.next()? {
        for column in 0..2 {
            let text: String = row.get(column)?;
            digest.update((text.len() as u64).to_le_bytes());
            digest.update(text.as_bytes());
        }
        digest.update(row.get::<_, i64>(2)?.to_le_bytes());
    }
    Ok(hex::encode(digest.finalize()))
}

/// 仅由没有活动输入会话的维护调用进入；训练在独立内存里完成，再原子提交。
pub fn optimize(journal: &Path) -> crate::Result<Outcome> {
    CANCELLED.store(false, Ordering::Release);
    if !journal.is_file() {
        return Ok(Outcome {
            status: "no_data",
            examples: 0,
            epochs: 0,
        });
    }
    let connection = crate::user_dictionary::journal::open_database(
        journal,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_WRITE,
    )?;
    crate::user_dictionary::journal::ensure_schema(&connection)?;
    let before = fingerprint(&connection)?;
    let mut examples = Vec::new();
    {
        let mut statement=connection.prepare("SELECT previous,word,count FROM personal_bigram WHERE previous<>?1 ORDER BY count DESC,previous,word LIMIT 512")?;
        let mut rows = statement.query([crate::lattice::ngram::SENTENCE_START])?;
        while let Some(row) = rows.next()? {
            examples.push((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, u32>(2)?.min(4),
            ));
        }
    }
    if examples.is_empty() {
        return Ok(Outcome {
            status: "no_data",
            examples: 0,
            epochs: 0,
        });
    }
    let mut model = PersonalRanker::default();
    for (_, word, _) in &examples {
        model.observe("", word, 1);
    }
    for _ in 0..8 {
        for (context, word, count) in &examples {
            if CANCELLED.load(Ordering::Acquire) {
                return Ok(Outcome {
                    status: "cancelled",
                    examples: 0,
                    epochs: 0,
                });
            }
            model.observe(context, word, *count);
        }
    }
    let weights = serde_json::to_string(&model.snapshot())
        .map_err(|_| crate::EngineError::failed("invalid personal neural weights"))?;
    connection.execute_batch("BEGIN IMMEDIATE")?;
    if CANCELLED.load(Ordering::Acquire) || fingerprint(&connection)? != before {
        connection.execute_batch("ROLLBACK")?;
        return Ok(Outcome {
            status: "cancelled",
            examples: 0,
            epochs: 0,
        });
    }
    connection.execute_batch("CREATE TABLE IF NOT EXISTS personal_neural_model(id INTEGER PRIMARY KEY CHECK(id=1), fingerprint TEXT NOT NULL, weights TEXT NOT NULL)")?;
    connection.execute(
        "INSERT OR REPLACE INTO personal_neural_model VALUES(1,?1,?2)",
        params![before, weights],
    )?;
    connection.execute_batch("COMMIT")?;
    crate::user_dictionary::ngram_store::invalidate_journal(journal);
    Ok(Outcome {
        status: "trained",
        examples: examples.len(),
        epochs: 8,
    })
}

pub(crate) fn restore(connection: &Connection, model: &mut PersonalRanker) -> rusqlite::Result<()> {
    let present: i64 = connection.query_row(
        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='personal_neural_model'",
        [],
        |row| row.get(0),
    )?;
    if present == 0 {
        return Ok(());
    }
    let mut statement=connection.prepare("SELECT fingerprint,weights FROM personal_neural_model WHERE id=1 AND length(weights)<=4096")?;
    let mut rows = statement.query([])?;
    if let Some(row) = rows.next()? {
        let hash: String = row.get(0)?;
        let weights: String = row.get(1)?;
        if hash == fingerprint(connection)? {
            if let Ok(weights) = serde_json::from_str::<Vec<f32>>(&weights) {
                model.restore(&weights);
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn training_persists_numeric_weights_and_stale_data_is_not_reused() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("journal.db");
        let connection = Connection::open(&path).unwrap();
        crate::user_dictionary::journal::ensure_schema(&connection).unwrap();
        connection.execute("INSERT INTO personal_bigram(previous,word,count) VALUES('甲','丁',64),('乙','丙',64)",[]).unwrap();
        assert_eq!(optimize(&path).unwrap().status, "trained");
        let mut model = PersonalRanker::default();
        restore(&connection, &mut model).unwrap();
        assert!(model.snapshot().iter().any(|value| *value != 0.0));
        let weights: String = connection
            .query_row("SELECT weights FROM personal_neural_model", [], |r| {
                r.get(0)
            })
            .unwrap();
        assert!(!weights.contains('甲') && !weights.contains('丁'));
        connection
            .execute("DELETE FROM personal_bigram", [])
            .unwrap();
        let mut fresh = PersonalRanker::default();
        restore(&connection, &mut fresh).unwrap();
        assert!(fresh.snapshot().iter().all(|value| *value == 0.0));
    }
}
