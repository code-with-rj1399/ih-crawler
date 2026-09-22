# InterviewHQ — System Design Document

## 1. Overview

InterviewHQ is a platform that aggregates software engineering interview experiences and coding questions from multiple online sources such as Reddit, LeetCode, Hacker News, blogs, and developer communities.

The system continuously discovers new content, extracts genuine interview questions using an LLM, normalizes the extracted information, removes duplicates, and makes the resulting questions available through a web application.

The primary goal is to provide engineers with a centralized, searchable source of recently reported interview questions.

## 2. Problem Statement

Interview experiences are distributed across many platforms. A candidate may need to search Reddit, LeetCode Discuss, Hacker News, engineering blogs, developer communities, and company-specific forums.

The same interview question may also appear multiple times with different wording.

InterviewHQ solves this by:
1. Discovering relevant content automatically.
2. Filtering content based on publication time.
3. Extracting actual interview questions from noisy content.
4. Identifying company, role, round, topic, and difficulty.
5. Deduplicating questions.
6. Storing normalized questions.
7. Providing a searchable interface.

## 3. High-Level Architecture

    Web Client
        |
        v
    API Layer - Spring Boot
        |
        v
    Question Store - DynamoDB

    Crawl Pipeline
        |
        +--> Scheduler
        |
        +--> Source Registry
        |
        +--> Source Adapter
               +--> Reddit
               +--> LeetCode
               +--> Hacker News
               +--> RSS
               +--> HTML/Web
               +--> Other sources
        |
        +--> Parsed Entries
        |
        +--> Publication Date Filter
        |
        +--> LLM Extraction
        |
        +--> Normalization + Deduplication
        |
        +--> Question Store

## 4. Core Components

### Source Registry

The source registry contains configuration for every supported source.

Typical fields:
- slug
- name
- URL
- sourceKind
- enabled
- rateLimit
- delay
- parserConfig
- status

This allows sources to be enabled or disabled without changing the crawler architecture.

## 5. Source Adapter Architecture

Every source implements a common adapter contract:

    kind()
    crawl(source, lookback, fetcher)

The crawler does not need to understand how a particular website works.

The boundary is:

    CrawlSource
        |
        v
    SourceAdapter
        |
        v
    ParsedEntry

This provides isolation between source-specific crawling logic.

## 6. Source-Specific Crawling

Different platforms have different access mechanisms.

InterviewHQ should not force every platform through the same crawler implementation.

Examples:

    Reddit
      -> Reddit Adapter
      -> Reddit-specific backend

    LeetCode
      -> LeetCode Adapter
      -> GraphQL

    RSS
      -> RSS Adapter
      -> RSS feed

    Generic website
      -> HTML Adapter
      -> HTTP / Chromium

The source adapter is responsible for selecting the appropriate mechanism.

## 7. ParsedEntry

All source-specific implementations eventually produce a common internal representation containing:

- URL
- canonical URL
- external ID
- title
- author
- publication timestamp
- company
- role
- body text
- content type
- HTTP status
- ETag
- content hash
- robots status

This creates a clean boundary between crawling and question extraction.

## 8. Crawl Pipeline

    Source
      |
      v
    Discovery
      |
      v
    Fetch
      |
      v
    Parse
      |
      v
    Publication Date Extraction
      |
      v
    Hard Freshness Filter
      |
      v
    LLM Extraction
      |
      v
    Normalization
      |
      v
    Deduplication
      |
      v
    Persistence

## 9. Discovery

Discovery identifies potentially relevant content.

Examples:

Reddit:
    Search Reddit
        -> Interview-related posts

LeetCode:
    Interview Discuss
        -> Recent posts

RSS:
    RSS Feed
        -> New entries

Generic websites:
    Seed URL
        -> Extract links
        -> Visit relevant pages

Discovery should intentionally be broader than final eligibility. A discovered article is only a candidate.

## 10. Publication Date Is a Hard Gate

The crawler must distinguish between discovery date and content publication date.

A post may appear recently because someone commented on it, it was indexed again, it was edited, an algorithm surfaced it, or the crawler discovered it late.

Therefore:

    discovered recently != published recently

The crawler uses the actual publication timestamp.

For a 48-hour lookback:

    Current time: 20 Sep 10:00
    Cutoff:       18 Sep 10:00

Anything older than the cutoff is rejected.

## 11. Missing Publication Date

A source may not provide a reliable publication timestamp.

The default policy should be conservative:

    publishedAt == null
        -> reject

This prevents old content from entering the LLM pipeline simply because its date could not be verified.

## 12. LLM Extraction

Only content that passes the hard freshness gate should reach the LLM.

    Candidate
        |
        v
    Publication Date Gate
        |
        +--> OLD -> Reject
        |
        +--> UNKNOWN -> Reject
        |
        +--> RECENT
               |
               v
              LLM

The LLM extracts structured interview information such as company, role, round, questions, topics, and difficulty.

## 13. Extraction Responsibilities

The LLM should determine:

- Company
- Role
- Interview round
- Actual technical question
- Topic
- Difficulty

Example rounds:
- Coding
- DSA
- System Design
- Machine Coding
- Behavioral
- Managerial

## 14. LLM Should Not Determine Freshness

The LLM should not be responsible for deciding whether content is recent.

Preferred design:

    Crawler
      |
      v
    Timestamp extraction
      |
      v
    Deterministic freshness filter
      |
      v
    LLM

This makes freshness deterministic and cheaper.

## 15. Deduplication

The same interview question can appear in multiple sources.

InterviewHQ should perform multiple levels of deduplication.

### Level 1 — URL deduplication

Normalize URLs and deduplicate canonical URLs.

### Level 2 — Content hash

Hash normalized source content using a stable hash such as SHA-256.

### Level 3 — Question hash

Normalize extracted question text and calculate a question hash.

A future semantic deduplication layer can identify questions with different wording that represent the same underlying question.

## 16. Storage

DynamoDB is used as the primary persistence layer.

Conceptual InterviewQuestion fields:

- id
- company
- role
- round
- question
- topic
- difficulty
- source
- sourceUrl
- publishedAt
- createdAt
- contentHash
- questionHash

Source information should be retained so users can inspect the original interview experience.

## 17. API Layer

The Spring Boot service exposes APIs for the frontend.

Examples:

    GET /api/questions
    GET /api/questions/{id}
    GET /api/companies
    GET /api/topics

Filtering can include:
- company
- role
- round
- topic
- difficulty
- date

## 18. Frontend

The frontend provides:

### Home
Latest Interview Questions

### Company page
Company
- SDE-1
- SDE-2
- Senior

### Question page
- Question
- Company
- Role
- Round
- Difficulty
- Topic
- Source
- Published date

### Search
Users can search by company, question, topic, role, and round.

## 19. Crawler Scheduling

The crawler should periodically execute.

    Every 1 hour
        |
        v
    Load enabled sources
        |
        v
    Crawl concurrently
        |
        v
    Apply freshness filter
        |
        v
    Extract questions
        |
        v
    Persist

Each source should have its own rate limit, request delay, timeout, and retry policy.

## 20. Polite Fetching

The fetch layer should support:
- request delays
- rate limiting
- timeouts
- retries
- robots policy
- user-agent configuration
- exponential backoff

## 21. Browser Crawling

Some websites cannot be reliably crawled using simple HTTP.

For these sources:

    HTTP fetch
        |
        v
    blocked / insufficient content
        |
        v
    Chromium
        |
        v
    rendered page

Playwright/Chromium provides support for JavaScript-heavy pages.

Browser crawling should not automatically become the default because it is more expensive in CPU, memory, and execution time.

## 22. External Backends

Some platforms require specialized tooling.

The architecture therefore allows:

    Source Adapter
        |
        v
    Specialized Backend

Examples:

    Reddit     -> Reddit-specific backend
    GitHub     -> GitHub API / gh
    YouTube    -> YouTube-specific extraction
    RSS        -> RSS parser
    LeetCode   -> GraphQL
    Generic web -> HTTP / Chromium

The key principle is to use the simplest reliable backend for each source rather than introducing one crawler technology for every platform.

## 23. Failure Handling

One source should not bring down the entire crawl.

    Reddit    -> FAILED
    LeetCode  -> SUCCESS
    HN        -> SUCCESS
    RSS       -> SUCCESS

The crawler records the failure and continues processing other sources.

## 24. Retry Strategy

Transient failures should be retried:
- HTTP 429
- HTTP 502
- HTTP 503
- Network timeout

Use exponential backoff:

    Attempt 1 -> 1 sec
    Attempt 2 -> 2 sec
    Attempt 3 -> 4 sec

Permanent failures should not be repeatedly retried, such as HTTP 404, invalid configuration, or unsupported sources.

## 25. Observability

Each crawl should produce metrics such as:

- sources_started
- sources_succeeded
- sources_failed
- entries_discovered
- entries_fetched
- entries_rejected_old
- entries_rejected_undated
- llm_requests
- llm_failures
- questions_extracted
- questions_deduplicated
- questions_saved

This makes it possible to understand why the platform produced fewer questions on a particular day.

## 26. Cost Optimization

The most expensive stage is generally LLM extraction.

Prefer:

    1000 discovered posts
        |
        v
    freshness filter
        |
        v
    100 eligible posts
        |
        v
    LLM

instead of sending all 1000 posts to the LLM.

Deterministic filters should happen before LLM processing wherever possible.

## 27. Development Mode

Development mode should minimize external traffic and cost.

Example:

    DEV
    2 URLs/source
    small LLM batch
    short lookback
    verbose logs

Production can use larger source limits, longer lookback windows, scheduled crawling, and normal logging.

## 28. Security

Secrets must never be stored in source code.

Examples:
- OPENAI_API_KEY
- AWS credentials
- session cookies
- external service credentials

should be supplied through environment variables, AWS Secrets Manager, or IAM roles.

For production AWS deployment, IAM roles should be preferred over static AWS access keys.

## 29. Deployment

An initial production deployment can use:

    Internet
       |
       v
      ALB
       |
       v
    EC2 / Container
    Spring Boot
       |
       +--> DynamoDB
       |
       +--> LLM Provider

The crawler can initially run in the same application.

As traffic grows, crawling can be separated from the API service.

## 30. Future Architecture

At larger scale:

    Scheduler
       |
       v
    Crawl Queue
       |
       +--> Reddit Worker
       +--> LeetCode Worker
       +--> HN Worker
       |
       v
    Parsed Entries
       |
       v
    Freshness Filter
       |
       v
    Extraction Queue
       |
       v
    LLM Workers
       |
       v
    Deduplication
       |
       v
    DynamoDB
       |
       v
    API
       |
       v
    Frontend

This allows crawler workloads and API traffic to scale independently.

## 31. Incremental Implementation Strategy

InterviewHQ should be developed source by source rather than integrating every external crawling technology simultaneously.

### Phase 1 — LeetCode

Validate:
- discovery
- publication timestamp
- extraction
- deduplication

### Phase 2 — Reddit

Validate the Reddit-specific backend independently.

### Phase 3 — Hacker News and RSS

### Phase 4 — Generic websites and blogs

### Phase 5 — Additional platforms

For every new source:

    Source
      |
      v
    Adapter
      |
      v
    Discovery
      |
      v
    Fetch
      |
      v
    Parse
      |
      v
    Freshness
      |
      v
    LLM
      |
      v
    Dedup
      |
      v
    Storage

Only after one source is stable should the next source be integrated.

## 32. Key Design Principles

1. **Source isolation** — A change to Reddit should not affect LeetCode.
2. **Deterministic freshness** — Publication-date filtering happens before LLM extraction.
3. **Common internal model** — Every source produces ParsedEntry.
4. **Specialized backends where necessary** — Use the appropriate access mechanism for each platform.
5. **Independent failure** — One source failure must not stop the entire crawler.
6. **Optimize before LLM** — Filter aggressively before invoking the LLM.
7. **Incremental integration** — Add and validate one source at a time.
8. **Preserve provenance** — Every extracted question should retain its original source URL and publication information.

## 33. End-to-End Example

A Reddit post is discovered:

    Reddit
      |
      v
    "Amazon SDE-2 Interview Experience"
      |
      v
    Reddit Adapter
      |
      v
    ParsedEntry
      |
      v
    Publication timestamp
      |
      v
    Freshness Filter
      |
      v
    LLM Extraction
      |
      v
    Amazon / SDE-2 / System Design
      |
      v
    "Design a distributed rate limiter"
      |
      v
    Question Hash
      |
      v
    DynamoDB
      |
      v
    InterviewHQ API
      |
      v
    Frontend

The user can then search for:

    Amazon + SDE-2 + System Design

and find the extracted question together with its original source.

## 34. Conclusion

InterviewHQ is designed as a source-agnostic interview-question aggregation platform with source-specific crawling implementations.

The critical architectural boundary is:

    Source-specific crawling
              |
              v
         ParsedEntry
              |
              v
    Common processing pipeline
              |
              v
    Structured interview question

This allows InterviewHQ to support different platforms without coupling the entire system to a single crawling technology.

The initial implementation should remain deliberately simple: stabilize one source end-to-end, measure its behavior, and then add the next source using the same adapter contract.
