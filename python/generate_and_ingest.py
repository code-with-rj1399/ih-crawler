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
You are an advanced technical interview data extraction engine.

SOURCE PLATFORM: {source_platform}
POST URL: {post_url}
TITLE: {title}
AUTHOR: {author}
PUBLISHED AT: {published_at}

PAGE CONTENT:
---
{page_content}
---

Follow these strictly ordered steps to extract data:

STEP 1: AUTHENTICITY & COMPANY GATE
- Evaluate if this is a REAL, personal interview experience (e.g., "I interviewed at...").
- If it is a tutorial, generic list ("Top 50 questions"), or study guide, STOP. Return an empty questions list.
- A COMPANY MUST BE IDENTIFIED in the text. NEVER guess or infer the company. If missing, return an empty questions list.

STEP 2: QUESTION EXTRACTION
Extract only the actual technical questions/problems given to the candidate.
For each question, formulate:
- questionText: A 1-line, concise core problem statement (<= 140 chars).
- questionDescription: A 3-4 sentence "LeetCode-style" problem statement containing only the task, inputs, outputs, and explicit constraints.

STEP 3: APPLY STRICT PROHIBITIONS (CRITICAL)
- NEVER include interview narrative (Remove: "The interviewer asked me...", "A variation of...", etc.).
- NEVER substitute canonical names (e.g., replacing a vague description with "Two Sum"). Keep the original meaning.
- NEVER invent constraints, solutions, or context not present in the text.
- NEVER merge different questions together.

STEP 4: OUTPUT FORMAT
Return ONLY valid JSON matching the exact schema below. Do not wrap in ```json markdown.

{
  "is_authentic_experience": true,
  "company": "Company Name (or null)",
  "questions": [
    {
      "questionText": "Concise 1-line problem statement",
      "questionDescription": "Neutral LeetCode-style description using only provided details",
      "questionType": "CODING | SYSTEM_DESIGN | LOW_LEVEL_DESIGN | BEHAVIORAL | TECHNICAL | DATABASE | DEVOPS | AI_ML | OTHER",
      "difficulty": "Easy | Medium | Hard | null",
      "candidateApproach": "Candidate's explicitly stated approach (or null)",
      "topics": ["topic1", "topic2"],
      "sourcePlatform": "From inputs",
      "problemUrl": "Explicitly provided URL (or null)",
      "postDate": "From inputs",
      "role": "Role (or null)",
      "level": "Level (or null)",
      "location": "Location (or null)",
      "candidateYoE": "Candidate's years of experience (or null)",
      "outcome": "Interview outcome (or null)",
      "roundType": "Round name/type (or null)",
      "confidence": 0.95
    }
  ]
}
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
                    "candidateApproach": {"type": ["string", "null"]},
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
                    "questionText", "questionDescription", "candidateApproach", "difficulty",
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
        EXTRACTION_PROMPT.replace("{source_platform}", fields["source_platform"]).replace("{post_url}", fields["post_url"]).replace("{title}", fields["title"]).replace("{author}", fields["author"]).replace("{published_at}", fields["published_at"]).replace("{page_content}", fields["page_content"]),
        EXTRACTION_SCHEMA,
        "interview_question_extraction",
        6000,
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
