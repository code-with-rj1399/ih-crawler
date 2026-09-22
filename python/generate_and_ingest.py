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


EXTRACTION_PROMPT = r"""
Extract only real, specific interview questions. No tools, no external knowledge, no invention.

SOURCE PLATFORM: {source_platform}
POST URL: {post_url}
TITLE: {title}
AUTHOR: {author}
PUBLISHED AT: {published_at}
TAGS: {tags}

CONTENT:
---
{page_content}
---

CORE RULES

COMPANY GATE
- Real personal interview experience only.
- Company must be explicit in content or supplied metadata. Never infer.
- No company -> {"questions": []}
- Reject tutorials, prep guides, question banks, tip lists, and "what are common questions" posts.

WHAT COUNTS AS A QUESTION
Extract ONLY when the source shows a concrete task/question presented to the candidate.

YES examples:
- "Design a rate limiter."
- "Implement an LRU cache."
- "Design: Calendar."
- "Given an array, find the longest subarray..."

NO examples (never extract):
- "They asked about Kafka / Redis / microservices"
- "System design round / coding round / project discussion"
- "They asked LeetCode questions"
- "We discussed how Kafka handles failures" -> do NOT turn into a question
- Any generic list request ("common Google questions", "DSA questions", etc.)

RULES FOR EXTRACTION
- Preserve exact source specificity. Never add scale, APIs, constraints, requirements, or other details unless present.
- Never convert narrative, statements, discussions, or answers into questions.
- Multiple distinct questions -> extract separately. Never merge.
- One question with follow-ups/requirements -> keep as one. Never artificially split.
- Prefer an empty list over weak or inferred questions. Precision > recall.

questionText
- <= 140 chars, pure technical task only.
- Strip narrative ("they asked me...", "then they said...").
- No labels.
- Use the exact problem/task name if given. Never invent problem names.
- A technical topic or category is not a question.

questionDescription
- Only details explicitly present in the source.
- Include requirements, constraints, edge cases, follow-ups, approaches, or other details only when explicitly mentioned.
- Never expand vague topics with external knowledge.
- Keep it short and faithful when the source is sparse.

problemUrl
- Only a URL visibly present in the supplied content for that exact question.
- Never invent, construct, infer, search for, or use the post URL.
- Otherwise null.

METADATA
- Only values directly supported by the supplied content or metadata.
- Never infer company, role, level, location, candidateYoE, outcome, difficulty, topics, or roundType.
- difficulty = Easy, Medium, Hard, or null.

CONFIDENCE (authenticity only)
- Confidence measures how strongly the source proves that this specific question/task was actually presented to the candidate.
- It does NOT measure difficulty, extraction quality, popularity, or company confidence.

0.9-1.0 = explicit concrete question with strong evidence
0.7-0.8 = clear real question but sparse
0.5-0.6 = weak / borderline evidence
0.3-0.4 = very weak / mostly inferred
< 0.3 = do not include

FINAL GATE (apply before returning)
1. Real personal interview experience?
2. Company explicit?
3. Specific evidence this exact task/question was presented to the candidate?
4. Is it a concrete question/task, not a topic or category?
5. No narrative conversion?
6. Zero external knowledge added?
7. questionText and questionDescription fully source-faithful?
8. confidence >= 0.3?

Any NO -> drop the question.

If evidence is ambiguous, return no question rather than guessing.

Return ONLY valid JSON.
"""

EXTRACTION_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "is_authentic_experience": {"type": "boolean"},
        "company": {"type": ["string", "null"]},
        "questions": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "company": {"type": ["string", "null"]},
                    "sourcePlatform": {"type": ["string", "null"]},
                    "problemUrl": {"type": ["string", "null"]},
                    "postDate": {"type": ["string", "null"]},
                    "role": {"type": ["string", "null"]},
                    "level": {"type": ["string", "null"]},
                    "location": {"type": ["string", "null"]},
                    "candidateYoE": {"type": ["number", "null"]},
                    "outcome": {"type": ["string", "null"]},
                    "roundType": {"type": ["string", "null"]},
                    "questionType": {
                        "type": ["string", "null"],
                        "enum": ["CODING", "SYSTEM_DESIGN", "LOW_LEVEL_DESIGN", "BEHAVIORAL",
                                 "TECHNICAL", "DATABASE", "DEVOPS", "AI_ML", "OTHER", None],
                    },
                    "questionText": {"type": "string"},
                    "questionDescription": {"type": "string"},
                    "difficulty": {
                        "type": ["string", "null"],
                        "enum": ["Easy", "Medium", "Hard", None],
                    },
                    "topics": {"type": "array", "items": {"type": "string"}},
                    "confidence": {"type": "number"},
                },
                "required": [
                    "company", "sourcePlatform", "problemUrl", "postDate", "role", "level",
                    "location", "candidateYoE", "outcome", "roundType", "questionType",
                    "questionText", "questionDescription", "difficulty",
                    "topics", "confidence",
                ],
            },
        },
    },
    "required": ["is_authentic_experience", "company", "questions"],
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
    tags = record.get("tags") or record.get("tag") or []
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
        "tags": json.dumps(tags, ensure_ascii=False) if isinstance(tags, (list, dict)) else str(tags),
        "page_content": page_content,
    }


def call_json(prompt: str, schema: dict[str, Any], schema_name: str, max_output_tokens: int) -> dict[str, Any]:
    response = client.responses.create(
        model=MODEL,
        input=prompt,
        reasoning={"effort": "minimal"},
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
        details = []
        incomplete = getattr(response, "incomplete_details", None)
        if incomplete:
            details.append(f"incomplete_details={incomplete}")
        usage = getattr(response, "usage", None)
        if usage:
            details.append(f"usage={usage}")
        raise ValueError("OpenAI returned an empty output" + (f" ({'; '.join(details)})" if details else ""))
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON from OpenAI: {text[:2000]}") from exc


def extract(record: dict[str, Any]) -> dict[str, Any]:
    fields = get_source_fields(record)
    return call_json(
        EXTRACTION_PROMPT.replace("{source_platform}", fields["source_platform"]).replace("{post_url}", fields["post_url"]).replace("{title}", fields["title"]).replace("{author}", fields["author"]).replace("{published_at}", fields["published_at"]).replace("{tags}", fields["tags"]).replace("{page_content}", fields["page_content"]),
        EXTRACTION_SCHEMA,
        "interview_question_extraction",
        None,
    )


def print_payload(payload: dict[str, Any]) -> None:
    print(json.dumps(payload, ensure_ascii=False, indent=2))

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default=os.getenv("INPUT_DIR", "/data"))
    parser.add_argument("--limit", type=int, default=INPUT_LIMIT)
    args = parser.parse_args()

    limit = max(1, args.limit)
    records = load_records(args.input, limit)

    print(f"Loaded {len(records)} records (limit={limit}, model={MODEL}, single-prompt=true)")

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
