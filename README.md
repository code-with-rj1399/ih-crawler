# InterviewHQ crawler (`grok-changes`)

Cost-optimized crawl pipeline: **Chromium discovers and fetches pages. The model only extracts questions from text that was already downloaded.** OpenAI web-search / browsing tools are disabled.

## Why this branch exists

`prompt-dev` was spending money on AI search-tools to find posts and to "open" URLs. That is replaced with:

1. Playwright Chromium opens a source listing like a person would.
2. Deterministic heuristics collect interview-related article URLs (no model).
3. Each article URL is fetched one-by-one with Chromium (human delays, stealth fingerprint).
4. Page text is sent to a cheap extract model (`gpt-5-nano`) with `tools: []`.
5. 403 / 429 / challenge pages retry with 12–40s exponential backoff, UA/viewport rotation, and scroll/mouse jitter so the origin sees a browser, not a bot.

JSON/RSS adapters (Reddit, HN Algolia, RSS) still use polite HTTP first. If the origin blocks with 403, the runner falls back to Chromium.

## Pipeline

```
enabled CrawlSource
        │
        ├─ reddit_json / hn_algolia / rss  → PoliteHttpClient
        │         └─ 403/429 ──► ChromiumSiteCrawler
        │
        └─ html / leetcode_discuss / glassdoor / blind / unknown
                  → ChromiumSiteCrawler
                          │
                          ├─ listing page(s)  (max 3)
                          ├─ InterviewLinkDiscoverer  (keyword + same-host score)
                          └─ fetch each article
                                  │
                                  ▼
                         extractQuestionsFromContent(page text)
                         tools=[], truncated to 12k chars
                         cap = extract-max-posts-per-source
```

## 403 handling

| Layer | Behaviour |
|---|---|
| Chromium | Real Chrome UA, stealth `navigator.webdriver` patch, extra headers, random viewport, scroll + mouse moves, 2.5–8s gap between navigations |
| Chromium retry | Up to 4 retries on 403/429/5xx/challenge pages, 12–40s jittered backoff, UA rotation, fresh browser context |
| HTTP | Chrome UA, 403 is retryable (not only 429/503), longer human backoff before giving up |
| Fallback | Blocked HTTP adapters are retried via Chromium for that source |

## Run locally

```bash
cp .env.example .env   # OPENAI_API_KEY=...
./run.sh               # docker compose: crawler + DynamoDB local
```

Or:

```bash
export OPENAI_API_KEY=...
export SPRING_PROFILES_ACTIVE=local
./mvnw spring-boot:run
```

Dev UI: `http://localhost:8090/dev/index.html`

Profiles `local` / `dev` cap extraction at **1 post per source** so you can test on a free/cheap OpenAI plan.

## Cost knobs (`application.yml`)

- `crawler.extract-model` — default `gpt-5-nano`
- `crawler.extract-max-posts-per-source` — hard cap on model calls per source per run
- `crawler.extract-max-tokens` — 2500
- `crawler.browser.max-listing-pages` — listing pagination bound (default 3)
- `crawler.browser.human-delay-*-ms` / `block-retry-*-ms`

The model is never asked to find URLs, never asked to open a page, and `tools` is sent as an empty array.

## Layout (new code)

- `browser/ChromiumBrowserClient.java` — stealth Chromium fetch + retries
- `crawl/ChromiumSiteCrawler.java` — listing → URLs → articles
- `crawl/discovery/InterviewLinkDiscoverer.java` — interview URL heuristics
- `crawl/discovery/PageContentExtractor.java` — title/body/date from HTML
- `crawl/adapters/HtmlChromiumAdapter.java` — `sourceKind=html` (and unknown kinds)
- `extract/OpenAiQuestionExtractor.java` — extraction only
