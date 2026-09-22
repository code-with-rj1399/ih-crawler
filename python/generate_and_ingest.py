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
Extract every distinct question or problem that was actually asked or given to
the candidate from the complete interview-experience source.

Return only questionText and questionDescription for each question.

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

WHAT COUNTS AS A QUESTION
-------------------------
Extract questions that the source reports as part of the candidate's actual
interview, assessment, coding round, system-design round, LLD round, etc.

Do not extract generic preparation questions, question-bank items, tutorials,
generic advice, or hypothetical examples that were not reported as asked.

A technology mention is not a question.
A project discussion is not automatically a question.

Extract every distinct actual question separately. Do not merge separate
questions or split one problem into artificial subquestions.

QUESTION TEXT
-------------
Write a concise, grammatically correct standalone representation of the
actual problem.

Remove interview-story wording such as "they asked me" or "in the interview".

Fix obvious grammar problems in the source, but do not add information that
is not present.

Preserve explicitly stated constraints, variations, and requirements.

Never guess a canonical LeetCode/GFG/HackerRank problem from resemblance.

QUESTION DESCRIPTION
--------------------
Write a concise 1–2 sentence description of what the candidate was asked to
solve.

Use only information explicitly present in the source.

Do not invent or infer:
- requirements
- constraints
- examples
- algorithms
- data structures
- APIs
- architecture
- scale or traffic
- storage
- latency or availability
- business requirements
- acceptance criteria

Do not describe the candidate's solution.

If the source gives only a short question, keep the description short.

OUTPUT
------
Return ONLY JSON matching the supplied schema.

For each question return exactly:
- questionId
- questionText
- questionDescription

questionId must be q1, q2, q3, ... in source order.

If there are no actual interview questions, return an empty questions array.
"""


STAGE2_PROMPT = r"""
For each question below, extract its metadata from the original source.

Use the questionText and questionDescription to identify exactly which part of
the source the metadata belongs to.

Extract only the metadata fields defined by the schema.

ORIGINAL SOURCE
---------------
platform: {source_platform}
url: {post_url}
title: {title}
author: {author}
published_at: {published_at}

content:
{page_content}

QUESTIONS
---------
{questions_json}

For every supplied question, return exactly one metadata object with the same
questionId.

Extract only information supported by the source. Use null when a field is not
supported. Do not guess.

Metadata fields:
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

questionType must use only the allowed enum values in the schema.
topics must contain only source-supported topics.
candidateApproach must contain only the candidate's explicitly described
approach or solution.
problemUrl must be included only when the source contains a URL directly
corresponding to that question.
postDate should use the supplied publication date when available.

Preserve the supplied questionId exactly. Do not add, remove, merge, split,
duplicate, or reorder questions.
Return ONLY JSON matching the supplied schema.
"""


def extract_questions(fields: dict[str, str]) -> dict[str, Any]:
    return call_json(
        STAGE1_PROMPT.format(**fields),
        STAGE1_SCHEMA,
        "interview_question_extraction",
        8000,
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
