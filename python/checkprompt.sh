#!/usr/bin/env bash
set -euo pipefail


IMAGE_NAME="ih-generate"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "==> Pulling latest $CURRENT_BRANCH from origin..."
CURRENT_BRANCH="$(git -C "$REPO_ROOT" branch --show-current)"
if [ -z "$CURRENT_BRANCH" ]; then
  echo "ERROR: Could not determine current Git branch." >&2
  exit 1
fi
git -C "$REPO_ROOT" pull --ff-only origin "$CURRENT_BRANCH"

echo "==> Building current generate image..."
docker build -f "$REPO_ROOT/python/Dockerfile.generate" -t "$IMAGE_NAME" "$REPO_ROOT/python/"

echo
echo "==> Running extractor against ./data (maximum 5 records)..."
docker run --rm -e OPENAI_API_KEY="${OPENAI_API_KEY:?OPENAI_API_KEY is not set}" -v "$REPO_ROOT/data:/data:ro" "$IMAGE_NAME"

echo
echo "==> Extraction test completed."