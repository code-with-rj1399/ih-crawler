#!/usr/bin/env bash
set -e

BRANCH="$(git branch --show-current)"

if [ "$BRANCH" != "dev-db" ]; then
  echo "Error: run.sh must be executed on dev-db. Current branch: $BRANCH"
  exit 1
fi

if [ ! -f .env ]; then
  echo "Error: .env file not found."
  echo "Create .env with OPENAI_API_KEY=..."
  exit 1
fi

echo "Loading .env..."
set -a
source .env
set +a

echo "Pulling latest dev-db..."
git pull origin dev-db

echo "Stopping containers..."
docker compose down

echo "Starting containers..."
docker compose up --build
