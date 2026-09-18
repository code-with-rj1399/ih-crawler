#!/usr/bin/env bash
set -e

BRANCH="$(git branch --show-current)"

if [ -z "$BRANCH" ]; then
  echo "Error: unable to determine current git branch."
  exit 1
fi

if [ ! -f .env ]; then
  echo "Error: .env file not found."
  echo "Create .env with OPENAI_API_KEY=..."
  exit 1
fi

echo "Running branch: $BRANCH"
echo "Loading .env..."
set -a
source .env
set +a

echo "Pulling latest $BRANCH..."
git pull origin "$BRANCH"

echo "Stopping containers..."
docker compose down

echo "Starting containers..."
docker compose up --build
