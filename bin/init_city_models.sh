#!/bin/bash
# Copies the base Q_LEARNING model to city-specific variant files for all size-1 cities.
# Run once before city-variant training. Skips cities that already have a model file.
# Usage: ./init_city_models.sh

MODEL_DIR="src/main/resources/q-learning"
BASE="$MODEL_DIR/q-learning-model-2p-terminal-outcome-d099-v1.json"

CITIES=(
  LAS_VEGAS RENO FLAGSTAFF PUEBLO SALT_LAKE_CITY
  SPOKANE BOISE POCATELLO CONRAD BILLINGS CASPER
  FARGO SIOUX_FALLS INDIANAPOLIS RALEIGH
  MONTGOMERY HUNTSVILLE MEMPHIS LITTLE_ROCK
  OGALLALA DODGE_CITY EL_PASO
)

if [ ! -f "$BASE" ]; then
  echo "ERROR: Base model not found: $BASE"
  exit 1
fi

echo "Initializing city-specific Q-learning models from: $BASE"
echo ""
created=0
skipped=0
for CITY in "${CITIES[@]}"; do
  TARGET="$MODEL_DIR/q-learning-model-2p-terminal-outcome-d099-v1-${CITY}.json"
  if [ -f "$TARGET" ]; then
    echo "  EXISTS:   $CITY"
    skipped=$((skipped + 1))
  else
    cp "$BASE" "$TARGET"
    echo "  CREATED:  $CITY"
    created=$((created + 1))
  fi
done

echo ""
echo "Done. Created: $created  Skipped (already exist): $skipped"
