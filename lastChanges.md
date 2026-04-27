# Last Changes

## Bug Fix: Internal Server Error on COMPUTER draw after game over

`FranchiseService.computeBestDraw` now checks `state.isEnd()` first and throws
`IllegalArgumentException("Game is already over")` — the existing exception handler
returns a clean 400 instead of a 500.

---

## Feature: Frozen Opponent for Q-Learning Self-Play

Solves the "opponent gets weaker and weaker" problem when training Q_LEARNING vs Q_LEARNING.
The root cause was both players sharing the same live network and updating it simultaneously,
causing strategy cycling and catastrophic forgetting.

The fix mirrors how AlphaZero works: the opponent plays from a **frozen snapshot** of the
network and only advances in discrete jumps.

### Changed files

**`SelfPlayQModelService`**
- `getOrCreateFrozen()` — loads from a separate `...-frozen.json` file; if that file does
  not exist yet, copies the live model as the initial snapshot
- `syncFrozenModel()` — deep-copies the live network and writes it to the frozen file
- `writeToFile()` — extracted shared atomic-write logic used by both save methods

**`SelfPlayQStrategy`**
- Auto-syncs the frozen model every 100 training runs (`FROZEN_SYNC_INTERVAL = 100`)

**`FrozenQStrategy`** (new, `@Component("Q_LEARNING_FROZEN")`)
- Identical move selection to `Q_LEARNING` but reads the frozen snapshot
- Does NOT implement `TrainableStrategy` — never updates any weights

**`franchise.yaml` + `FranchiseController`**
- `Q_LEARNING_FROZEN` added as a valid `ComputerStrategy` in the API
- No epsilon parameter (frozen opponent always plays greedily)

**`SelfPlayQStrategyTest`**
- Fixed pre-existing bug: test was mocking `getPossibleDrawsForState` but the strategy
  calls `getPossibleStrategyDrawsForState`

### How to use

Set up a training run with:
- `RED` → `Q_LEARNING` (learner, trains with epsilon)
- `BLUE` → `Q_LEARNING_FROZEN` (stable opponent, never trains)
- `learningModels: [Q_LEARNING]`

Every 100 games the frozen opponent advances to the latest learner snapshot.
The learner always trains against a stable target, breaking the degradation cycle.
