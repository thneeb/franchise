#!/bin/bash
# Trains Q_LEARNING against STRATEGIC_Q in configurable batches.
# Epsilon decays each batch from START_EPSILON down to MIN_EPSILON.
# Q_LEARNING alternates sides (BLUE/RED) each batch so the network
# learns from both starting positions.
# Usage: ./train_q_learning.sh [batches] [games_per_batch] [start_epsilon]
#   batches:         number of training rounds  (default: 20)
#   games_per_batch: games per round            (default: 500)
#   start_epsilon:   initial exploration rate   (default: 0.2)

BASE_URL="http://localhost:8080"
BATCHES=${1:-20}
GAMES=${2:-500}
START_EPSILON=${3:-0.2}
MIN_EPSILON=0.02
EPSILON_DECAY=0.01

echo "Training Q_LEARNING vs STRATEGIC_Q: $BATCHES batches x $GAMES games"
echo "  ε: $START_EPSILON → $MIN_EPSILON (decay $EPSILON_DECAY/batch), alternating sides"

GAME_ID=$(curl -s -X POST "$BASE_URL/franchise" \
  -H "Content-Type: application/json" \
  -d '{"players": ["BLUE", "RED"]}' \
  | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$GAME_ID" ]; then
  echo "ERROR: Failed to create game. Is the server running on $BASE_URL?"
  exit 1
fi

echo "Game ID: $GAME_ID"
echo ""

poll_progress() {
  while kill -0 "$1" 2>/dev/null; do
    PROG=$(curl -s "$BASE_URL/franchise/$GAME_ID/learnings")
    LINE=$(echo "$PROG" | python3 -c "
import json, sys
d = json.load(sys.stdin)
c = d.get('gamesCompleted', 0)
t = d.get('gamesTotal', 0)
pct = int(100 * c / t) if t > 0 else 0
wins = ' | '.join(f\"{x.get('color','?')}: {x.get('value',0)} wins\" for x in d.get('wins', []))
print(f'  [{pct:3d}%] Game {c}/{t} | {wins}', end='')
" 2>/dev/null)
    printf "\r%s" "$LINE"
    sleep 2
  done
  printf "\n"
}

for i in $(seq 1 $BATCHES); do
  # Decay epsilon, clamp to MIN_EPSILON
  EPSILON=$(python3 -c "e=round($START_EPSILON - ($i-1)*$EPSILON_DECAY, 4); print(max($MIN_EPSILON, e))")

  # Alternate sides: odd batches Q_LEARNING=BLUE, even batches Q_LEARNING=RED
  if [ $((i % 2)) -eq 1 ]; then
    Q_COLOR="BLUE"; S_COLOR="RED"
  else
    Q_COLOR="RED";  S_COLOR="BLUE"
  fi

  echo "--- Batch $i / $BATCHES | ε=$EPSILON | Q_LEARNING=$Q_COLOR ---"

  PLAY_CONFIG=$(cat <<EOF
{
  "timesToPlay": $GAMES,
  "players": [
    {
      "playerType": "COMPUTER",
      "color": "$Q_COLOR",
      "strategy": "Q_LEARNING",
      "params": { "epsilon": $EPSILON, "trainingTarget": "TERMINAL_OUTCOME" }
    },
    {
      "playerType": "COMPUTER",
      "color": "$S_COLOR",
      "strategy": "STRATEGIC_Q",
      "params": { "trainingTarget": "TERMINAL_OUTCOME" }
    }
  ],
  "learningModels": ["Q_LEARNING"]
}
EOF
)

  RESULT_FILE=$(mktemp)
  curl -s -X POST "$BASE_URL/franchise/$GAME_ID/learnings" \
    -H "Content-Type: application/json" \
    -d "$PLAY_CONFIG" > "$RESULT_FILE" &
  CURL_PID=$!

  poll_progress $CURL_PID
  wait $CURL_PID

  echo "$RESULT_FILE" | xargs cat | python3 -c "
import json, sys
d = json.load(sys.stdin)
wins = ' | '.join(f\"{x.get('color','?')}: {x.get('value',0)}\" for x in d.get('wins', []))
runtimes = d.get('runtimes', {})
total = runtimes.get('totalTime', '?')
training = runtimes.get('trainingTime', '?')
runs = ', '.join(f\"{x.get('strategy','?')}: {x.get('value',0)} runs\" for x in d.get('trainingRuns', []))
print(f'  Result: {wins} | total: {total} | training: {training} | {runs}')
" 2>/dev/null || cat "$RESULT_FILE"
  rm -f "$RESULT_FILE"
  echo ""
done

echo "Training complete."
