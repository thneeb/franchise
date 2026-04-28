# Last Changes

## New Rule: AvoidGivingOpponentRegionClosureRule (STRATEGIC_Q)

Prevents moves that reduce any open region to exactly **one remaining
city slot**, which would let the opponent close it on their very next turn
and claim the region track bonus. Moves that close the region themselves
(zero slots remaining) are kept — closing is good.

---

## New Rule: SecureWinningRegionsLateRule (STRATEGIC_Q)

**Trigger:** ≥ 6 of the 10 regions are closed (reliable late-game signal).

When the endgame is reached, prefer **increase** moves in regions where
the player strictly leads in branch count. Covers both open *and* closed
regions, because increasing in a closed region still generates income → influence.

### Bug history

Two bugs were found and fixed during game analysis:

1. **Wrong trigger (original):** the rule measured null city slots in
   unclosed regions. Regions close when every *city* in them has been
   entered (extended to), **not** when all city slots are filled. The three
   never-entered regions (CALIFORNIA, UPPER_WEST, MONTANA) had no entries
   in `getCityBranches` → counted 0 open slots → the rule always fired
   but found no player presence there → permanent no-op during the real
   endgame.

2. **Wrong scope (original):** the rule only searched *open* regions for a
   leading position. After 6+ regions closed, the player may lead in closed
   regions (e.g. FLORIDA, TEXAS for BLUE) with many unfilled city slots.
   Increases there boost income and are exactly what a trailing player needs.

---

## Model Training

Q_LEARNING trained in three runs (20 batches × 500 games each, ε 0.2 → 0.02,
alternating sides) against the updated STRATEGIC_Q opponent:

| Checkpoint | Training runs | Benchmark vs STRATEGIC_Q | Notes |
|---|---|---|---|
| Before this session | 110 000 | 73 % | — |
| After run 1 | 120 000 | — | vs STRATEGIC_Q with AvoidGivingOpponentRegionClosureRule |
| After run 2 | 130 000 | — | vs STRATEGIC_Q with SecureWinningRegionsLateRule (original, buggy) |
| After run 3 | 140 000 | **95 %** | vs STRATEGIC_Q with SecureWinningRegionsLateRule (fixed) |

Model backed up: `src/main/resources/q-learning/backup/*_20260428_084325.json`

---

## Rule Order in STRATEGIC_Q (final)

```
1. AvoidSkipWhenMovesAvailableRule
2. UseExtensionBonusTileEarlyRule      (rounds 1–5)
3. AvoidIncreaseInSafeCityRule
4. SecureWinningRegionsLateRule        (≥6 regions closed)
5. PreferRegionLeadExtensionRule
6. ContestOpponentRegionRule
7. AvoidExpensiveExtensionRule         (connection cost ≥ 8)
8. AvoidGivingOpponentRegionClosureRule
```

Rule 4 is placed **before** the extension-preference rules (5, 6, 7) so
that increase candidates are still in scope when the late-game filter fires.

---

## Self-Play Training (run 1)

20 batches × 500 games, RED=Q_LEARNING (ε=0.1) vs BLUE=Q_LEARNING_FROZEN.
Model reached **150 000 runs**. Benchmark vs STRATEGIC_Q: **94%** (flat vs 95% before).

Self-play trains the model against itself and did not improve the STRATEGIC_Q benchmark —
the 95% ceiling is likely the upper limit of what supervised-opponent training can achieve.

Model backed up: `src/main/resources/q-learning/backup/*_20260428_085856.json`

---

## Next Steps

- Investigate whether further STRATEGIC_Q improvements can break the ceiling
- Or accept 95% and use the model as-is for human play testing
