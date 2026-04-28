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

## Human Play-Test Game Analysis (game b1b5cad6)

RED (human) 75 : BLUE (Q_LEARNING) 60 — human wins by 15.

### Two critical AI mistakes identified

**1. Little Rock → Houston miss**
BLUE entered LITTLE_ROCK on draw 6 (the natural gateway to the Texas corridor),
but then played JACKSONVILLE + Atlanta increase on draw 10 instead of extending
to HOUSTON. RED took HOUSTON unopposed on draw 15. Result: RED owned the entire
Deep South (Houston, Dallas, Oklahoma City, El Paso) while BLUE had no foothold.

Root cause: the model scores cities in isolation and does not see that
LITTLE_ROCK → HOUSTON → DALLAS is a connected strategic chain worth
claiming as a unit. It preferred contesting an already-safe eastern city
over opening a new region.

**2. Atlanta increase too early (draws 6 + 10)**
BLUE increased in ATLANTA when it already had exclusive presence across the
entire Southeast (Montgomery, Huntsville, Atlanta) with zero RED presence.
ATLANTA is odd-sized (5 slots): BLUE wins the city as long as RED cannot reach
majority — and RED had no path there. Both increase actions were wasted turns
that should have been the Houston extension.

Root cause: the model increases where it already leads instead of extending
where the opponent is about to dominate.

### Proposed STRATEGIC_Q improvements (future)

- **Regional chain rule:** when a player holds a city adjacent to an uncontested
  region gateway (e.g. LITTLE_ROCK → HOUSTON), prefer extending into that
  gateway before the opponent can.
- **Safe-city increase guard:** do not increase in a city/region where no
  opponent can realistically reach majority within 2–3 turns.

---

## City-Variant Training (in progress)

**Motivation:** the 95% ceiling against a single STRATEGIC_Q opponent means the
model has saturated learning against one fixed style. Different starting cities
produce fundamentally different game graphs. Training a separate model for each
of the 22 size-1 cities, then doing self-play against all of them as frozen
opponents, exposes the main model to a much wider distribution of game openings.

**New infrastructure:**
- `STRATEGIC_Q` accepts optional `startCity` param — always opens in that city
- `Q_LEARNING` / `Q_LEARNING_FROZEN` accept optional `modelVariant` param —
  loads/saves a city-suffixed model file (e.g. `…-INDIANAPOLIS.json`)
- `bin/init_city_models.sh` — copies base model to all 22 city variants (done)
- `bin/train_city_variants.sh` — trains each variant vs STRATEGIC_Q(startCity=X)
- `bin/train_multi_self_play.sh` — trains main model vs all 22 frozen variants

**Training pipeline:**
```
bin/init_city_models.sh             ✓ done (22 variants created from 150k base)
bin/train_city_variants.sh 10 500   ✓ done (each variant: 150k → 155k runs)
bin/train_multi_self_play.sh 5 200  ✓ done (main model: 150k → 162k runs)
```

**City-variant win rates** (Q_LEARNING RED vs STRATEGIC_Q with startCity, final batch):

| City | Win% | | City | Win% |
|---|---|---|---|---|
| LITTLE_ROCK | 76% | hardest | EL_PASO | 91% | easiest |
| MONTGOMERY | 78% | | SALT_LAKE_CITY | 90% | |
| MEMPHIS | 78% | | FLAGSTAFF | 83% | |
| RALEIGH | 81% | | HUNTSVILLE | 85% | |

Southeast/central starts (LITTLE_ROCK, MONTGOMERY) are the toughest opponents —
confirms the game analysis: a southeastern gateway start is the hardest position to beat.

**Benchmark after multi-self-play:** Q_LEARNING 91% vs STRATEGIC_Q (was 95% before).
Small regression — city-variant opponents were weaker than STRATEGIC_Q, diluting the
STRATEGIC_Q-specific adaptation. The model now handles more diverse openings.

Note: blocked-region cities (LAS_VEGAS, RENO, SPOKANE, BOISE, POCATELLO, CONRAD,
BILLINGS, CASPER, FARGO, SIOUX_FALLS) were included in the first run but have been
removed from all scripts — irrelevant for 2-player games.
City variant model files are gitignored (regenerable via training scripts).
