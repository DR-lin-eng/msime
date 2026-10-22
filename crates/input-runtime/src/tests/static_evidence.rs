use super::*;
use msime_engine::ordering::{rerank_pick, rerank_plan, OrderRow, ScoredOrderRows, StaticEvidence};

fn evidence(total: i64, characters: usize) -> Option<StaticEvidence> {
    Some(StaticEvidence {
        log_prob_milli_nats: total,
        characters,
    })
}

fn row(text: &str) -> OrderRow<'_> {
    OrderRow {
        text,
        source: LATTICE_SOURCE,
        answers_key: true,
        corrected: false,
    }
}

fn model() -> Reranker {
    Reranker::new(std::sync::Arc::new(favouring_model(&['甲', '乙'], &['乙'])))
}

#[test]
fn static_evidence_can_preserve_the_reading_the_model_alone_loses() {
    let rows = [row("甲"), row("乙")];
    let mut model = model();
    assert_eq!(rerank_pick(&mut model, "", 0, false, &rows), Some(1));
    let scores = [evidence(-1000, 1), evidence(-40000, 1)];
    assert_eq!(
        rerank_pick(
            &mut model,
            "",
            0,
            false,
            &ScoredOrderRows::new(&rows, &scores)
        ),
        None
    );
}

#[test]
fn incomplete_evidence_falls_back_to_the_existing_model() {
    let rows = [row("甲"), row("乙")];
    let mut model = model();
    for scores in [
        vec![],
        vec![evidence(-1000, 1)],
        vec![evidence(-1000, 1), None],
        vec![None, evidence(-40000, 1)],
        vec![evidence(-1000, 1), evidence(-40000, 0)],
    ] {
        assert_eq!(
            rerank_pick(
                &mut model,
                "",
                0,
                false,
                &ScoredOrderRows::new(&rows, &scores)
            ),
            Some(1),
            "{scores:?}"
        );
        let mut order = [0, 1];
        rerank_plan(
            &mut model,
            "",
            0,
            false,
            &ScoredOrderRows::new(&rows, &scores),
        )
        .unwrap()
        .apply(&mut order);
        assert_eq!(order, [1, 0], "缺失静态证据时仍只提升原模型首选");
    }
}

#[test]
fn static_means_compare_complete_readings_of_different_lengths() {
    // 模型同样喜欢每个字；总分会选短读法，逐字平均必须让长读法胜出。
    let rows = [row("乙"), row("乙乙")];
    let scores = [evidence(-5000, 1), evidence(-6000, 2)];
    assert_eq!(
        rerank_pick(
            &mut model(),
            "",
            0,
            false,
            &ScoredOrderRows::new(&rows, &scores)
        ),
        Some(1)
    );
    let tied = [evidence(-3000, 1), evidence(-6000, 2)];
    assert_eq!(
        rerank_pick(
            &mut model(),
            "",
            0,
            false,
            &ScoredOrderRows::new(&rows, &tied)
        ),
        None
    );
}

#[test]
fn static_evidence_preserves_dictionary_and_key_comparability_rules() {
    let mut rows = [row("甲"), row("乙")];
    let scores = [evidence(-40000, 1), evidence(-1000, 1)];
    rows[0].source = 0;
    assert_eq!(
        rerank_pick(
            &mut model(),
            "",
            0,
            false,
            &ScoredOrderRows::new(&rows, &scores)
        ),
        None
    );
    rows[0].source = LATTICE_SOURCE;
    rows[1].answers_key = false;
    assert_eq!(
        rerank_pick(
            &mut model(),
            "",
            0,
            false,
            &ScoredOrderRows::new(&rows, &scores)
        ),
        None
    );
    rows[1].answers_key = true;
    rows[1].corrected = true;
    assert_eq!(
        rerank_pick(
            &mut model(),
            "",
            1,
            false,
            &ScoredOrderRows::new(&rows, &scores)
        ),
        None
    );
    assert_eq!(
        rerank_pick(
            &mut model(),
            "",
            0,
            false,
            &ScoredOrderRows::new(&rows, &scores)
        ),
        Some(1)
    );
}

#[test]
fn static_evidence_ignores_nonreadings_and_keeps_the_nine_reading_budget() {
    let mut rows = vec![row("乙"); 10];
    let mut scores = vec![evidence(-5000, 1); 10];
    scores[9] = evidence(-1000, 1);
    assert_eq!(
        rerank_pick(
            &mut model(),
            "",
            0,
            false,
            &ScoredOrderRows::new(&rows, &scores)
        ),
        None,
        "第十条完整读法不扩充既有比较预算"
    );
    rows.insert(
        1,
        OrderRow {
            source: 3,
            ..row("乙")
        },
    );
    scores.insert(1, None);
    scores[2] = evidence(-3000, 1);
    assert_eq!(
        rerank_pick(
            &mut model(),
            "",
            0,
            false,
            &ScoredOrderRows::new(&rows, &scores)
        ),
        Some(2),
        "AI 行既不参与比较，也不因缺失证据阻止真实读法的混合"
    );
}

#[test]
fn successive_reranks_keep_static_evidence_on_its_engine_row() {
    let mut runtime = Runtime::new(
        Fixture {
            words: vec!["甲".into(), "乙".into()],
            codes: vec!["a".into(), "a".into()],
            sources: vec![LATTICE_SOURCE; 2],
            text: "a".into(),
            ..Fixture::default()
        },
        5,
    )
    .unwrap();
    runtime.focus(true).unwrap();
    type_key(&mut runtime);
    runtime.cached.candidate_static_evidence = vec![evidence(-40000, 1), evidence(-1000, 1)];
    runtime.set_settled_reranker(Some(model()));
    let before = runtime.cached.clone();
    runtime.set_settled_rerank_enabled(false);
    assert!(!runtime.rerank_settled());
    assert_eq!(runtime.cached, before, "静态证据不绕过关闭的模型开关");
    runtime.set_settled_rerank_enabled(true);
    assert!(runtime.rerank_settled());
    assert_eq!(runtime.cached.candidates, ["乙", "甲"]);
    assert_eq!(runtime.engine_order, [1, 0]);
    assert_eq!(
        runtime.cached.candidate_static_evidence,
        [evidence(-1000, 1), evidence(-40000, 1)]
    );
    assert!(
        !runtime.rerank_settled(),
        "同一行的证据重复排序后仍保持领先"
    );
    let first = runtime.view().candidates[0].clone();
    assert_eq!(
        runtime.dispatch(Action::Select(first.id)).unwrap().commit,
        Some("乙".into())
    );
}

#[test]
fn static_rerank_large_lists_do_not_allocate_a_full_text_projection() {
    for compared in [2, 9] {
        for interleaved in [false, true] {
            let mut prefix = Vec::new();
            let mut scores = Vec::new();
            for index in 0..compared {
                if interleaved && index > 0 {
                    prefix.push(OrderRow {
                        source: 3,
                        ..row("乙")
                    });
                    scores.push(None);
                }
                prefix.push(row(if index == 0 { "甲" } else { "乙" }));
                scores.push(evidence(
                    if index + 1 == compared { -1000 } else { -40000 },
                    1,
                ));
            }
            let expected = Some(prefix.len() - 1);
            for warmed in [false, true] {
                let run = |count| {
                    let mut rows = prefix.clone();
                    let mut scores = scores.clone();
                    rows.resize(
                        count,
                        OrderRow {
                            source: 3,
                            ..row("乙")
                        },
                    );
                    scores.resize(count, None);
                    let scored = ScoredOrderRows::new(&rows, &scores);
                    let mut model = model();
                    if warmed {
                        assert_eq!(rerank_pick(&mut model, "甲", 0, false, &scored), expected);
                    }
                    let (picked, count, bytes) = allocations::count_bytes(|| {
                        rerank_pick(&mut model, "甲", 0, false, &scored)
                    });
                    assert_eq!(picked, expected);
                    (count, bytes)
                };
                let baseline = run(prefix.len());
                for count in [128, 129, 1024] {
                    assert_eq!(run(count), baseline, "compared={compared} interleaved={interleaved} warmed={warmed} rows={count}");
                }
            }
        }
    }
}

#[test]
fn large_static_rerank_lists_preserve_fallback_and_key_rules() {
    for count in [2, 128, 129, 1024] {
        for mode in 0..7 {
            let mut rows = vec![
                OrderRow {
                    source: 3,
                    ..row("乙")
                };
                count
            ];
            rows[0] = row("甲");
            rows[1] = row("乙");
            let mut scores = vec![None; count];
            scores[0] = evidence(-1000, 1);
            scores[1] = evidence(-40000, 1);
            let mut scheme = 0;
            let expected = match mode {
                0 => {
                    scores[1] = None;
                    Some(1)
                }
                1 => {
                    scores[1] = evidence(-40000, 0);
                    Some(1)
                }
                2 => {
                    scores.pop();
                    Some(1)
                }
                3 => {
                    rows[0].source = 0;
                    None
                }
                4 => {
                    rows[0].source = 0;
                    rows[1].corrected = true;
                    scores[1] = evidence(-1000, 1);
                    scores[0] = evidence(-40000, 1);
                    Some(1)
                }
                5 => {
                    scheme = 1;
                    rows[1].corrected = true;
                    None
                }
                _ => {
                    rows[0].answers_key = false;
                    None
                }
            };
            assert_eq!(
                rerank_pick(
                    &mut model(),
                    "甲",
                    scheme,
                    false,
                    &ScoredOrderRows::new(&rows, &scores)
                ),
                expected,
                "rows={count} mode={mode}"
            );
        }
    }
}

#[test]
fn ranked_runner_ups_keep_their_scores_before_the_presentation_limit() {
    let mut runtime = Runtime::new(
        Fixture {
            words: vec![
                "甲甲甲".into(),
                "丙丙丙".into(),
                "丁丁丁".into(),
                "乙乙乙".into(),
            ],
            codes: vec!["a".into(), "b".into(), "c".into(), "d".into()],
            positions: vec![11, 22, 33, 44],
            sources: vec![LATTICE_SOURCE; 4],
            text: "a".into(),
            ..Fixture::default()
        },
        3,
    )
    .unwrap();
    runtime.focus(true).unwrap();
    type_key(&mut runtime);
    runtime.cached.candidate_static_evidence = vec![
        evidence(0, 3),
        evidence(-12000, 3),
        evidence(-24000, 3),
        evidence(-54000, 3),
    ];
    runtime.set_settled_reranker(Some(Reranker::new(std::sync::Arc::new(favouring_model(
        &['甲', '乙', '丙', '丁'],
        &['乙'],
    )))));
    assert!(
        runtime.rerank_settled(),
        "首选未变时，次选也应按已有混合评分更新"
    );
    assert_eq!(
        runtime.cached.candidates,
        ["甲甲甲", "乙乙乙", "丙丙丙", "丁丁丁"]
    );
    assert_eq!(runtime.cached.candidate_codes, ["a", "d", "b", "c"]);
    assert_eq!(runtime.cached.candidate_positions, [11, 44, 22, 33]);
    assert_eq!(
        runtime.cached.candidate_annotations,
        ["(0)", "(3)", "(1)", "(2)"]
    );
    assert_eq!(runtime.engine_order, [0, 3, 1, 2]);
    assert_eq!(
        runtime.cached.candidate_static_evidence,
        [
            evidence(0, 3),
            evidence(-54000, 3),
            evidence(-12000, 3),
            evidence(-24000, 3),
        ]
    );
    assert!(!runtime.rerank_settled(), "重复落定应保持同分和已评分次序");
    let second = runtime.view().candidates[1].clone();
    assert_eq!(
        runtime.dispatch(Action::Select(second.id)).unwrap().commit,
        Some("乙乙乙".into())
    );
}

#[test]
fn scored_runner_ups_keep_unscored_slots_and_equal_score_order() {
    let mut rows = vec![
        row("甲甲甲"),
        row("online"),
        row("丙丙丙"),
        row("丁丁丁"),
        row("乙乙乙"),
        row("catalog"),
        row("乙乙乙"),
    ];
    rows[1].source = 3;
    rows[5].source = 5;
    let evidence = vec![
        evidence(0, 3),
        None,
        evidence(-12000, 3),
        evidence(-24000, 3),
        evidence(-54000, 3),
        None,
        evidence(-54000, 3),
    ];
    let mut model = Reranker::new(std::sync::Arc::new(favouring_model(
        &['甲', '乙', '丙', '丁'],
        &['乙'],
    )));
    let plan = rerank_plan(
        &mut model,
        "",
        0,
        false,
        &ScoredOrderRows::new(&rows, &evidence),
    )
    .unwrap();
    let mut order: Vec<_> = (0..rows.len()).collect();
    plan.apply(&mut order);
    assert_eq!(order, [0, 1, 4, 6, 2, 5, 3]);
}

#[test]
fn scored_runner_ups_keep_the_nine_row_budget_and_bounded_allocations() {
    let run = |count| {
        let mut rows = vec![row("甲"); count];
        let mut evidence = vec![evidence(-40000, 1); count];
        for candidate in rows.iter_mut().take(9).skip(1) {
            *candidate = row("乙");
        }
        evidence[8] = Some(StaticEvidence {
            log_prob_milli_nats: -1000,
            characters: 1,
        });
        for index in 9..count {
            rows[index] = OrderRow {
                source: 3,
                ..row("乙")
            };
            evidence[index] = None;
        }
        let scored = ScoredOrderRows::new(&rows, &evidence);
        let mut model = model();
        assert!(rerank_plan(&mut model, "甲", 0, false, &scored).is_some());
        let (plan, allocations, bytes) =
            allocations::count_bytes(|| rerank_plan(&mut model, "甲", 0, false, &scored));
        let mut order: Vec<_> = (0..count).collect();
        plan.unwrap().apply(&mut order);
        assert_eq!(order[0], 8);
        assert!(order
            .iter()
            .enumerate()
            .skip(9)
            .all(|(seat, index)| seat == *index));
        (allocations, bytes)
    };
    let baseline = run(9);
    for count in [128, 129, 1024] {
        assert_eq!(run(count), baseline);
    }

    let rows = vec![row("甲"); 10];
    let mut evidence = vec![evidence(-40000, 1); 10];
    evidence[9] = Some(StaticEvidence {
        log_prob_milli_nats: 0,
        characters: 1,
    });
    assert!(
        rerank_plan(
            &mut model(),
            "",
            0,
            false,
            &ScoredOrderRows::new(&rows, &evidence)
        )
        .is_none(),
        "第十条的高分不能进入九条评分预算"
    );
}
