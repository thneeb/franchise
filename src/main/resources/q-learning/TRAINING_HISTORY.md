# Q-Learning Training History — 2-Player TERMINAL_OUTCOME Model

Model file: `q-learning-model-2p-terminal-outcome-d099-v1.json`

---

## Reset — Fresh start (2026-04-24)

All previous model versions (including the 987,661-run Phase 6 model) were discarded.
The `target/classes/` classpath fallback caused earlier "fresh start" attempts to load the stale model.
Correct reset procedure: delete model from `src/main/resources/q-learning/` **and** run `mvn clean` before restarting.

---

## Phase 1 — Behavioral cloning (2026-04-24)
- **Script:** `./bin/pretrain_minimax.sh 20 3000` (+ 12-batch top-up)
- **Setup:** MINIMAX (depth=1, `learn=true`) as BLUE vs RANDOM as RED
- **Games:** 32 batches × 3,000 = **96,000 games** (first 20 via script, 12 top-up; fresh model received 20 effective batches = 60,000 runs due to mid-run model replacement)
- **Runs:** 0 → **60,000**
- **Win rate:** MINIMAX won ~83% consistently (expected vs RANDOM)
- **Goal:** Seed the network with MINIMAX-level intuition before RL fine-tuning.

---

## Phase 2 — RL fine-tuning round 1 (2026-04-24)
- **Script:** `./bin/train_q_learning.sh 20 500`
- **Setup:** Q_LEARNING (ε=0.3, TERMINAL_OUTCOME) as BLUE vs STRATEGIC_Q as RED
- **Games:** 20 batches × 500 = **10,000 games**
- **Runs:** 60,000 → **70,000**
- **Win rate trend:** 76–78% early, dropped to 52–60% mid, settled 60–75% in final batches.

---

## Phase 3 — RL fine-tuning round 2 (2026-04-24)
- **Script:** `./bin/train_q_learning.sh 20 500`
- **Setup:** Q_LEARNING (ε=0.3, TERMINAL_OUTCOME) as BLUE vs STRATEGIC_Q as RED
- **Games:** 20 batches × 500 = **10,000 games**
- **Runs:** 70,000 → **80,000**
- **Win rate trend:** 58–82% early, stabilised 50–74% in later batches.
- **Benchmark (ε=0.3):** Q_LEARNING 58% / STRATEGIC_Q 42% — strong result with only 80k runs.

---

## Summary

| Phase | Method | Games | Runs after |
|-------|--------|-------|------------|
| Reset | Model deleted + mvn clean | — | 0 |
| 1 | Behavioral cloning (MINIMAX vs RANDOM) | 60,000 | 60,000 |
| 2 | RL vs STRATEGIC_Q | 10,000 | 70,000 |
| 3 | RL vs STRATEGIC_Q | 10,000 | 80,000 |
| **Total (current model)** | | **80,000** | **80,000** |

---

## Lessons learned

- **Self-play causes mode collapse** (Phases 4+5 from prior history): both players reinforce each other's bad habits, overwriting the MINIMAX-learned expansion intuition. Do not use self-play.
- **Classpath fallback**: Spring Boot copies resources to `target/classes/` at compile time. Deleting from `src/main/resources/` is not enough for a true reset — always run `mvn clean` too.
- **Benchmark with ε=0.3**, not ε=0: greedy Q_LEARNING (ε=0) always beats STRATEGIC_Q 100% because both use the same model and STRATEGIC_Q's rules can only filter out the top Q-score move, never improve on it.
- **More training ≠ better**: the previous model regressed badly after 1M+ runs. Stop at ~80–100k runs and evaluate before continuing.

---

## Next training options

### Option A — More RL vs STRATEGIC_Q
- **Pro:** Stable, grounded opponent; prevents catastrophic forgetting.
- **Con:** Diminishing returns; STRATEGIC_Q can't adapt back.
- **Use when:** Win rate is still noisy and needs stabilisation.

### Option B — Alternating schedule
Run a round of RL vs STRATEGIC_Q, benchmark, repeat only if still improving.
- Best safeguard against regression: stop as soon as benchmark plateaus.
