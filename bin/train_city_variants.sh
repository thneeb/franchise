#!/bin/bash
# Trains a city-specific Q_LEARNING model for each size-1 city against STRATEGIC_Q
# that always opens in that city. Run init_city_models.sh first.
# Usage: ./train_city_variants.sh [batches] [games_per_batch]
#   batches:         training rounds per city  (default: 10)
#   games_per_batch: games per round           (default: 500)

BASE_URL="http://localhost:8080"
BATCHES=${1:-10}
GAMES=${2:-500}

CITIES=(
  LAS_VEGAS RENO FLAGSTAFF PUEBLO SALT_LAKE_CITY
  SPOKANE BOISE POCATELLO CONRAD BILLINGS CASPER
  FARGO SIOUX_FALLS INDIANAPOLIS RALEIGH
  MONTGOMERY HUNTSVILLE MEMPHIS LITTLE_ROCK
  OGALLALA DODGE_CITY EL_PASO
)

echo "City-variant training: STRATEGIC_Q(startCity) vs Q_LEARNING(modelVariant)"
echo "Batches: $BATCHES x $GAMES games per city | Cities: ${#CITIES[@]}"
echo ""

for CITY in "${CITIES[@]}"; do
  echo "=== $CITY ==="

  GAME_ID=$(curl -s -X POST "$BASE_URL/franchise" \
    -H "Content-Type: application/json" \
    -d '{"players": ["BLUE", "RED"]}' \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

  if [ -z "$GAME_ID" ]; then
    echo "  ERROR: Failed to create game — skipping $CITY"
    continue
  fi

  PLAY_CONFIG=$(cat <<EOF
{
  "timesToPlay": $GAMES,
  "players": [
    {
      "playerType": "COMPUTER",
      "color": "BLUE",
      "strategy": "STRATEGIC_Q",
      "params": {
        "trainingTarget": "TERMINAL_OUTCOME",
        "startCity": "$CITY"
      }
    },
    {
      "playerType": "COMPUTER",
      "color": "RED",
      "strategy": "Q_LEARNING",
      "params": {
        "epsilon": 0.1,
        "trainingTarget": "TERMINAL_OUTCOME",
        "modelVariant": "$CITY"
      }
    }
  ],
  "learningModels": ["Q_LEARNING"]
}
EOF
)

  for i in $(seq 1 $BATCHES); do
    RESULT=$(curl -s -X POST "$BASE_URL/franchise/$GAME_ID/learnings" \
      -H "Content-Type: application/json" \
      -d "$PLAY_CONFIG")

    echo "$RESULT" | python3 -c "
import json, sys
d = json.load(sys.stdin)
wins = {w['color']: w['value'] for w in d.get('wins', [])}
blue = wins.get('BLUE', 0); red = wins.get('RED', 0); total = blue + red
pct = int(100 * red / total) if total > 0 else 0
runs = next((x.get('value', 0) for x in d.get('trainingRuns', []) if 'Q_LEARNING' in x.get('strategy','')), '?')
t = d.get('runtimes', {}).get('totalTime', '?')
print(f'  Batch $i/$BATCHES | Q_LEARNING(RED): {pct}% | runs: {runs} | {t}')
" 2>/dev/null || echo "  Batch $i/$BATCHES: (parse error)"
  done
  echo ""
done

echo "City-variant training complete."
