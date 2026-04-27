#!/bin/bash
# Self-play training: Q_LEARNING (learner, exploring) vs Q_LEARNING_FROZEN (stable opponent).
# RED learns with epsilon > 0; BLUE plays greedily from a frozen snapshot that advances
# every 100 games — prevents the degradation cycle caused by two live networks updating
# simultaneously.
# Usage: ./train_self_play.sh [batches] [games_per_batch]
#   batches:         number of training rounds  (default: 20)
#   games_per_batch: games per round            (default: 500)

BASE_URL="http://localhost:8080"
BATCHES=${1:-20}
GAMES=${2:-500}

echo "Self-play training: RED=Q_LEARNING (ε=0.1) vs BLUE=Q_LEARNING_FROZEN: $BATCHES batches x $GAMES games"

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

PLAY_CONFIG=$(cat <<EOF
{
  "timesToPlay": $GAMES,
  "players": [
    {
      "playerType": "COMPUTER",
      "color": "RED",
      "strategy": "Q_LEARNING",
      "params": { "epsilon": 0.1, "trainingTarget": "TERMINAL_OUTCOME" }
    },
    {
      "playerType": "COMPUTER",
      "color": "BLUE",
      "strategy": "Q_LEARNING_FROZEN",
      "params": { "trainingTarget": "TERMINAL_OUTCOME" }
    }
  ],
  "learningModels": ["Q_LEARNING"]
}
EOF
)

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
  echo "--- Batch $i / $BATCHES ---"
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

echo "Self-play training complete."
