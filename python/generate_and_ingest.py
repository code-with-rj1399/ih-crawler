#!/usr/bin/env python3
import argparse
import glob
import hashlib
import json
import os
from typing import Any

from openai import OpenAI

MODEL = os.getenv("OPENAI_MODEL", "gpt-5-nano")
INPUT_LIMIT = int(os.getenv("INPUT_LIMIT", "5"))
MAX_CONTENT_CHARS = int(os.getenv("MAX_CONTENT_CHARS", "30000"))

client = OpenAI(api_key=os.environ["OPENAI_API_KEY"])


QUESTION_DISCOVERY_PROMPT = r"""
You are InterviewHQ's QUESTION DISCOVERY engine.

Your ONLY job is to find technical questions/problems that were ACTUALLY
asked or given to the candidate in the supplied interview-experience source.

You are NOT a metadata extractor, question generator, LeetCode classifier,
or solution writer. You are NOT allowed to use outside knowledge.

SOURCE
------
platform: {source_platform}
url: {post_url}
title: {title}
author: {author}
published_at: {published_at}

CONTENT
-------
{page_content}
-------

AUTHENTICITY GATE
-----------------
Accept only a personal interview/assessment experience describing the
author's actual interview, assessment, hiring loop, coding round,
system-design round, LLD round, etc.

Reject the entire source if it is primarily:
- interview preparation
- "top N interview questions"
- question banks
- tutorials/courses
- generic interview advice
- commonly asked question collections
- hypothetical examples that were not reported as asked

Do not reject merely because the post is informal, short, or incomplete.

QUESTION EVIDENCE
-----------------
A question must have evidence in the source that it was actually asked.

Strong evidence:
- "I was asked..."
- "they asked me..."
- "the interviewer asked..."
- "coding round: ..."
- "system design: ..."
- "LLD round: ..."
- "question was..."
- "given an array..."
- "design X..."
- "implement X..."
- a clearly described sequence of questions from the author's own round

A technology mention is NOT a question.
A project discussion is NOT automatically a question.
"Discussed Kafka" is NOT a question.
"Interviewer asked me to design a Kafka-based notification system" IS a question.

EXTRACT THE QUESTION, NOT THE STORY
------------------------------------
Return the smallest faithful representation of the actual problem.

Good:
- "Design a calendar."
- "Find the longest substring without repeating characters."
- "Design a notification system."
- "Implement an LRU cache."

Bad:
- "In the second round the interviewer asked me to..."
- "I explained my approach and then they asked..."
- "This was a difficult system design question..."

Do not turn a short source statement into a more elaborate problem.

ANTI-HALLUCINATION
------------------
Every extracted question MUST be supported by a specific span of the source.

If you cannot point to source evidence, do not extract it.

Never infer a canonical LeetCode/GFG/HackerRank problem merely because the
description resembles one.

If the source explicitly names a known problem, preserve that name.

If the source says "variation of Two Sum with negative numbers", preserve
that meaning. Do not silently convert it to generic "Two Sum".

MULTIPLE QUESTIONS
------------------
Extract every distinct actual question separately.
Do not merge separate questions.
Do not split one problem into artificial subquestions.

SOURCE EVIDENCE
---------------
For every candidate return a short verbatim evidence quote copied from the
source, maximum 300 characters. This is quality-control evidence only.

COMPANY
-------
Return the company only when the source supports it. Never guess it from
technology, URL, role, or author.

OUTPUT
------
Return ONLY JSON matching the supplied schema.

Each candidate contains:
- questionText
- evidence
- confidence

Do not output descriptions, solutions, difficulty, topics, candidate approach,
or other metadata.
"""

ENRICHMENT_PROMPT = r"""
You are InterviewHQ's QUESTION QUALITY + METADATA engine.

Stage 1 already found candidate interview questions.

Your job is to:
1. validate every candidate against the ORIGINAL SOURCE,
2. remove false positives,
3. produce a high-quality questionText,
4. write a faithful questionDescription,
5. extract supported metadata.

YOU MUST NOT INVENT QUESTIONS.

ORIGINAL SOURCE
---------------
platform: {source_platform}
url: {post_url}
title: {title}
author: {author}
published_at: {published_at}

SOURCE CONTENT
--------------
{page_content}

STAGE 1 CANDIDATES
------------------
{candidates}

NON-NEGOTIABLE RULE
-------------------
A candidate may survive ONLY if the original source contains evidence that
this exact technical problem/question was part of the author's actual
interview/assessment.

If unsupported, DROP it.

Do not use general programming knowledge to fill missing information.

QUESTION TEXT
-------------
questionText is the canonical short display form for InterviewHQ.

Rules:
- one concise sentence where possible
- usually <= 140 characters
- describe the actual problem/prompt
- preserve important constraints explicitly stated in the source
- remove interview-story wording
- remove "they asked me", "I was asked", "in the interview", etc.
- do NOT add "(Coding)", "(System Design)", "(Product)", etc.
- do NOT add unstated scale, APIs, architecture, requirements, constraints,
  examples, algorithms, data structures, or business goals
- do NOT convert a descriptive problem into a guessed canonical platform name
- if the source explicitly names the problem, preserve that name

Examples:
Source: "They asked me to design a calendar."
=> "Design a calendar."

Source: "The coding question was a variation of Two Sum with negative numbers."
=> "Two Sum with negative numbers"

Source: "They asked: Given an array, find the maximum sum subarray."
=> "Find the maximum-sum subarray in an array."

QUESTION DESCRIPTION
--------------------
Write a concise problem statement explaining what a reader actually had to
solve.

Include ONLY details explicitly present in the source:
- requirements
- constraints
- inputs/outputs
- edge cases
- functional behaviour
- explicitly stated follow-ups
- explicitly stated interviewer requirements

Do NOT invent traffic, scale, APIs, storage, latency, availability,
data structures, algorithms, examples, constraints, or acceptance criteria.

Do NOT describe the candidate's solution in the problem description.

If the source contains only "Design a calendar", keep the description short.
Do NOT manufacture a full calendar-system specification.

The description and questionText MUST represent the same problem.

METADATA
--------
Extract only what the source supports:
company, role, level, location, candidateYoE, outcome, roundType,
questionType, difficulty, candidateApproach, problemUrl, postDate, topics,
confidence.

questionType must be one of:
CODING, SYSTEM_DESIGN, LOW_LEVEL_DESIGN, BEHAVIORAL, TECHNICAL, DATABASE,
DEVOPS, AI_ML, OTHER

Use null for unsupported values.

candidateApproach must contain only the candidate's explicitly described
approach. Do not solve the problem yourself.

difficulty:
Only set Easy/Medium/Hard when the source explicitly states it or gives
strong direct evidence. Do NOT infer difficulty from the problem type.

problemUrl:
Use ONLY a URL that appears in the supplied source and directly corresponds
to this problem. Never construct or search for one.

postDate:
Use the supplied publication timestamp converted to UTC date when available.

CONFIDENCE
----------
Confidence is confidence in the FINAL extracted record, not how common the
problem is.

OUTPUT
------
Return ONLY the JSON defined by the schema.
"""

DISCOVERY_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "isInterviewExperience": {"type": "boolean"},
        "company": {"type": ["string", "null"]},
        "questions": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "questionText": {"type": "string"},
                    "evidence": {"type": "string"},
                    "confidence": {"type": "number"},
                },
                "required": ["questionText", "evidence", "confidence"],
            },
        },
    },
    "required": ["isInterviewExperience", "company", "questions"],
}

ENRICHMENT_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "questions": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "company": {"type": ["string", "null"]},
                    "questionText": {"type": ["string", "null"]},
                    "questionDescription": {"type": ["string", "null"]},
                    "questionType": {"type": ["string", "null"]},
                    "difficulty": {"type": ["string", "null"]},
                    "candidateApproach": {"type": ["string", "null"]},
                    "candidateYoE": {"type": ["number", "null"]},
                    "problemUrl": {"type": ["string", "null"]},
                    "postDate": {"type": ["string", "null"]},
                    "role": {"type": ["string", "null"]},
                    "level": {"type": ["string", "null"]},
                    "location": {"type": ["string", "null"]},
                    "outcome": {"type": ["string", "null"]},
                    "roundType": {"type": ["string", "null"]},
                    "topics": {"type": "array", "items": {"type": "string"}},
                    "confidence": {"type": "number"},
                },
                "required": [
                    "company", "questionText", "questionDescription",
                    "questionType", "difficulty", "candidateApproach",
                    "candidateYoE", "problemUrl", "postDate", "role", "level",
                    "location", "outcome", "roundType", "topics", "confidence"
                ],
            },
        }
    },
    "required": ["questions"],
}


def load_records(directory: str, limit: int) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for path in sorted(glob.glob(os.path.join(directory, "*.json"))):
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            items = data if isinstance(data, list) else [data]
            for item in items:
                if isinstance(item, dict):
                    records.append(item)
                    if len(records) >= limit:
                        return records
        except Exception as e:
            print(f"[WARN] {path}: {e}")
    return records


def get_source_fields(record: dict[str, Any]) -> dict[str, str]:
    source_platform = record.get("platform") or record.get("source") or record.get("sourcePlatform") or ""
    post_url = record.get("url") or record.get("sourceUrl") or record.get("originalPostUrl") or ""
    title = record.get("title") or ""
    author = record.get("author") or record.get("postedBy") or ""
    published_at = record.get("publishedAt") or record.get("postDate") or record.get("postedAt") or ""
    page_content = (
        record.get("pageContent")
        or record.get("content")
        or record.get("text")
        or json.dumps(record, ensure_ascii=False)
    )
    page_content = str(page_content)
    if len(page_content) > MAX_CONTENT_CHARS:
        page_content = page_content[:MAX_CONTENT_CHARS]
    return {
        "source_platform": str(source_platform),
        "post_url": str(post_url),
        "title": str(title),
        "author": str(author),
        "published_at": str(published_at),
        "page_content": page_content,
    }


def call_json(prompt: str, schema: dict[str, Any], schema_name: str, max_output_tokens: int) -> dict[str, Any]:
    response = client.responses.create(
        model=MODEL,
        input=prompt,
        max_output_tokens=max_output_tokens,
        text={
            "format": {
                "type": "json_schema",
                "name": schema_name,
                "strict": True,
                "schema": schema,
            }
        },
    )
    text = (response.output_text or "").strip()
    if not text:
        raise ValueError("OpenAI returned an empty output")
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON from OpenAI: {text[:1000]}") from exc


def discover_questions(fields: dict[str, str]) -> dict[str, Any]:
    return call_json(
        QUESTION_DISCOVERY_PROMPT.format(**fields),
        DISCOVERY_SCHEMA,
        "interview_question_discovery",
        2500,
    )


def enrich_questions(fields: dict[str, str], discovery: dict[str, Any]) -> dict[str, Any]:
    candidates = json.dumps(discovery, ensure_ascii=False, indent=2)
    prompt = ENRICHMENT_PROMPT.format(**fields, candidates=candidates)
    return call_json(
        prompt,
        ENRICHMENT_SCHEMA,
        "interview_question_enrichment",
        4500,
    )


def normalize_payload(payload: dict[str, Any]) -> dict[str, Any]:
    clean_questions: list[dict[str, Any]] = []
    for q in payload.get("questions") or []:
        if not isinstance(q, dict):
            continue

        question_text = (q.get("questionText") or "").strip()
        company = (q.get("company") or "").strip()

        if not question_text or not company:
            continue

        q["questionText"] = question_text
        q["company"] = company

        if q.get("questionDescription"):
            q["questionDescription"] = q["questionDescription"].strip()

        clean_questions.append(q)

    return {"questions": clean_questions}


def extract(record: dict[str, Any]) -> dict[str, Any]:
    fields = get_source_fields(record)

    discovery = discover_questions(fields)
    candidates = discovery.get("questions") or []

    print(
        f"[STAGE-1] interview={discovery.get('isInterviewExperience')} "
        f"company={discovery.get('company')} candidates={len(candidates)}"
    )

    if not discovery.get("isInterviewExperience") or not candidates:
        return {"questions": []}

    enriched = enrich_questions(fields, discovery)
    result = normalize_payload(enriched)

    print(f"[STAGE-2] final_questions={len(result['questions'])}")
    return result


def experience_id(payload: dict[str, Any]) -> str:
    value = payload.get("sourceUrl") or json.dumps(payload, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def print_payload(payload: dict[str, Any]) -> None:
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default=os.getenv("INPUT_DIR", "/data"))
    parser.add_argument("--limit", type=int, default=INPUT_LIMIT)
    args = parser.parse_args()

    limit = max(1, args.limit)
    records = load_records(args.input, limit)

    print(f"Loaded {len(records)} records (limit={limit}, model={MODEL}, two-stage=true)")

    for i, record in enumerate(records, 1):
        try:
            payload = extract(record)
            print_payload(payload)
            print(
                f"[OK] {i}/{len(records)} generated: "
                f"{len(payload.get('questions', []))} questions"
            )
        except Exception as e:
            print(f"[ERROR] {i}/{len(records)}: {e}")


if __name__ == "__main__":
    main()
