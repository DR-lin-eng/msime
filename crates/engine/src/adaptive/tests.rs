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

fn frozen_features(context: &[f32; SIGNATURE], word: &[f32; SIGNATURE]) -> [f32; INPUTS] {
    let mut input = [0.0; INPUTS];
    input[..SIGNATURE].copy_from_slice(context);
    input[SIGNATURE..SIGNATURE * 2].copy_from_slice(word);
    for (left, &a) in context.iter().enumerate() {
        for (right, &b) in word.iter().enumerate() {
            let hash = mix((left * SIGNATURE + right) as u32 + 0x1024);
            let index = SIGNATURE * 2 + hash as usize % (INPUTS - SIGNATURE * 2);
            input[index] += a * b * if hash & 0x100 != 0 { 1.0 } else { -1.0 };
        }
    }
    input
}

#[test]
fn projected_features_preserve_every_float_bit_for_sparse_and_dense_signatures() {
    let texts = ["", "甲", "合成词", "a🙂𠀀", "负号与零", "字"];
    for context in texts {
        for word in texts {
            let context = signature(context);
            let word = signature(word);
            assert_eq!(
                features(&context, &word).map(f32::to_bits),
                frozen_features(&context, &word).map(f32::to_bits)
            );
        }
    }
    let mut seed = 0x1024_u32;
    for case in 0..1024 {
        let mut values = [[0.0; SIGNATURE]; 2];
        for row in &mut values {
            for value in row {
                seed = mix(seed.wrapping_add(1));
                *value = match seed % 8 {
                    0 => -0.0,
                    1..=3 if case % 2 == 0 => 0.0,
                    _ => (seed % 2049) as f32 / 1024.0 - 1.0,
                };
            }
        }
        assert_eq!(
            features(&values[0], &values[1]).map(f32::to_bits),
            frozen_features(&values[0], &values[1]).map(f32::to_bits),
            "case={case}"
        );
    }
}

#[test]
fn projected_features_use_no_heap_storage() {
    let context = signature("合成上下文");
    let word = signature("合成候选");
    let (actual, allocations) =
        crate::ime::personal_rerank::allocations::count(|| features(&context, &word));
    assert_eq!(allocations, 0);
    assert_eq!(
        actual.map(f32::to_bits),
        frozen_features(&context, &word).map(f32::to_bits)
    );
}

#[test]
fn a_full_context_window_appends_without_transient_growth() {
    let initial = "甲".repeat(CONTEXT_CHARACTERS);
    let mut context = String::with_capacity(initial.len());
    context.push_str(&initial);
    let capacity = context.capacity();
    let ((), allocations) = crate::ime::personal_rerank::allocations::count(|| {
        for _ in 0..1024 {
            remember_context(&mut context, "乙");
        }
    });
    assert_eq!(allocations, 0);
    assert_eq!(context.capacity(), capacity);
    assert_eq!(context, "乙".repeat(CONTEXT_CHARACTERS));
}

#[test]
fn context_trimming_preserves_unicode_suffixes_for_mixed_width_and_oversized_input() {
    for initial in ["".to_owned(), "甲".repeat(512), "🙂a𠀀".repeat(400)] {
        for text in [
            "".to_owned(),
            "b".to_owned(),
            "乙🙂a".repeat(200),
            "𠀀".repeat(1024),
        ] {
            let expected =
                crate::text::last_characters(&format!("{initial}{text}"), CONTEXT_CHARACTERS)
                    .to_owned();
            let mut actual = initial.clone();
            remember_context(&mut actual, &text);
            assert_eq!(actual, expected);
        }
    }
}
