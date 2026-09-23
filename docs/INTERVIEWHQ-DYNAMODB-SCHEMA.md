# InterviewHQ — DynamoDB Database Schema

**Version:** 1.0  
**Database:** AWS DynamoDB  
**Consumers:** `hq-API`, `ih-crawler`, InterviewHQ UI  
**Architecture:** Java + Spring Boot + AWS DynamoDB + Next.js

## 1. Purpose

InterviewHQ aggregates real-world software engineering interview experiences from multiple sources.

The system has three major services:

1. **UI** — Next.js application used by InterviewHQ users.
2. **hq-API** — Spring Boot backend responsible for serving and ingesting interview data.
3. **ih-crawler** — crawler service responsible for discovering, crawling, and extracting interview questions.

Both `ih-crawler` and `hq-API` use the same DynamoDB database.

The crawler should preferably communicate with DynamoDB through `hq-API` for production ingestion rather than directly modifying canonical production data. This gives the backend a single validation and persistence boundary.

## 2. DynamoDB Strategy

Initial implementation uses one DynamoDB table:

```
interview-hq
```

Primary key:

```
PK — Partition Key
SK — Sort Key
```

This uses a single-table design so related entities can be queried efficiently without relational joins.

## 3. Logical Entities

| Entity | Purpose |
|---|---|
| Question | Extracted technical interview question |
| Experience | Original interview experience/post |
| Source | Website/source from which content is crawled |
| SourceSeed | URL configured for crawling |
| CrawlRun | Execution metadata for a crawler run |
| CrawlPage | Information about a crawled page |
| IngestionEvent | Optional audit/idempotency record |

The first implementation can start with **Question, SourceSeed, CrawlRun, and CrawlPage**.

## 4. Question Entity

Key:

```
PK = QUESTION#{dedupeHash}
SK = ENTITY
```

Example:

```json
{
  "PK": "QUESTION#8a91c7...",
  "SK": "ENTITY",
  "entityType": "Question",
  "questionId": "8a91c7...",
  "questionText": "Design a notification system.",
  "questionDescription": "Design a notification system that can deliver notifications reliably to users through supported channels.",
  "questionType": "System Design",
  "topics": ["Notifications", "Distributed Systems"],
  "company": "Amazon",
  "sourceUrl": "https://example.com/interview/123",
  "problemUrl": null,
  "sourceName": "Example",
  "postedAt": "2026-09-23T10:20:00Z",
  "crawledAt": "2026-09-23T12:00:00Z",
  "confidence": 0.92,
  "questionSpecificity": 0.88,
  "createdAt": "2026-09-23T12:00:00Z",
  "updatedAt": "2026-09-23T12:00:00Z"
}
```

### Deduplication

A deterministic hash should be generated from normalized question identity, for example:

```
SHA-256(
  normalizedCompany +
  normalizedQuestionText +
  normalizedQuestionType
)
```

The exact deduplication algorithm should be centralized in the application layer.

## 5. Canonical Question Types

The database uses these human-readable canonical values:

```
System Design
Coding
Database
LLD
Cloud
Security
DevOps
AI/ML
Data Engineering
Distributed Systems
Networking
Operating Systems
Programming Language
Web Frontend
Mobile
Testing
Technical Concept
```

DSA, algorithm, LeetCode, and data-structure coding problems are stored as **CODING**. Do not introduce a separate DSA type.

## 6. Question Description

`questionDescription` is required at the application-validation layer.

It describes the technical problem itself, not the interview context.

Avoid:

```
The candidate was asked to design a notification system.
```

Prefer:

```
Design a notification system that supports the notification
requirements described in the source.
```

The description must remain faithful to source content and must not invent constraints, algorithms, architecture, scale, APIs, or implementation details.

## 7. Interview Experience Entity

Key:

```
PK = EXPERIENCE#{experienceId}
SK = ENTITY
```

Example:

```json
{
  "PK": "EXPERIENCE#12345",
  "SK": "ENTITY",
  "entityType": "Experience",
  "experienceId": "12345",
  "sourceName": "LeetCode",
  "sourceUrl": "https://leetcode.com/discuss/interview-experience/12345",
  "company": "Google",
  "title": "Google Software Engineer Interview Experience",
  "author": "anonymous",
  "postedAt": "2026-09-23T09:30:00Z",
  "contentHash": "abc123...",
  "crawledAt": "2026-09-23T10:00:00Z",
  "createdAt": "2026-09-23T10:00:00Z",
  "updatedAt": "2026-09-23T10:00:00Z"
}
```

Raw page content should only be retained if required by product requirements and storage/cost considerations.

## 8. Experience → Question Relationship

One experience can produce multiple questions.

Questions may carry:

```
experienceId
```

If efficient retrieval of all questions for an experience becomes a common access pattern, introduce relationship items such as:

```
PK = EXPERIENCE#12345
SK = QUESTION#8a91c7
```

Avoid application-side scans for relationships that can be represented by keys or indexes.

## 9. Source Seed Entity

Crawler seed URLs must be persisted in DynamoDB instead of being permanently hardcoded.

Key:

```
PK = SOURCE#{sourceId}
SK = SEED#{seedId}
```

Example:

```json
{
  "PK": "SOURCE#leetcode",
  "SK": "SEED#001",
  "entityType": "SourceSeed",
  "sourceId": "leetcode",
  "seedId": "001",
  "name": "LeetCode Interview Experiences",
  "url": "https://leetcode.com/discuss/interview-experience/",
  "enabled": true,
  "crawlStrategy": "LISTING",
  "crawlIntervalMinutes": 30,
  "lastCrawledAt": "2026-09-23T18:00:00Z",
  "createdAt": "2026-09-20T10:00:00Z",
  "updatedAt": "2026-09-23T18:00:00Z"
}
```

Seed configuration should support enabling/disabling sources and source-specific crawl configuration.

## 10. Crawl Run Entity

Key:

```
PK = CRAWL_RUN#{runId}
SK = ENTITY
```

Example:

```json
{
  "PK": "CRAWL_RUN#20260923-180000",
  "SK": "ENTITY",
  "entityType": "CrawlRun",
  "runId": "20260923-180000",
  "startedAt": "2026-09-23T18:00:00Z",
  "completedAt": "2026-09-23T18:04:32Z",
  "status": "COMPLETED",
  "sourcesAttempted": 15,
  "pagesDiscovered": 143,
  "pagesCrawled": 121,
  "questionsExtracted": 237,
  "questionsAccepted": 201,
  "questionsRejected": 36,
  "errors": 4
}
```

Statuses:

```
RUNNING
COMPLETED
PARTIAL
FAILED
```

## 11. Crawl Page Entity

The crawler needs to know which pages have already been processed.

Key:

```
PK = PAGE#{urlHash}
SK = ENTITY
```

Example:

```json
{
  "PK": "PAGE#abc123",
  "SK": "ENTITY",
  "entityType": "CrawlPage",
  "url": "https://example.com/interview/123",
  "urlHash": "abc123",
  "sourceId": "example",
  "httpStatus": 200,
  "contentHash": "content123",
  "lastCrawledAt": "2026-09-23T18:02:00Z",
  "firstSeenAt": "2026-09-22T18:02:00Z",
  "lastModifiedAt": null,
  "crawlStatus": "SUCCESS"
}
```

This supports skipping unchanged pages and reducing unnecessary requests.

## 12. Global Secondary Indexes

Initial question access patterns require a small number of GSIs.

### GSI 1 — Questions by company

```
GSI1PK = COMPANY#{company}
GSI1SK = {postedAt}#{questionId}
```

Supports recent questions for a company.

### GSI 2 — Questions by type

```
GSI2PK = TYPE#{questionType}
GSI2SK = {postedAt}#{questionId}
```

Supports recent questions by category such as `System Design` or `Coding`.

### GSI 3 — Questions by source

```
GSI3PK = SOURCE#{sourceName}
GSI3SK = {postedAt}#{questionId}
```

Supports questions extracted from a specific source.

## 13. Core Access Patterns

| Access pattern | Key/index |
|---|---|
| Get a question | `PK = QUESTION#{id}` |
| Get recent company questions | GSI1 |
| Get recent questions by type | GSI2 |
| Get questions by source | GSI3 |
| Check crawled page | `PK = PAGE#{urlHash}` |
| Get crawl run | `PK = CRAWL_RUN#{runId}` |
| Get source seeds | `PK = SOURCE#{sourceId}` |

## 14. 24-Hour Crawling

The crawler workflow should be:

```
Source Seed
    ↓
Discover recent URLs
    ↓
Check CrawlPage
    ↓
Skip already-processed/unchanged pages
    ↓
Fetch page
    ↓
Extract page content
    ↓
LLM question extraction
    ↓
Application validation
    ↓
POST to hq-API
    ↓
Persist to DynamoDB
```

The crawler should use source-supported publication timestamps where available. It must not invent timestamps when the source does not provide reliable evidence.

## 15. Crawler → hq-API Ingestion

Production ingestion boundary:

```
ih-crawler
    |
    | POST /api/v1/questions/ingest
    v
hq-API
    |
    | validation
    | normalization
    | deduplication
    | conditional writes
    v
DynamoDB
```

The crawler should not need to know DynamoDB key construction.

The backend owns canonical persistence rules.

## 16. Suggested Ingestion API

Endpoint:

```
POST /api/v1/questions/ingest
```

Example:

```json
{
  "source": {
    "name": "LeetCode",
    "url": "https://leetcode.com/discuss/interview-experience/123"
  },
  "experience": {
    "title": "Google Interview Experience",
    "postedAt": "2026-09-23T10:20:00Z",
    "author": "anonymous"
  },
  "questions": [
    {
      "questionText": "Design a notification system.",
      "questionDescription": "Design a notification system that supports reliable delivery of notifications.",
      "questionType": "SYSTEM_DESIGN",
      "topics": ["Notifications"],
      "problemUrl": null,
      "confidence": 0.92,
      "questionSpecificity": 0.86
    }
  ]
}
```

## 17. Server-Side Validation

Even though the crawler uses an LLM, hq-API must enforce the contract.

Required validation:

```
questionText != null
questionText != blank

questionDescription != null
questionDescription != blank

questionType ∈ canonical enum

confidence >= 0
confidence <= 1

questionSpecificity >= 0
questionSpecificity <= 1
```

Invalid records should be rejected or quarantined rather than persisted as canonical questions.

## 18. Problem URL

`problemUrl` is nullable.

Populate it only when the source explicitly contains the exact URL associated with the technical problem.

Never:

- construct a URL;
- search for a problem URL;
- infer a problem URL;
- copy the interview experience URL into `problemUrl`.

## 19. Idempotency

Crawler ingestion must be idempotent.

Deterministic identifiers should be used for pages and questions.

For example:

```
pageId = hash(canonicalUrl)

questionId = hash(normalizedQuestionIdentity)
```

The API should use DynamoDB conditional writes so concurrent crawler processes cannot accidentally create duplicate canonical records.

## 20. TTL

Questions should not have TTL because they are permanent product data.

Initial recommendation:

| Entity | TTL |
|---|---|
| Question | No |
| Experience | No |
| SourceSeed | No |
| CrawlRun | Optional later |
| CrawlPage | Optional later |
| Temporary ingestion records | Optional |

Retention requirements should be established before enabling TTL.

## 21. Capacity Mode

Start with DynamoDB **PAY_PER_REQUEST** capacity mode.

This keeps the initial deployment simple while crawler volume and UI traffic are still being established.

Capacity can be revisited after observing production usage.

## 22. Consistency

Use eventual consistency for normal read paths unless strong consistency is specifically required.

Use conditional writes for ingestion, deduplication, and state transitions.

Examples:

```
Question listing      → Eventually consistent
Question lookup      → Eventually consistent
Idempotent ingestion → Conditional write
State transitions    → Conditional write
```

## 23. Security

The crawler should not have unrestricted production DynamoDB credentials.

Preferred production boundary:

```
Crawler
   ↓ HTTPS
API Gateway / Load Balancer
   ↓
hq-API
   ↓ IAM Role
DynamoDB
```

The crawler authenticates to hq-API, while hq-API accesses DynamoDB through an AWS IAM role.

## 24. Local Development

Local:

```
ih-crawler
    ↓
hq-API
    ↓
DynamoDB Local
```

Production:

```
ih-crawler
    ↓
hq-API
    ↓
AWS DynamoDB
```

The DynamoDB endpoint should be configuration-driven so local and production deployments use the same application code.

## 25. Logical Table Layout

```
interview-hq
│
├── QUESTION#<hash>
│      └── ENTITY
│
├── EXPERIENCE#<id>
│      └── ENTITY
│
├── SOURCE#<source>
│      └── SEED#<id>
│
├── CRAWL_RUN#<runId>
│      └── ENTITY
│
└── PAGE#<urlHash>
       └── ENTITY
```

## 26. Implementation Phases

### Phase 1

Implement:

- Question
- SourceSeed
- CrawlPage
- CrawlRun
- hq-API ingestion endpoint
- DynamoDB persistence
- Idempotent writes

### Phase 2

Add:

- Experience
- Experience → Question relationship
- Source health
- Crawl metrics

### Phase 3

Add:

- Search-oriented indexes
- Analytics
- Advanced source monitoring
- Historical crawl analytics

## 27. Architecture Decision

The intended ownership boundary is:

```
Crawler → discovers and extracts
hq-API  → validates and persists
DynamoDB → stores canonical data
UI      → reads through hq-API
```

This keeps data acquisition, persistence, and presentation independently scalable while maintaining one canonical persistence boundary.
