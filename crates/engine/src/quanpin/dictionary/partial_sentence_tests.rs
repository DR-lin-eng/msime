use super::*;
use crate::quanpin::fixture::Fixture;

fn fixture() -> Fixture {
    let fixture = Fixture::new();
    for (key, word) in [
        ("jia", "甲"),
        ("yi", "乙"),
        ("bing", "丙"),
        ("ding", "丁"),
        ("wu", "戊"),
        ("chi", "己"),
    ] {
        fixture.insert(key, word, 100);
    }
    fixture
}

#[test]
fn long_incomplete_tail_keeps_the_completed_sentence_prefix() {
    let fixture = fixture();
    for (raw, code) in [
        ("jiayibingdingwuch", "jia'yi'bing'ding'wu'ch"),
        ("jiayibingdingwuch", "jia'yi'bing'ding'wu'c'h"),
        ("jia'yi'bing'ding'wu'ch", "jia'yi'bing'ding'wu'ch"),
    ] {
        for types in [0, TYPO_EDGE_TYPES] {
            let mut dictionary = QuanpinDictionary::new(&fixture.paths);
            for _ in 0..2 {
                let rows = dictionary.query(raw, code, types, FuzzyPinyinOptions::default());
                let sentence = rows
                    .iter()
                    .find(|row| row.word == "甲乙丙丁戊")
                    .expect("长串未完成音节前的完整整句不应消失");
                assert_eq!(sentence.pinyin, "jia'yi'bing'ding'wu");
                assert_eq!(sentence.canonical_pinyin, "jia'yi'bing'ding'wu");
                assert_eq!(sentence.source, CandidateSource::Generated);
            }
        }
    }
}

#[test]
fn completing_the_tail_restores_the_full_sentence() {
    let fixture = fixture();
    let mut dictionary = QuanpinDictionary::new(&fixture.paths);
    dictionary.query(
        "jiayibingdingwuch",
        "jia'yi'bing'ding'wu'ch",
        0,
        FuzzyPinyinOptions::default(),
    );
    let rows = dictionary.query(
        "jiayibingdingwuchi",
        "jia'yi'bing'ding'wu'chi",
        0,
        FuzzyPinyinOptions::default(),
    );
    assert!(rows
        .iter()
        .any(|row| row.word == "甲乙丙丁戊己" && row.pinyin == "jia'yi'bing'ding'wu'chi"));
}

#[test]
fn incomplete_sentence_respects_the_lattice_switch() {
    let fixture = fixture();
    let mut dictionary = QuanpinDictionary::new(&fixture.paths);
    dictionary.set_sentence_association(SentenceAssociationOptions {
        word_lattice: false,
        ..SentenceAssociationOptions::default()
    });
    let rows = dictionary.query(
        "jiayibingdingwuch",
        "jia'yi'bing'ding'wu'ch",
        0,
        FuzzyPinyinOptions::default(),
    );
    assert!(!rows
        .iter()
        .any(|row| row.source == CandidateSource::Generated));
}

#[test]
fn incomplete_middle_and_short_inputs_keep_existing_boundaries() {
    let fixture = fixture();
    let mut dictionary = QuanpinDictionary::new(&fixture.paths);
    for (raw, code) in [
        ("jiaybingdingwuch", "jia'y'bing'ding'wu'ch"),
        ("jiayibingch", "jia'yi'bing'ch"),
    ] {
        let rows = dictionary.query(raw, code, 0, FuzzyPinyinOptions::default());
        assert!(!rows
            .iter()
            .any(|row| row.source == CandidateSource::Generated));
    }
}

#[test]
fn automatic_long_partial_segmentation_keeps_the_literal_prefix() {
    let fixture = fixture();
    for (key, word) in [
        ("wo", "我"),
        ("xian", "先"),
        ("zai", "在"),
        ("ce", "测"),
        ("shi", "试"),
        ("dao", "到"),
        ("shen", "深"),
        ("me", "么"),
    ] {
        fixture.insert(key, word, 100);
    }
    for raw in ["jiayibingdingwuch", "woxianzaiceshidaoshenmech"] {
        for types in [0, TYPO_EDGE_TYPES] {
            let mut dictionary = QuanpinDictionary::new(&fixture.paths);
            let rows = dictionary.query(raw, "", types, FuzzyPinyinOptions::default());
            assert!(
                rows.iter()
                    .take(9)
                    .any(|row| row.source == CandidateSource::Generated),
                "开启纠错时完整字面前缀的组句也应留在首页"
            );
        }
    }
}
