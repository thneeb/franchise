#!/bin/bash
# Quick benchmark: Q_LEARNING (no training) vs STRATEGIC_Q.
# Use this to check model strength after training rounds.
# Usage: ./test_strategic_q.sh [games] [epsilon]
#   games:   number of games to play  (default: 200)
#   epsilon: exploration rate         (default: 0.2)

BASE_URL="http://localhost:8080"
GAMES=${1:-200}
EPSILON=${2:-0.2}

echo "Benchmark: Q_LEARNING (ε=$EPSILON) vs STRATEGIC_Q — $GAMES games"

GAME_ID=$(curl -s -X POST "$BASE_URL/franchise" \
  -H "Content-Type: application/json" \
  -d '{"players": ["BLUE", "RED"]}' \
  | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$GAME_ID" ]; then
  echo "ERROR: Failed to create game. Is the server running on $BASE_URL?"
  exit 1
fi

RESULT=$(curl -s -X POST "$BASE_URL/franchise/$GAME_ID/learnings" \
  -H "Content-Type: application/json" \
  -d "{
    \"timesToPlay\": $GAMES,
    \"players\": [
      {\"playerType\": \"COMPUTER\", \"color\": \"BLUE\", \"strategy\": \"Q_LEARNING\",
       \"params\": {\"epsilon\": $EPSILON, \"trainingTarget\": \"TERMINAL_OUTCOME\"}},
      {\"playerType\": \"COMPUTER\", \"color\": \"RED\", \"strategy\": \"STRATEGIC_Q\",
       \"params\": {\"trainingTarget\": \"TERMINAL_OUTCOME\"}}
    ],
    \"learningModels\": []
  }")

echo "$RESULT" | python3 -c "
import json, sys
d = json.load(sys.stdin)
wins = {w['color']: w['value'] for w in d.get('wins', [])}
blue = wins.get('BLUE', 0)
red  = wins.get('RED', 0)
total = blue + red
pct_blue = int(100 * blue / total) if total > 0 else 0
pct_red  = int(100 * red  / total) if total > 0 else 0
total_t  = d.get('runtimes', {}).get('totalTime', '?')
print(f'  Q_LEARNING (BLUE): {blue}/{total} ({pct_blue}%)')
print(f'  STRATEGIC_Q (RED): {red}/{total} ({pct_red}%)')
print(f'  Total time: {total_t}')
" 2>/dev/null || echo "$RESULT"
