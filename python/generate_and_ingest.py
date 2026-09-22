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


# ---------------------------------------------------------------------------
# IMPORTANT DESIGN:
# Do NOT ask one model call to simultaneously:
#   1. decide whether this is a real interview experience,
#   2. find questions,
#   3. rewrite questions,
#   4. write descriptions,
#   5. infer metadata.
#
# That produced low-quality/question-invention behaviour in the previous
# prompt. We deliberately split extraction into two independent stages.
#
# Stage 1 = high-recall question discovery.
# Stage 2 = high-precision normalization + metadata enrichment.
#
# Stage 2 receives the original source as evidence and the Stage-1 candidates.
# It is explicitly forbidden from inventing a question that Stage 1 did not
# identify.
# ---------------------------------------------------------------------------


QUESTION_DISCOVERY_PROMPT = r"""
You are InterviewHQ's QUESTION DISCOVERY engine.

Your ONLY job is to find technical questions/problems that were ACTUALLY
asked or given to the candidate in the supplied interview-experience source.

You are NOT a metadata extractor.
You are NOT a question generator.
You are NOT a LeetCode classifier.
You are NOT allowed to use outside knowledge.

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

STEP 1 — AUTHENTICITY
Determine whether the source is a personal interview/assessment experience.

Accept only content that describes the author's/candidate's actual interview,
assessment, hiring loop, coding round, system-design round, LLD round, etc.

REJECT the entire source if it is primarily:
- an interview-preparation article
- a "top N interview questions" article
- a question bank
- a tutorial
- a course/study guide
- generic interview advice
- a list of commonly asked questions
- a company interview-question collection with no first-person/actual
  interview evidence
- hypothetical questions/examples that were not reported as asked

Do not reject merely because the post is informal, short, poorly written, or
contains incomplete interview details.

STEP 2 — FIND ACTUAL QUESTIONS
Look for explicit evidence that a question/problem was asked.

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

STEP 3 — EXTRACT THE QUESTION, NOT THE STORY
For each real question, identify the smallest faithful representation of the
actual problem.

GOOD:
- "Design a calendar."
- "Find the longest substring without repeating characters."
- "Design a notification system."
- "Implement an LRU cache."
- "Given an array, find two numbers that sum to a target."

BAD:
- "In the second round the interviewer asked me to..."
- "I explained my approach and then they asked..."
- "This was a difficult system design question..."
- "They asked a variation of..."

Do NOT turn a short source statement into a more elaborate problem.

CRITICAL ANTI-HALLUCINATION RULE
--------------------------------
The extracted question MUST be supported by a specific span of the supplied
content.

If you cannot point to source evidence for the question, DO NOT extract it.

Never infer a canonical LeetCode/GFG/HackerRank problem merely because the
description resembles one.

If the source explicitly names a known problem, preserve that name.

If the source says "variation of Two Sum with negative numbers", preserve
that meaning. Do not silently convert it to generic "Two Sum".

MULTIPLE QUESTIONS
------------------
Extract every distinct actual question separately.

Do NOT merge:
"Design Uber" and "Design payment service"

Do NOT split one problem into artificial subquestions just because it contains
multiple requirements.

SOURCE EVIDENCE
---------------
For every candidate return a short verbatim evidence quote copied from the
source (maximum 300 characters) that proves the question was actually asked.

The evidence is for internal quality control and MUST NOT be used to invent
missing details.

COMPANY
-------
Only reject for missing company if the source genuinely cannot identify the
company involved in the interview. Never guess the company from the URL,
technology, role, or author.

OUTPUT
------
Return ONLY JSON matching the supplied schema.

For each candidate:
- questionText: concise actual question/problem
- evidence: exact source excerpt proving it
- confidence: confidence that this was an actual asked/given question

Do not output descriptions.
Do not output solutions.
Do not output difficulty.
Do not output topics.
Do not output candidate approach.
"""


ENRICHMENT_PROMPT = r"""
You are InterviewHQ's QUESTION QUALITY + METADATA engine.

Stage 1 already found candidate interview questions.

Your job is to:
1. validate each candidate against the ORIGINAL SOURCE,
2. remove false positives,
3. produce a high-quality questionText,
4. write a faithful questionDescription,
5. extract supported metadata.

You MUST NOT invent questions.

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

If a candidate is not supported by the original source, DROP it.

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
- do NOT add unstated scale, APIs, architecture, requirements, constraints,
  examples, algorithms, data structures, or business goals
- do NOT append "(Coding)", "(System Design)", "(Product)", etc.
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
This is where useful source detail belongs.

Write a concise problem statement that explains what a reader actually had
to solve.

Include ONLY details explicitly present in the source:
- requirements
- constraints
- inputs/outputs
- edge cases
- functional behaviour
- explicitly stated follow-ups
- explicitly stated interviewer requirements

Do NOT invent:
- traffic
- scale
- APIs
- storage
- latency
- availability
- data structures
- algorithms
- examples
- constraints
- acceptance criteria

Do NOT describe the candidate's solution in the problem description.

If the source contains only "Design a calendar", the description should remain
short. Do NOT manufacture a full calendar-system specification.

The description and questionText MUST represent the same problem.

METADATA
--------
Extract only what the source supports:
- company
- role
- level
- location
- candidateYoE
- outcome
- roundType
- questionType
- difficulty
- candidateApproach
- problemUrl
- postDate
- topics
- confidence

questionType must be one of:
CODING, SYSTEM_DESIGN, LOW_LEVEL_DESIGN, BEHAVIORAL, TECHNICAL, DATABASE,
DEVOPS, AI_ML, OTHER

Use null for unsupported values.

candidateApproach must contain only the candidate's explicitly described
approach. Do not solve the problem yourself.

difficulty:
Only set Easy/Medium/Hard when the source explicitly states it or gives
strong direct evidence. Do NOT infer difficulty merely from the problem type.

problemUrl:
Use ONLY a URL that appears in the supplied source and directly corresponds
to this problem. Never construct or search for one.

postDate:
Use the supplied publication timestamp converted to UTC date when available.

CONFIDENCE
----------
confidence is the confidence in the FINAL extracted record, not confidence
in how common/standard the problem is.

A high confidence score requires:
- actual interview evidence
- clear problem statement
- metadata supported by source
- no invented details

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
                    "topics": {
                        "type": "array",
                        "items": {"type": "string"},
                    },
                    "confidence": {"type": "number"},
                },
                "required": [
                    "company",
                    "questionText",
                    "questionDescription",
                    "questionType",
                    "difficulty",
                    "candidateApproach",
                    "candidateYoE",
                    "problemUrl",
                    "postDate",
                    "role",
                    "level",
                    "location",
                    "outcome",
                    "roundType",
                    "topics",
                    "confidence",
                ],
            },
        }
    },
    "required": ["questions"],
}


def load_records(directory: str) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for path in sorted(glob.glob(os.path.join(directory, "*.json"))):
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)

            items = data if isinstance(data, list) else [data]
            for item in items:
                if isinstance(item, dict):
                    records.append(item)
                    if len(records) >= INPUT_LIMIT:
                        return records
        except Exception as e:
            print(f"[WARN] {path}: {e}")

    return records


def get_source_fields(record: dict[str, Any]) -> dict[str, str]:
    source_platform = (
        record.get("platform")
        or record.get("source")
        or record.get("sourcePlatform")
        or ""
    )
    post_url = (
        record.get("url")
        or record.get("sourceUrl")
        or record.get("originalPostUrl")
        or ""
    )
    title = record.get("title") or ""
    author = record.get("author") or record.get("postedBy") or ""
    published_at = (
        record.get("publishedAt")
        or record.get("postDate")
        or record.get("postedAt")
        or ""
    )
    page_content = (
        record.get("pageContent")
        or record.get("content")
        or record.get("text")
        or json.dumps(record, ensure_ascii=False)
    )

    # Keep enough context for good extraction, but avoid sending absurdly large
    # scraped pages to the model.
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


def call_json(
    *,
    prompt: str,
    schema: dict[str, Any],
    schema_name: str,
    max_output_tokens: int,
) -> dict[str, Any]:
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
    prompt = QUESTION_DISCOVERY_PROMPT.format(**fields)
    return call_json(
        prompt=prompt,
        schema=DISCOVERY_SCHEMA,
        schema_name="interview_question_discovery",
        max_output_tokens=2500,
    )


def enrich_questions(
    fields: dict[str, str], discovery: dict[str, Any]
) -> dict[str, Any]:
    candidates = json.dumps(discovery, ensure_ascii=False, indent=2)
    prompt = ENRICHMENT_PROMPT.format(
        **fields,
        candidates=candidates,
    )
    return call_json(
        prompt=prompt,
        schema=ENRICHMENT_SCHEMA,
        schema_name="interview_question_enrichment",
        max_output_tokens=4500,
    )


def normalize_payload(payload: dict[str, Any], fields: dict[str, str]) -> dict[str, Any]:
    questions = payload.get("questions") or []
    clean_questions: list[dict[str, Any]] = []

    for q in questions:
        if not isinstance(q, dict):
            continue

        question_text = (q.get("questionText") or "").strip()
        company = (q.get("company") or "").strip()

        # Hard quality gates. The model is not allowed to manufacture a
        # company/question just to satisfy the schema.
        if not question_text:
            continue
        if not company:
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

    print(
        f"[STAGE-1] interview={discovery.get('isInterviewExperience')} "
        f"company={discovery.get('company')} "
        f"candidates={len(discovery.get('questions') or [])}"
    )

    if not discovery.get("isInterviewExperience"):
        return {"questions": []}

    candidates = discovery.get("questions") or []
    if not candidates:
        return {"questions": []}

    enriched = enrich_questions(fields, discovery)
    result = normalize_payload(enriched, fields)

    print(
        f"[STAGE-2] final_questions={len(result.get('questions') or [])}"
    )

    return result


def experience_id(payload: dict[str, Any]) -> str:
    value = payload.get("sourceUrl") or json.dumps(
        payload, sort_keys=True, ensure_ascii=False
    )
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def print_payload(payload: dict[str, Any]) -> None:
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default=os.getenv("INPUT_DIR", "/data"))
    parser.add_argument(
        "--limit",
        type=int,
        default=INPUT_LIMIT,
        help="Maximum number of source records to process",
    )
    args = parser.parse_args()

    global INPUT_LIMIT
    INPUT_LIMIT = max(1, args.limit)

    records = load_records(args.input)
    print(
        f"Loaded {len(records)} records "
        f"(limit={INPUT_LIMIT}, model={MODEL}, two-stage=true)"
    )

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
