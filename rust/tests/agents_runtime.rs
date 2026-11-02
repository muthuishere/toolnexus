//! §7D runtime acceptance (S1–S9) against the shared examples/subagent-*
//! expectations, plus the shipped-only obligations. Mirrors
//! golang/agents/runtime_test.go. Hermetic: a scripted in-process model.

mod agents_support;

use std::sync::{Arc, Mutex};
use std::time::Duration;

use agents_support::*;
use tokio_util::sync::CancellationToken;
use toolnexus::Answer;
use toolnexus::agents::{
    Budget, CloseOptions, Def, InboxItem, OnCloseFn, Options, Runtime, State, TaskResult,
};
use toolnexus::store::{ConversationStore, InMemoryConversationStore};

fn rt_with(slow: CancellationToken) -> Runtime {
    Runtime::new(Options {
        in_process: Some(mock_llm(slow)),
        registry: test_registry(),
        ..Default::default()
    })
}

fn rt() -> Runtime {
    rt_with(CancellationToken::new())
}

// SPEC §7D "Background runs": spawn + wake return at once with the id; a background
// wait delivers the completion to the host's callback. No new API; pins the recipe.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn background_run_recipe() {
    let rt = rt();
    let h = rt.spawn(&rt.root(), "coordinator", None).unwrap();
    let id = h.id.clone();
    assert!(!id.is_empty(), "the handle id is available at once");
    rt.wake(&h, "answer using two lookups");
    let (tx, rx) = tokio::sync::oneshot::channel();
    let (rt2, h2, id2) = (rt.clone(), h.clone(), id.clone());
    tokio::spawn(async move {
        let r = rt2.wait(&h2, None).await;
        let _ = tx.send((id2, r)); // on_done(id, r)
    });
    let (got_id, r) = tokio::time::timeout(Duration::from_secs(5), rx)
        .await
        .expect("completion within 5s")
        .unwrap();
    assert_eq!(got_id, id);
    assert_eq!(r.status, "done", "{}", r.text);
    assert!(r.text.contains("found:"), "{}", r.text);
}

// S1/S2 — fan-out, isolation, deterministic ids, concurrency, roll-up, auto-close.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn task_fanout_isolation_and_rollup() {
    let rt = rt();
    let coord = rt.spawn(&rt.root(), "coordinator", None).unwrap();
    rt.wake(&coord, "answer using two lookups");
    let r = rt.wait(&coord, None).await;
    assert_eq!(r.status, "done", "{}", r.text);
    assert!(
        r.text.contains("found:data(x)") && r.text.matches("found:").count() == 2,
        "{}",
        r.text
    );
    assert_eq!(r.turns, 2, "children's turns stay out of the parent");
    assert!(trace_has(&rt, "/coordinator.1/explore.1:") && trace_has(&rt, "/explore.2:"));
    assert!(
        rt.max_observed_concurrent_turns() >= 2,
        "children should run concurrently"
    );
    assert_eq!(rt.usage_tokens(&coord), 240, "roll-up ledger");
    for h in rt.list() {
        if h.id != coord.id {
            assert_eq!(h.state, State::Closed, "{} should be auto-closed", h.id);
        }
    }
}

// S3 — escalation to the NEAREST ancestor interpreter, inline.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn escalation_nearest_interpreter() {
    let mut reg = test_registry();
    let ap = reg.get_mut("approverParent").unwrap();
    ap.wait_for = Some(Arc::new(|req| {
        Box::pin(async move {
            Ok(Answer {
                id: req.id,
                ok: true,
                ..Default::default()
            })
        })
    }));
    let rt = Runtime::new(Options {
        in_process: Some(mock_llm(CancellationToken::new())),
        registry: reg,
        ..Default::default()
    });
    let p = rt.spawn(&rt.root(), "approverParent", None).unwrap();
    rt.wake(&p, "do the secret thing");
    let r = rt.wait(&p, None).await;
    assert_eq!(r.status, "done");
    assert!(r.text.contains("asker-done: secret-token"), "{}", r.text);
    assert!(trace_has(&rt, "running→suspended") && trace_has(&rt, "suspended→running"));
    assert!(trace_has(&rt, "escalate → root/approverParent.1 answers"));
}

// S4 — durable pending + resume cascade that REATTACHES.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn durable_resume_cascade_reattaches() {
    let rt = rt();
    let p = rt.spawn(&rt.root(), "approverParent", None).unwrap();
    rt.wake(&p, "do the secret thing");
    let r1 = rt.wait(&p, None).await;
    assert_eq!(r1.status, "pending");
    let pend = r1.pending.clone().unwrap();
    assert_eq!(pend.kind, "approval");
    let path: Vec<String> = pend.data.as_ref().unwrap()["path"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v.as_str().unwrap().to_string())
        .collect();
    assert!(path.join("/").contains("approverParent.1"), "{path:?}");
    let mut before = 0;
    for h in rt.list() {
        assert_eq!(h.state, State::Suspended, "{}", h.id);
        if h.id.contains("asker") {
            before = h.tokens;
        }
    }
    rt.resume(Answer {
        id: pend.id.clone(),
        ok: true,
        ..Default::default()
    })
    .await
    .unwrap();
    let tr = rt.trace().join("\n");
    assert!(tr.contains("resume with Answer(ok=true) at checkpoint"));
    assert!(tr.contains("task replay → REATTACH"));
    assert_eq!(rt.state_of(&p), State::Idle);
    let (mut after, mut askers) = (0, 0);
    for h in rt.list() {
        if h.id.contains("asker") {
            after = h.tokens;
            askers += 1;
        }
    }
    assert_eq!(askers, 1, "no duplicate child");
    assert!(
        after > before,
        "usage grows across resume ({before} → {after})"
    );
    let last = rt.wait(&p, None).await;
    assert!(
        last.text.contains("asker-done: secret-token"),
        "{}",
        last.text
    );
}

// S5 — one wake drains the whole inbox as ONE turn; ticks dedupe.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn unsolicited_rail_coalesced_drain() {
    let rt = rt();
    let peer = rt.spawn(&rt.root(), "peer", None).unwrap();
    rt.post(
        &peer,
        InboxItem::new("root/coordinator.1", "peer", "update 1"),
    );
    rt.post(
        &peer,
        InboxItem::new("external", "external", "webhook payload"),
    );
    for _ in 0..3 {
        rt.post(&peer, InboxItem::new("clock", "timer", "tick"));
    }
    rt.wake(&peer, "");
    let r = rt.wait(&peer, None).await;
    assert_eq!(r.turns, 1);
    assert_eq!(r.text, "processed 3 items");
}

// S6 — inbox gate.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn backpressure_inbox_gate() {
    let rt = Runtime::new(Options {
        in_process: Some(mock_llm(CancellationToken::new())),
        registry: test_registry(),
        inbox_cap: 2,
        ..Default::default()
    });
    let peer = rt.spawn(&rt.root(), "peer", None).unwrap();
    rt.post(&peer, InboxItem::new("a", "peer", "1"));
    rt.post(&peer, InboxItem::new("a", "peer", "2"));
    let third = rt.post(&peer, InboxItem::new("a", "peer", "3"));
    assert!(!third.ok && third.err.contains("inbox full"), "{third:?}");
    assert_eq!(rt.inbox_len(&peer), 2);
}

// S6 — per-parent concurrency gate with slot transfer.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn backpressure_concurrency_gate() {
    let mut reg = test_registry();
    reg.get_mut("coordinator").unwrap().budget = Some(Budget {
        max_concurrent: 1,
        ..Default::default()
    });
    let rt = Runtime::new(Options {
        in_process: Some(mock_llm(CancellationToken::new())),
        registry: reg,
        ..Default::default()
    });
    let c = rt.spawn(&rt.root(), "coordinator", None).unwrap();
    rt.wake(&c, "go");
    let r = rt.wait(&c, None).await;
    assert_eq!(r.status, "done", "{}", r.text);
    assert!(trace_has(&rt, "wake QUEUED") && trace_has(&rt, "DEQUEUED wake"));
    assert!(rt.max_observed_concurrent_turns() <= 2);
}

// S6 — the global turn gate wraps ONLY the provider call.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn backpressure_turn_gate() {
    let rt = Runtime::new(Options {
        in_process: Some(mock_llm(CancellationToken::new())),
        registry: test_registry(),
        max_concurrent_turns: 1,
        ..Default::default()
    });
    let c = rt.spawn(&rt.root(), "coordinator", None).unwrap();
    rt.wake(&c, "go");
    let r = rt.wait(&c, None).await;
    assert_eq!(r.status, "done", "a delegating parent must not deadlock");
    assert_eq!(rt.max_observed_concurrent_turns(), 1);
}

// S7 — hierarchical carve, live ancestor walk, maxChildren, loud incomplete.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn hierarchical_budgets() {
    let rt = rt();
    let c = rt
        .spawn(
            &rt.root(),
            "coordinator",
            Some(Budget {
                max_tokens: 100,
                max_children: 2,
                ..Default::default()
            }),
        )
        .unwrap();
    let kid = rt
        .spawn(
            &c,
            "explore",
            Some(Budget {
                max_tokens: 500,
                ..Default::default()
            }),
        )
        .unwrap();
    assert_eq!(
        rt.pool_tokens(&kid),
        100,
        "carve = min(own, parent remaining)"
    );
    let kid2 = rt.spawn(&c, "explore", None).unwrap();
    let err = rt.spawn(&c, "explore", None).unwrap_err();
    assert!(err.contains("maxChildren"), "{err}");
    rt.wake(&kid, "go");
    rt.wait(&kid, None).await;
    assert_eq!(rt.pool_tokens(&c), 20, "roll-up drains the parent pool");
    rt.wake(&kid2, "go");
    assert_eq!(rt.wait(&kid2, None).await.status, "done");
    let r3 = rt.run_turn(&kid2, "again").await;
    assert!(
        r3.status == "incomplete" && r3.text.contains("budget exhausted"),
        "{r3:?}"
    );

    let mut reg = test_registry();
    reg.get_mut("looper").unwrap().budget = Some(Budget {
        max_turns: 3,
        ..Default::default()
    });
    let rt4 = Runtime::new(Options {
        in_process: Some(mock_llm(CancellationToken::new())),
        registry: reg,
        ..Default::default()
    });
    let h = rt4.spawn(&rt4.root(), "looper", None).unwrap();
    rt4.wake(&h, "loop forever");
    assert_eq!(rt4.wait(&h, None).await.status, "incomplete");
}

// S8 — interrupt aborts the TURN, not the agent; drained items restored.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn interrupt_is_turn_abort_with_drain_restore() {
    let gate = CancellationToken::new();
    let rt = rt_with(gate.clone());
    let s = rt.spawn(&rt.root(), "slow", None).unwrap();
    rt.post(&s, InboxItem::new("root", "peer", "for later"));
    rt.wake(&s, "work slowly");
    assert!(await_state(&rt, &s, State::Running).await);
    let waiter = {
        let (rt, s) = (rt.clone(), s.clone());
        tokio::spawn(async move { rt.wait(&s, None).await })
    };
    tokio::time::sleep(Duration::from_millis(10)).await;
    rt.interrupt(&s);
    let r = waiter.await.unwrap();
    assert!(r.is_error && r.status == "interrupted", "{r:?}");
    assert_eq!(rt.state_of(&s), State::Idle);
    assert_eq!(rt.inbox_len(&s), 1, "transactional drain restores the item");
    gate.cancel();
    rt.wake(&s, "work again");
    assert_eq!(rt.wait(&s, None).await.status, "done");
}

// S9 — close(root) cascades LEAF-FIRST; closed handles reject posts.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn close_cascade_leaf_first() {
    let order = Arc::new(Mutex::new(Vec::<String>::new()));
    let mut reg = test_registry();
    let o = order.clone();
    let on_close: OnCloseFn = Arc::new(move |h, _| o.lock().unwrap().push(h.id.clone()));
    for n in ["coordinator", "peer"] {
        reg.get_mut(n).unwrap().on_close = Some(on_close.clone());
    }
    let rt = Runtime::new(Options {
        in_process: Some(mock_llm(CancellationToken::new())),
        registry: reg,
        ..Default::default()
    });
    let c = rt.spawn(&rt.root(), "coordinator", None).unwrap();
    let k1 = rt.spawn(&c, "peer", None).unwrap();
    let k2 = rt.spawn(&k1, "peer", None).unwrap();
    rt.close(&rt.root(), None).await;
    let got = order.lock().unwrap().join(" → ");
    assert_eq!(got, format!("{} → {} → {}", k2.id, k1.id, c.id));
    for h in rt.list() {
        assert_eq!(h.state, State::Closed);
    }
    let p = rt.post(&k1, InboxItem::new("x", "peer", "late"));
    assert!(!p.ok && p.err.contains("closed"));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn transcript_survives_turns() {
    let rt = rt();
    let h = rt.spawn(&rt.root(), "counter", None).unwrap();
    assert_eq!(rt.run_turn(&h, "one").await.text, "msgs:2");
    assert_eq!(rt.run_turn(&h, "two").await.text, "msgs:4");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn transcript_rewinds_on_pending() {
    let store = Arc::new(InMemoryConversationStore::new());
    let rt = Runtime::new(Options {
        in_process: Some(mock_llm(CancellationToken::new())),
        registry: test_registry(),
        store: Some(store.clone()),
        ..Default::default()
    });
    let p = rt.spawn(&rt.root(), "approverParent", None).unwrap();
    rt.wake(&p, "do the secret thing");
    let r = rt.wait(&p, None).await;
    assert_eq!(r.status, "pending");
    for row in rt.list() {
        let n = store.get(&row.id).unwrap().unwrap_or_default().len();
        assert_eq!(n, 0, "{} must stay at its pre-turn checkpoint", row.id);
    }
    rt.resume(Answer {
        id: r.pending.unwrap().id,
        ok: true,
        ..Default::default()
    })
    .await
    .unwrap();
    assert!(trace_has(&rt, "task replay → REATTACH"));
    assert!(!store.get(&p.id).unwrap().unwrap_or_default().is_empty());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn forced_close_restores_drained_items() {
    let gate = CancellationToken::new();
    let rt = rt_with(gate.clone());
    let s = rt.spawn(&rt.root(), "slow", None).unwrap();
    rt.post(&s, InboxItem::new("root", "peer", "for later"));
    rt.wake(&s, "work slowly");
    assert!(await_state(&rt, &s, State::Running).await);
    rt.close(
        &s,
        Some(CloseOptions {
            force: true,
            ..Default::default()
        }),
    )
    .await;
    assert_eq!(rt.state_of(&s), State::Closed);
    assert_eq!(rt.inbox_len(&s), 1);
    gate.cancel();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn graceful_close_escalates_after_shutdown() {
    let gate = CancellationToken::new();
    let rt = Runtime::new(Options {
        in_process: Some(mock_llm(gate.clone())),
        registry: test_registry(),
        shutdown: Duration::from_millis(20),
        ..Default::default()
    });
    let s = rt.spawn(&rt.root(), "slow", None).unwrap();
    rt.post(&s, InboxItem::new("root", "peer", "for later"));
    rt.wake(&s, "work slowly");
    assert!(await_state(&rt, &s, State::Running).await);
    tokio::time::timeout(Duration::from_secs(2), rt.close(&s, None))
        .await
        .expect("graceful close never escalated");
    assert_eq!(rt.state_of(&s), State::Closed);
    assert_eq!(rt.inbox_len(&s), 1);
    gate.cancel();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn runtime_uses_injected_store() {
    let store = Arc::new(InMemoryConversationStore::new());
    let rt = Runtime::new(Options {
        in_process: Some(mock_llm(CancellationToken::new())),
        registry: test_registry(),
        store: Some(store.clone()),
        ..Default::default()
    });
    let h = rt.spawn(&rt.root(), "peer", None).unwrap();
    rt.wake(&h, "hello");
    rt.wait(&h, None).await;
    assert!(!store.get(&h.id).unwrap().unwrap_or_default().is_empty());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn turn_gate_releases_on_acquirer_death() {
    let gate = CancellationToken::new();
    let rt = Runtime::new(Options {
        in_process: Some(mock_llm(gate.clone())),
        registry: test_registry(),
        max_concurrent_turns: 1,
        ..Default::default()
    });
    let s = rt.spawn(&rt.root(), "slow", None).unwrap();
    let p = rt.spawn(&rt.root(), "peer", None).unwrap();
    rt.wake(&s, "hold the slot");
    assert!(await_state(&rt, &s, State::Running).await);
    tokio::time::sleep(Duration::from_millis(20)).await;
    rt.wake(&p, "queued behind the slot");
    tokio::time::sleep(Duration::from_millis(20)).await;
    rt.interrupt(&s);
    let r = rt.wait(&p, None).await;
    assert!(r.status == "done" && r.text == "processed 0 items", "{r:?}");
    gate.cancel();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn wait_timeout_child_keeps_running() {
    let gate = CancellationToken::new();
    let rt = rt_with(gate.clone());
    let s = rt.spawn(&rt.root(), "slow", None).unwrap();
    rt.wake(&s, "take your time");
    assert!(await_state(&rt, &s, State::Running).await);
    let r = rt.wait(&s, Some(Duration::from_millis(30))).await;
    assert!(
        r.is_error && r.status == "timeout" && r.text.contains("wait timeout"),
        "{r:?}"
    );
    assert_eq!(r.text, "wait timeout after 30ms (child still running)");
    assert_eq!(rt.state_of(&s), State::Running);
    gate.cancel();
    let r = rt.wait(&s, None).await;
    assert!(r.status == "done" && r.text == "slow-done", "{r:?}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn wait_settled_fast_path() {
    let rt = rt();
    let h = rt.spawn(&rt.root(), "peer", None).unwrap();
    rt.wake(&h, "go");
    let first = rt.wait(&h, None).await;
    let again = rt.wait(&h, Some(Duration::from_millis(10))).await;
    assert_eq!(again, first);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn wall_clock_budget() {
    let rt = rt();
    let h = rt
        .spawn(
            &rt.root(),
            "peer",
            Some(Budget {
                max_wall: Duration::from_nanos(1),
                ..Default::default()
            }),
        )
        .unwrap();
    tokio::time::sleep(Duration::from_millis(1)).await;
    let r = rt.run_turn(&h, "too late").await;
    assert!(
        r.status == "incomplete" && r.text.contains("wallMs"),
        "{r:?}"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn tool_call_budget() {
    let rt = rt();
    let h = rt
        .spawn(
            &rt.root(),
            "explore",
            Some(Budget {
                max_tool_calls: 1,
                ..Default::default()
            }),
        )
        .unwrap();
    assert_eq!(rt.run_turn(&h, "go").await.status, "done");
    let r = rt.run_turn(&h, "go again").await;
    assert!(
        r.status == "incomplete" && r.text.contains("toolCalls"),
        "{r:?}"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn max_depth_at_spawn() {
    let rt = rt();
    let mut h = rt.root();
    for i in 0..3 {
        h = rt
            .spawn(&h, "peer", None)
            .unwrap_or_else(|e| panic!("spawn {i}: {e}"));
    }
    let err = rt.spawn(&h, "peer", None).unwrap_err();
    assert!(err.contains("maxDepth"), "{err}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn task_outside_team_refused() {
    let rt = rt();
    let h = rt.spawn(&rt.root(), "rogue", None).unwrap();
    rt.wake(&h, "delegate to a stranger");
    let r = rt.wait(&h, None).await;
    assert!(
        r.text.contains("not in this agent's team") && r.text.contains("explore"),
        "{}",
        r.text
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn no_team_means_no_task_tool() {
    let rt = rt();
    let h = rt.spawn(&rt.root(), "peer", None).unwrap();
    rt.wake(&h, "hello");
    rt.wait(&h, None).await;
    assert!(!trace_has(&rt, "task replay") && rt.list().len() == 1);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn virtual_clock_wait_timeout() {
    let gate = CancellationToken::new();
    let clk = FakeClock::new();
    let rt = Runtime::new(Options {
        in_process: Some(mock_llm(gate.clone())),
        registry: test_registry(),
        clock: Some(clk.clone()),
        ..Default::default()
    });
    let s = rt.spawn(&rt.root(), "slow", None).unwrap();
    rt.wake(&s, "hold");
    assert!(await_state(&rt, &s, State::Running).await);
    let waiter = {
        let (rt, s) = (rt.clone(), s.clone());
        tokio::spawn(async move { rt.wait(&s, Some(Duration::from_secs(3600))).await })
    };
    tokio::time::sleep(Duration::from_millis(30)).await;
    assert!(
        !waiter.is_finished(),
        "wait resolved before the virtual clock fired"
    );
    clk.fire();
    let r = waiter.await.unwrap();
    assert_eq!(r.status, "timeout");
    assert_eq!(r.text, "wait timeout after 1h0m0s (child still running)");
    gate.cancel();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn interrupt_suspended_cancels_pending() {
    let rt = rt();
    let p = rt.spawn(&rt.root(), "approverParent", None).unwrap();
    rt.wake(&p, "do the secret thing");
    assert_eq!(rt.wait(&p, None).await.status, "pending");
    rt.interrupt(&p);
    assert_eq!(rt.state_of(&p), State::Idle);
    assert!(trace_has(&rt, "interrupt cancelled pending"));
    assert_eq!(rt.inspect(&p).pending_kind, "");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn spawn_unknown_agent_lists_registry_sorted() {
    let mut reg = std::collections::HashMap::new();
    reg.insert("zeta".to_string(), def("zeta", "z", "", "m"));
    reg.insert("alpha".to_string(), def("alpha", "a", "", "m"));
    let rt = Runtime::new(Options {
        in_process: Some(mock_llm(CancellationToken::new())),
        registry: reg,
        ..Default::default()
    });
    let err = rt.spawn(&rt.root(), "nope", None).unwrap_err();
    assert!(err.contains("alpha, zeta"), "{err}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn post_while_running_enters_next_drain() {
    let gate = CancellationToken::new();
    let rt = rt_with(gate.clone());
    let s = rt.spawn(&rt.root(), "slow", None).unwrap();
    rt.wake(&s, "busy");
    assert!(await_state(&rt, &s, State::Running).await);
    rt.post(&s, InboxItem::new("x", "peer", "mid-turn item"));
    gate.cancel();
    rt.wait(&s, None).await;
    assert_eq!(
        rt.inbox_len(&s),
        1,
        "nothing unsolicited enters a LIVE turn"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn list_and_inspect_views() {
    let rt = rt();
    let h = rt.spawn(&rt.root(), "peer", None).unwrap();
    rt.wake(&h, "go");
    rt.wait(&h, None).await;
    let rows = rt.list();
    assert!(rows.len() == 1 && rows[0].id == h.id && rows[0].state == State::Idle);
    let v = rt.inspect(&h);
    assert!(v.turns == 1 && v.tokens == 40, "{v:?}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn conversation_store_accessor() {
    let rt = rt();
    let h = rt.spawn(&rt.root(), "peer", None).unwrap();
    rt.wake(&h, "hello");
    rt.wait(&h, None).await;
    let msgs = rt
        .conversation_store()
        .get(&h.id)
        .unwrap()
        .unwrap_or_default();
    assert!(!msgs.is_empty());
    rt.close(&rt.root(), None).await;

    let injected = Arc::new(InMemoryConversationStore::new());
    let as_dyn: Arc<dyn ConversationStore> = injected.clone();
    let rt2 = Runtime::new(Options {
        in_process: Some(mock_llm(CancellationToken::new())),
        registry: test_registry(),
        store: Some(as_dyn.clone()),
        ..Default::default()
    });
    assert!(
        Arc::ptr_eq(&rt2.conversation_store(), &as_dyn),
        "the injected store itself"
    );
}

// ---- in-process option (ADR 0030) -------------------------------------------

struct Overlap {
    in_flight: Mutex<(usize, usize, usize, usize)>, // in-flight, max, overlaps, calls
}

fn scripted_model(hold: Duration) -> (Arc<Overlap>, toolnexus::client::GenerateFn) {
    let o = Arc::new(Overlap {
        in_flight: Mutex::new((0, 0, 0, 0)),
    });
    let oo = o.clone();
    let g: toolnexus::client::GenerateFn = Arc::new(move |req| {
        let o = oo.clone();
        Box::pin(async move {
            {
                let mut g = o.in_flight.lock().unwrap();
                g.0 += 1;
                g.3 += 1;
                if g.0 > g.1 {
                    g.1 = g.0;
                }
                if g.0 > 1 {
                    g.2 += 1;
                }
            }
            if !hold.is_zero() {
                tokio::time::sleep(hold).await;
            }
            o.in_flight.lock().unwrap().0 -= 1;
            Ok(toolnexus::client::InProcessResponse {
                content: format!("ok model={} turn-done", req.model),
                ..Default::default()
            })
        })
    });
    (o, g)
}

fn worker_reg() -> std::collections::HashMap<String, Def> {
    let mut m = std::collections::HashMap::new();
    m.insert(
        "worker".to_string(),
        def("worker", "a scripted worker", "", ""),
    );
    m
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn in_process_shared_generate_top_level_and_sub_agent() {
    let (o, g) = scripted_model(Duration::ZERO);
    let client = toolnexus::client::create_in_process_client(toolnexus::client::InProcessOptions {
        model: "spike-model".into(),
        generate: Some(g.clone()),
        ..Default::default()
    });
    let tk = toolnexus::create_toolkit(Default::default()).await.unwrap();
    let top = client.ask("hello", &tk, "top-level").await.unwrap();
    assert_eq!(top.status, "done");
    let rt = Runtime::new(Options {
        in_process: Some(g),
        registry: worker_reg(),
        ..Default::default()
    });
    let h = rt.spawn(&rt.root(), "worker", None).unwrap();
    rt.wake(&h, "do the thing");
    assert_eq!(rt.wait(&h, None).await.status, "done");
    assert_eq!(o.in_flight.lock().unwrap().3, 2);
}

#[test]
#[should_panic(expected = "mutually exclusive")]
fn new_runtime_in_process_and_http_client_conflict() {
    let (_, g) = scripted_model(Duration::ZERO);
    Runtime::new(Options {
        http_client: Some(reqwest::Client::new()),
        in_process: Some(g),
        ..Default::default()
    });
}

#[test]
#[should_panic(expected = "mutually exclusive")]
fn new_runtime_in_process_and_llm_conflict() {
    let (_, g) = scripted_model(Duration::ZERO);
    Runtime::new(Options {
        llm: Some(toolnexus::agents::LlmOptions {
            base_url: "http://example.invalid".into(),
            ..Default::default()
        }),
        in_process: Some(g),
        ..Default::default()
    });
}

async fn five_workers(cap: usize) -> (Arc<Overlap>, Runtime) {
    let (o, g) = scripted_model(Duration::from_millis(15));
    let rt = Runtime::new(Options {
        in_process: Some(g),
        max_concurrent_turns: cap,
        registry: worker_reg(),
        ..Default::default()
    });
    let mut joins = Vec::new();
    for i in 0..5 {
        let rt = rt.clone();
        joins.push(tokio::spawn(async move {
            let h = rt.spawn(&rt.root(), "worker", None).unwrap();
            rt.wake(&h, &format!("job {i}"));
            rt.wait(&h, None).await
        }));
    }
    for j in joins {
        let r: TaskResult = j.await.unwrap();
        assert_eq!(r.status, "done");
    }
    (o, rt)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn in_process_gate_holds_at_concurrency_one() {
    let (o, rt) = five_workers(1).await;
    assert_eq!(o.in_flight.lock().unwrap().2, 0, "gate FALSIFIED");
    assert_eq!(rt.max_observed_concurrent_turns(), 1);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn in_process_gate_control_would_catch_a_bypass() {
    let (o, _) = five_workers(5).await;
    assert!(
        o.in_flight.lock().unwrap().2 > 0,
        "control INVALID: the detector proves nothing"
    );
}
