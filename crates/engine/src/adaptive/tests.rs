use super::*;

#[test]
fn context_is_bounded_by_characters_and_retains_distant_topic() {
    let mut context = String::new();
    remember_context(&mut context, &format!("甲{}", "的".repeat(400)));
    assert!(context.starts_with('甲'));
    remember_context(&mut context, &"乙".repeat(200));
    assert_eq!(context.chars().count(), CONTEXT_CHARACTERS);
    assert!(context.ends_with('乙'));
    remember_context(&mut context, &"🙂".repeat(1000));
    assert_eq!(context.chars().count(), CONTEXT_CHARACTERS);
    assert_eq!(context, "🙂".repeat(CONTEXT_CHARACTERS));
}

fn trained() -> PersonalRanker {
    let mut model = PersonalRanker::default();
    for _ in 0..64 {
        model.observe("", "甲", 2);
        model.observe("甲", "丁", 2);
        model.observe("", "乙", 2);
        model.observe("乙", "丙", 2);
    }
    model
}

#[test]
fn repeated_choices_teach_opposite_contexts_and_generalize_beyond_64_characters() {
    let model = trained();
    for (topic, expected) in [("甲", 1), ("乙", 0)] {
        for context in [topic.to_owned(), format!("{topic}{}", "的".repeat(100))] {
            let scores = model.scores(&context, &["丙", "丁"]).unwrap();
            assert!(
                scores[expected] > scores[1 - expected],
                "{topic}: {scores:?}"
            );
        }
    }
}

#[test]
fn cold_or_empty_context_does_not_invent_preferences() {
    let mut model = PersonalRanker::default();
    assert!(model.scores("甲", &["丙", "丁"]).is_none());
    model.observe("甲", "丁", 1);
    assert!(model.scores("甲", &["丙", "丁"]).is_none());
    assert!(trained().scores("", &["丙", "丁"]).is_none());
}

#[test]
fn aggregated_journal_counts_stay_bounded_and_learn_the_same_choice() {
    let mut model = PersonalRanker::default();
    for word in ["甲", "乙", "丙", "丁"] {
        model.observe("", word, 128);
    }
    model.observe("甲", "丁", 128);
    model.observe("乙", "丙", 128);
    let scores = model.scores("甲", &["丙", "丁"]).unwrap();
    assert!(scores[1] > scores[0]);
    assert!(model
        .head
        .iter()
        .all(|weight| weight.is_finite() && weight.abs() <= 2.0));
}

#[test]
fn onnx_graph_uses_exact_cpu_weights() {
    let graph = include_bytes!("model.onnx");
    let bytes: Vec<u8> = WEIGHTS
        .iter()
        .flat_map(|weight| weight.to_le_bytes())
        .collect();
    assert!(graph.windows(bytes.len()).any(|window| window == bytes));
}
