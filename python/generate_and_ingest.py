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


STAGE1_PROMPT = r"""
You are InterviewHQ's QUESTION EXTRACTION engine.

Your ONLY job is to extract the actual interview questions/problems from the
supplied interview-experience source and write a faithful questionDescription.

Stage 1 output is intentionally limited to questionText and questionDescription. However, use the ENTIRE source and all surrounding context to understand each question correctly. Company, round, role, interviewer wording, and other contextual information may be used as evidence while extracting the two output fields.

Do not produce metadata fields in the Stage 1 output.

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
Extract questions only when the source describes an actual interview,
assessment, hiring loop, coding round, system-design round, LLD round, etc.

Do NOT extract questions from:
- interview preparation material
- "top N interview questions"
- question banks
- tutorials/courses
- generic interview advice
- commonly asked question collections
- hypothetical examples not reported as asked

QUESTION EXTRACTION
-------------------
Extract every distinct technical problem/question actually asked or given
to the candidate.

A technology mention is NOT a question.
A project discussion is NOT automatically a question.

"Discussed Kafka" is NOT a question.
"Interviewer asked me to design a Kafka-based notification system" IS a question.

Return the smallest faithful representation of the actual question.

Examples:
- "Design a calendar."
- "Find the longest substring without repeating characters."
- "Design a notification system."
- "Implement an LRU cache."

Do not include interview-story wording such as "they asked me" or "in the
second round".

ANTI-HALLUCINATION
------------------
Every question and every detail in questionDescription MUST be supported by
the supplied source.

Never infer a canonical LeetCode/GFG/HackerRank problem because the source
resembles one.

If the source explicitly names a known problem, preserve that name.

If the source says "variation of Two Sum with negative numbers", preserve
that meaning. Do not silently convert it to generic "Two Sum".

QUESTION DESCRIPTION
--------------------
For each extracted question, write a concise, source-grounded problem
description explaining what the candidate was actually asked to solve.

Include ONLY details explicitly present in the source:
- requirements
- constraints
- inputs/outputs
- edge cases
- functional behaviour
- explicitly stated follow-ups
- explicitly stated interviewer requirements

Do NOT invent:
- traffic or scale
- APIs
- architecture
- storage
- latency
- availability
- algorithms
- data structures
- examples
- constraints
- acceptance criteria
- business requirements

Do NOT describe the candidate's solution or approach.

If the source only says "Design a calendar", keep the description short.
Do not manufacture a full specification.

The questionText and questionDescription MUST describe the same problem.

OUTPUT
------
Return ONLY JSON matching the supplied schema.

The ONLY output fields are:
- questionId
- questionText
- questionDescription

questionId must be a deterministic sequential identifier in source order: q1, q2, q3, etc. Do not skip or reuse IDs.

If the source is not an interview experience or contains no actual questions,
return an empty questions array.
"""


STAGE2_PROMPT = r"""
You are InterviewHQ's METADATA EXTRACTION engine.

Stage 1 has already extracted the interview questions and their
source-grounded descriptions.

Your ONLY job is to extract the OTHER metadata fields for each Stage 1
question from the ORIGINAL SOURCE.

Use the original source plus the Stage 1 question and description as context.

Do NOT rewrite, improve, expand, classify, or generate questionText or
questionDescription. Stage 1 owns those two fields.

Do NOT invent information.

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

STAGE 1 QUESTIONS
-----------------
{questions}

METADATA
--------
For each Stage 1 question, extract metadata only:
questionId, company, role, level, location, candidateYoE, outcome, roundType,
questionType, difficulty, candidateApproach, problemUrl, postDate, topics.

Rules:
- Extract only what the source supports.
- Use null when unsupported.
- candidateApproach must contain only the candidate's explicitly described
  approach. Never solve the problem.
- difficulty may be Easy/Medium/Hard only when explicitly stated or strongly
  supported by direct evidence. Do not infer it from problem type.
- problemUrl must be a URL present in the supplied source and directly
  corresponding to that question. Never construct or search for one.
- postDate should use the supplied publication timestamp converted to UTC
  date when available.
- company must be supported by the source; never guess it from URL,
  technology, role, or author.
- topics must contain only topics explicitly supported by the source.

questionType must be one of:
CODING, SYSTEM_DESIGN, LOW_LEVEL_DESIGN, BEHAVIORAL, TECHNICAL, DATABASE,
DEVOPS, AI_ML, OTHER

Every Stage 1 question must have exactly one corresponding Stage 2 metadata object. Preserve the Stage 1 questionId exactly. Do not invent, drop, merge, or duplicate question IDs.

OUTPUT
------
Return ONLY JSON matching the supplied schema.
"""


STAGE1_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "questions": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "questionId": {"type": "string"},
                    "questionText": {"type": "string"},
                    "questionDescription": {"type": "string"},
                },
                "required": ["questionId", "questionText", "questionDescription"],
            },
        }
    },
    "required": ["questions"],
}

STAGE2_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "questions": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "questionId": {"type": "string"},
                    "company": {"type": ["string", "null"]},
                    "role": {"type": ["string", "null"]},
                    "level": {"type": ["string", "null"]},
                    "location": {"type": ["string", "null"]},
                    "candidateYoE": {"type": ["number", "null"]},
                    "outcome": {"type": ["string", "null"]},
                    "roundType": {"type": ["string", "null"]},
                    "questionType": {"type": ["string", "null"]},
                    "difficulty": {"type": ["string", "null"]},
                    "candidateApproach": {"type": ["string", "null"]},
                    "problemUrl": {"type": ["string", "null"]},
                    "postDate": {"type": ["string", "null"]},
                    "topics": {"type": "array", "items": {"type": "string"}},
                },
                "required": [
                    "questionId", "company", "role", "level", "location", "candidateYoE",
                    "outcome", "roundType", "questionType", "difficulty",
                    "candidateApproach", "problemUrl", "postDate", "topics",
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


def extract_questions(fields: dict[str, str]) -> dict[str, Any]:
    return call_json(
        STAGE1_PROMPT.format(**fields),
        STAGE1_SCHEMA,
        "interview_question_extraction",
        3000,
    )


def extract_metadata(
    fields: dict[str, str],
    stage1: dict[str, Any],
) -> dict[str, Any]:
    questions = json.dumps(stage1.get("questions") or [], ensure_ascii=False, indent=2)
    prompt = STAGE2_PROMPT.format(**fields, questions=questions)
    return call_json(
        prompt,
        STAGE2_SCHEMA,
        "interview_metadata_extraction",
        3000,
    )


def normalize_payload(
    stage1: dict[str, Any],
    stage2: dict[str, Any],
) -> dict[str, Any]:
    questions1 = stage1.get("questions") or []
    questions2 = stage2.get("questions") or []

    if len(questions1) != len(questions2):
        raise ValueError(
            f"Stage 2 returned {len(questions2)} records for "
            f"{len(questions1)} Stage 1 questions"
        )

    stage1_ids = [q.get("questionId") for q in questions1]
    stage2_ids = [q.get("questionId") for q in questions2]
    if stage1_ids != stage2_ids:
        raise ValueError(
            f"Stage 1/Stage 2 questionId mismatch: "
            f"stage1={stage1_ids}, stage2={stage2_ids}"
        )

    clean_questions: list[dict[str, Any]] = []
    for q1, q2 in zip(questions1, questions2):
        question_id = (q1.get("questionId") or "").strip()
        question_text = (q1.get("questionText") or "").strip()
        question_description = (q1.get("questionDescription") or "").strip()
        company = (q2.get("company") or "").strip()

        if not question_id or not question_text or not question_description or not company:
            continue

        clean_questions.append({
            "questionId": question_id,
            "company": company,
            "questionText": question_text,
            "questionDescription": question_description,
            "questionType": q2.get("questionType"),
            "difficulty": q2.get("difficulty"),
            "candidateApproach": q2.get("candidateApproach"),
            "candidateYoE": q2.get("candidateYoE"),
            "problemUrl": q2.get("problemUrl"),
            "postDate": q2.get("postDate"),
            "role": q2.get("role"),
            "level": q2.get("level"),
            "location": q2.get("location"),
            "outcome": q2.get("outcome"),
            "roundType": q2.get("roundType"),
            "topics": q2.get("topics") or [],
        })

    return {"questions": clean_questions}


def extract(record: dict[str, Any]) -> dict[str, Any]:
    fields = get_source_fields(record)

    stage1 = extract_questions(fields)
    questions = stage1.get("questions") or []

    print(f"[STAGE-1] questions={len(questions)}")

    if not questions:
        return {"questions": []}

    stage2 = extract_metadata(fields, stage1)

    result = normalize_payload(stage1, stage2)
    print(f"[STAGE-2] metadata={len(stage2.get('questions') or [])} final={len(result['questions'])}")
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
