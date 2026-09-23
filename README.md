# InterviewHQ Crawler

The InterviewHQ crawler (`ih-crawler`) discovers recent software-engineering interview experiences, extracts genuine technical interview questions, and prepares them for ingestion into **hq-API**.

InterviewHQ uses Java 21, Spring Boot, Chromium / Playwright, OpenAI extraction models, AWS DynamoDB, and hq-API as the canonical ingestion boundary.

## Architecture

```
Source Seeds → Crawler → hq-API → AWS DynamoDB
              │
              ├─ discover recent interview experiences
              ├─ fetch pages with rate limiting
              ├─ extract clean page content
              └─ extract technical questions with an LLM
```

The crawler owns discovery and extraction. hq-API owns validation and canonical persistence. The UI reads canonical data through hq-API.

## Documentation

Use the focused documents below as the design and contract references.

- [InterviewHQ System Design](docs/INTERVIEWHQ_DESIGN.md) — overall architecture, services, crawling flow, extraction, and system-level design decisions.
- [DynamoDB Schema](docs/INTERVIEWHQ-DYNAMODB-SCHEMA.md) — single-table design, entities, keys, GSIs, access patterns, ingestion boundary, validation, idempotency, and DynamoDB decisions.
- [Extraction Prompt](src/main/resources/prompts/interview_question_extraction.txt) — complete source-grounded extraction and description contract.

## Crawl Pipeline

```
Enabled Source Seed
        ↓
Discover recent URLs
        ↓
Check previously crawled pages
        ↓
Fetch page
        ↓
Extract page metadata/content
        ↓
LLM question extraction
        ↓
Validate extracted questions
        ↓
Send to hq-API
        ↓
Persist canonical data in DynamoDB
```

The crawler normally targets the previous 24-hour window and should skip already-processed or unchanged pages whenever crawl metadata allows it.

## Question Extraction

The extraction model is not a question generator. It extracts only technical questions explicitly supported by a real interview-experience source.

- `questionText` is the concise question.
- `questionDescription` elaborates the **same question** using source-supported information.
- Description details must not leak from another question.
- Source-provided examples may be used when they improve clarity.
- Examples, constraints, inputs, outputs, and requirements must not be invented.
- Interviewer, candidate, and interview-process language should not appear in the final description.
- DSA, algorithms, LeetCode, and data-structure problems use `CODING`.

## Crawl Efficiency and Anti-Blocking

The crawler is designed to minimize unnecessary requests and reduce aggressive traffic patterns:

- Chromium for sources requiring browser rendering.
- Polite HTTP adapters where appropriate.
- Rate limiting and human-like navigation delays.
- Retry handling for transient `403`, `429`, `5xx`, and challenge responses.
- Jittered exponential backoff.
- Browser-context / user-agent rotation where configured.
- Limited listing-page traversal.
- Page deduplication and crawl-state tracking.
- Model-call caps per source.
- No AI browsing/search tools for URL discovery.

The model receives already-fetched page content rather than being asked to browse the web.

## Running Locally

### Docker

```bash
cp .env.example .env
# Set OPENAI_API_KEY in .env
./run.sh
```

### Spring Boot

```bash
export OPENAI_API_KEY=...
export SPRING_PROFILES_ACTIVE=local
./mvnw spring-boot:run
```

Dev UI: `http://localhost:8090/dev/index.html`

## Configuration

Important crawler configuration lives in `application.yml`.

Typical controls include extraction model, maximum posts per source, maximum extraction tokens, listing-page limits, browser delays, retry/backoff delays, source enablement, and crawl intervals.

Crawler scheduling should remain configuration-driven rather than hardcoded.

## Data Ownership

```
ih-crawler
    │ HTTPS
    ↓
hq-API
    │ validation + persistence
    ↓
AWS DynamoDB
```

The crawler should not construct canonical DynamoDB keys or bypass hq-API for production ingestion.

## Repository Layout

- `src/main/java/.../browser/` — Chromium browser client and browser-level crawling behavior.
- `src/main/java/.../crawl/` — crawl orchestration.
- `src/main/java/.../crawl/discovery/` — interview URL discovery and page-content extraction.
- `src/main/java/.../crawl/adapters/` — source-specific crawl adapters.
- `src/main/java/.../extract/` — LLM-based interview-question extraction.
- `src/main/resources/prompts/` — extraction prompts and source-grounded extraction rules.
- `src/main/resources/application.yml` — crawler and extraction configuration.
- `docs/` — architecture and data-contract documentation.

## Development Notes

When changing crawler behavior:

1. Update the relevant source/configuration.
2. Keep extraction rules source-grounded.
3. Avoid increasing model calls unnecessarily.
4. Preserve rate limiting and retry behavior.
5. Keep crawler and persistence responsibilities separated.
6. Update the relevant documentation when architecture or contracts change.

## Project Status

The crawler is being developed incrementally. Current priorities include reliable source discovery, recent interview-experience crawling, high-quality question extraction, question-description fidelity, deduplication, efficient model usage, hq-API ingestion, DynamoDB persistence, and configurable crawl scheduling.