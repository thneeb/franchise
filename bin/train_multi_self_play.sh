#!/bin/bash
# Trains the main Q_LEARNING model against all city-variant frozen models in rotation.
# Each round cycles through all 22 city variants as opponents.
# Requires city variant models to exist (run init_city_models.sh + train_city_variants.sh first).
# Usage: ./train_multi_self_play.sh [rounds] [games_per_city]
#   rounds:         full cycles through all city opponents  (default: 5)
#   games_per_city: games per city per round                (default: 200)

BASE_URL="http://localhost:8080"
ROUNDS=${1:-5}
GAMES=${2:-200}

CITIES=(
  FLAGSTAFF PUEBLO SALT_LAKE_CITY INDIANAPOLIS RALEIGH
  MONTGOMERY HUNTSVILLE MEMPHIS LITTLE_ROCK
  OGALLALA DODGE_CITY EL_PASO
)

TOTAL_GAMES=$((ROUNDS * GAMES * ${#CITIES[@]}))
echo "Multi-opponent self-play: Q_LEARNING vs ${#CITIES[@]} city frozen models"
echo "Rounds: $ROUNDS | Games/city: $GAMES | Total games: $TOTAL_GAMES"
echo ""

GAME_ID=$(curl -s -X POST "$BASE_URL/franchise" \
  -H "Content-Type: application/json" \
  -d '{"players": ["BLUE", "RED"]}' \
  | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$GAME_ID" ]; then
  echo "ERROR: Failed to create game."
  exit 1
fi

for round in $(seq 1 $ROUNDS); do
  echo "--- Round $round / $ROUNDS ---"
  for CITY in "${CITIES[@]}"; do
    PLAY_CONFIG=$(cat <<EOF
{
  "timesToPlay": $GAMES,
  "players": [
    {
      "playerType": "COMPUTER",
      "color": "RED",
      "strategy": "Q_LEARNING",
      "params": {
        "epsilon": 0.1,
        "trainingTarget": "TERMINAL_OUTCOME"
      }
    },
    {
      "playerType": "COMPUTER",
      "color": "BLUE",
      "strategy": "Q_LEARNING_FROZEN",
      "params": {
        "trainingTarget": "TERMINAL_OUTCOME",
        "modelVariant": "$CITY"
      }
    }
  ],
  "learningModels": ["Q_LEARNING"]
}
EOF
)

    RESULT=$(curl -s -X POST "$BASE_URL/franchise/$GAME_ID/learnings" \
      -H "Content-Type: application/json" \
      -d "$PLAY_CONFIG")

    echo "$RESULT" | python3 -c "
import json, sys
d = json.load(sys.stdin)
wins = {w['color']: w['value'] for w in d.get('wins', [])}
red = wins.get('RED', 0); blue = wins.get('BLUE', 0); total = red + blue
pct = int(100 * red / total) if total > 0 else 0
runs = next((x.get('value', 0) for x in d.get('trainingRuns', []) if 'Q_LEARNING' in x.get('strategy','')), '?')
print(f'  vs {\"$CITY\":<20s}: RED {pct:3d}% | runs: {runs}')
" 2>/dev/null || echo "  vs $CITY: (parse error)"
  done
  echo ""
done

echo "Multi-opponent self-play complete."
