# InterviewHQ — DynamoDB Database Schema

**Database:** AWS DynamoDB  
**Consumers:** `hq-API`, `ih-crawler`, InterviewHQ UI  
**Architecture:** Java + Spring Boot + AWS DynamoDB + Next.js

## 1. Canonical model

An interview experience and its questions are separate entities.

```text
InterviewExperience
    |
    +-- Question
    +-- Question
    +-- Question
```

Do **not** duplicate experience metadata on every question.

The UI access pattern is deliberately experience-first:

```text
GET /dev/api/experiences
        |
        v
list of experiences only
        |
        | user clicks an experience
        v
GET /dev/api/experiences/{experienceId}/questions
```

Questions are therefore loaded only when an experience is opened.

## 2. InterviewExperience entity

Logical fields:

```text
id
sourceId
sourcePlatform
title
summary
author
postedAt
originalPostUrl
company
role
level
location
candidateYoE
questionCount
dedupeHash
createdAt
```

DynamoDB key:

```text
PK = EXPERIENCE#{dedupeHash}
SK = ENTITY
```

`dedupeHash` is generated from the normalized source platform and canonical post URL.

Example:

```json
{
  "pk": "EXPERIENCE#<hash>",
  "sk": "ENTITY",
  "entityType": "InterviewExperience",
  "data": {
    "id": 123,
    "sourcePlatform": "LeetCode Discuss",
    "title": "Backend | 3-5 years | PhonePe",
    "summary": "...",
    "author": "Anonymous",
    "postedAt": "2026-09-24T14:58:28Z",
    "originalPostUrl": "https://leetcode.com/discuss/post/...",
    "company": "PhonePe",
    "role": "Backend Engineer",
    "level": "3-5 years",
    "candidateYoE": 3.0,
    "questionCount": 2,
    "dedupeHash": "<hash>",
    "createdAt": "2026-09-25T18:18:40Z"
  }
}
```

## 3. InterviewQuestion entity

Question records contain only question-specific data plus the relationship to their experience.

Logical fields:

```text
id
experienceId
problemUrl
questionType[]
difficulty
questionText
questionDescription
candidateApproach
confidence
questionGranularity
modelName
dedupeHash
extractedAt
createdAt
```

Experience fields such as company, title, summary, author, location, and source URL do **not** belong here.

DynamoDB key:

```text
PK = EXPERIENCE#{experienceId}
SK = QUESTION#{questionDedupeHash}
```

This makes the primary UI query a native DynamoDB `Query` rather than a table scan.

Example:

```json
{
  "pk": "EXPERIENCE#123",
  "sk": "QUESTION#<hash>",
  "entityType": "InterviewQuestion",
  "data": {
    "id": 101,
    "experienceId": 123,
    "questionText": "Design and implement a logger library",
    "questionDescription": "...",
    "questionType": ["LLD", "Coding"],
    "difficulty": null,
    "problemUrl": null,
    "confidence": 0.54,
    "questionGranularity": 0.35,
    "modelName": "gpt-5-nano",
    "dedupeHash": "<hash>",
    "extractedAt": "2026-09-25T18:18:40Z",
    "createdAt": "2026-09-25T18:18:40Z"
  }
}
```

`questionType` is always an array in the canonical application model.

## 4. Relationship and access pattern

The relationship is represented by the question partition key:

```text
EXPERIENCE#123
    |
    +-- QUESTION#abc
    +-- QUESTION#def
    +-- QUESTION#ghi
```

The application must not load every question and group them in memory to render the experience list.

### List experiences

```text
InterviewExperienceRepository.findAll()
```

This returns experience entities only.

### Get questions for one experience

```text
InterviewQuestionRepository.findByExperienceId(experienceId)
```

This performs a DynamoDB query on:

```text
PK = EXPERIENCE#{experienceId}
```

## 5. Extraction ownership

Step 1 extracts:

```text
Experience metadata
+
plain question candidates
```

Step 2 extracts:

```text
questionText
questionType[]
difficulty
questionDescription
problemUrl
questionGranularity
confidence
```

Persistence then performs:

```text
Step 1
  ↓
InterviewExperience
  ↓
Step 2 metadata
  ↓
InterviewQuestion records linked by experienceId
```

The LLM extractor does not need to know DynamoDB keys.

## 6. Re-extraction behavior

When an existing post is crawled again:

1. Find the experience using `experienceDedupeHash(sourcePlatform, canonicalPostUrl)`.
2. Update the experience metadata.
3. Delete the existing questions for that experience.
4. Persist the current valid Step-2 questions.
5. Update `questionCount`.

This prevents stale questions from remaining when a source post changes.

## 7. Canonical question types

Allowed values are exactly:

```text
Coding
Database
System Design
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

No separate `topics` field is part of the canonical question persistence model.

## 8. DynamoDB repository behavior

The shared DynamoDB table uses:

```text
pk — partition key
sk — sort key
entityType
 data — serialized entity payload
```

Repository scans are filtered by `entityType`, so an `InterviewExperience` scan cannot accidentally deserialize questions, posts, or other entities as experiences.

Queries are also filtered by entity type after partition selection.

## 9. Current development API

### Experiences

```http
GET /dev/api/experiences
```

Returns experience records only.

### Questions for an experience

```http
GET /dev/api/experiences/{experienceId}/questions
```

Returns questions belonging to that experience only.

The development dashboard follows the same pattern: it loads experiences first and fetches questions when the user expands an experience.

## 10. Production ownership

The intended production boundary remains:

```text
ih-crawler
    ↓
hq-API
    ↓
DynamoDB
    ↑
InterviewHQ UI
```

The crawler should eventually send canonical experience/question payloads to `hq-API`; the backend owns validation, persistence, and DynamoDB key construction.
