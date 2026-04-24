#!/bin/bash
# Backs up the current Q-learning model files so a fresh pre-training run can start clean.
# Run this BEFORE restarting the server for pre-training.
# Usage: ./backup_model.sh

MODEL_DIR="src/main/resources/q-learning"
BACKUP_DIR="$MODEL_DIR/backup"
mkdir -p "$BACKUP_DIR"

backed_up=0
for f in "$MODEL_DIR"/*.json; do
  [ -f "$f" ] || continue
  DEST="$BACKUP_DIR/$(basename "$f" .json)_$(date +%Y%m%d_%H%M%S).json"
  cp "$f" "$DEST"
  echo "Backed up: $f → $DEST"
  backed_up=$((backed_up + 1))
done

if [ "$backed_up" -eq 0 ]; then
  echo "No model files found in $MODEL_DIR."
else
  echo ""
  echo "Backup complete. You can now delete the originals and restart the server for a clean pre-training run."
  echo "To delete:  rm $MODEL_DIR/*.json"
fi
