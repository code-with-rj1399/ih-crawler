#!/usr/bin/env python3
import argparse
import glob
import json
import os
from typing import Any

from openai import OpenAI

MODEL = os.getenv("OPENAI_MODEL", "gpt-5.6-terra")
INPUT_LIMIT = int(os.getenv("INPUT_LIMIT", "5"))
MAX_CONTENT_CHARS = int(os.getenv("MAX_CONTENT_CHARS", "30000"))

client = OpenAI(api_key=os.environ["OPENAI_API_KEY"])

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXPERIENCE_PROMPT_PATH = os.getenv(
    "EXPERIENCE_PROMPT_PATH",
    os.path.join(BASE_DIR, "src", "main", "resources", "prompts", "experience_extraction.txt"),
)
QUESTION_PROMPT_PATH = os.getenv(
    "QUESTION_PROMPT_PATH",
    os.path.join(BASE_DIR, "src", "main", "resources", "prompts", "question_metadata_extraction.txt"),
)


def read_prompt(path: str) -> str:
    with open(path, encoding="utf-8") as prompt_file:
        return prompt_file.read()


EXPERIENCE_PROMPT = read_prompt(EXPERIENCE_PROMPT_PATH)
QUESTION_PROMPT = read_prompt(QUESTION_PROMPT_PATH)

EXPERIENCE_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "authenticExperience": {"type": "boolean"},
        "experience": {
            "type": "object",
            "additionalProperties": False,
            "properties": {
                "title": {"type": ["string", "null"]},
                "summary": {"type": ["string", "null"]},
                "postedAt": {"type": ["string", "null"]},
                "author": {"type": ["string", "null"]},
                "company": {"type": ["string", "null"]},
                "role": {"type": ["string", "null"]},
                "level": {"type": ["string", "null"]},
                "location": {"type": ["string", "null"]},
                "candidateYoE": {"type": ["number", "null"]},
                "outcome": {"type": ["string", "null"]},
                "rounds": {"type": "array", "items": {"type": "string"}},
            },
            "required": [
                "title", "summary", "postedAt", "author", "company", "role", "level",
                "location", "candidateYoE", "outcome", "rounds",
            ],
        },
        "questions": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["authenticExperience", "experience", "questions"],
}

QUESTION_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "questionType": {"type": ["string", "null"]},
        "difficulty": {"type": ["string", "null"]},
        "topics": {"type": "array", "items": {"type": "string"}},
        "questionDescription": {"type": ["string", "null"]},
        "confidence": {"type": ["number", "null"]},
    },
    "required": ["questionType", "difficulty", "topics", "questionDescription", "confidence"],
}


def load_records(directory: str, limit: int) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for path in sorted(glob.glob(os.path.join(directory, "*.json"))):
        try:
            with open(path, encoding="utf-8") as file:
                data = json.load(file)
            items = data if isinstance(data, list) else [data]
            for item in items:
                if isinstance(item, dict):
                    records.append(item)
                    if len(records) >= limit:
                        return records
        except Exception as exc:
            print(f"[WARN] {path}: {exc}")
    return records


def get_source_fields(record: dict[str, Any]) -> dict[str, str]:
    source_platform = record.get("platform") or record.get("source") or record.get("sourcePlatform") or ""
    post_url = record.get("url") or record.get("sourceUrl") or record.get("originalPostUrl") or ""
    title = record.get("title") or ""
    author = record.get("author") or record.get("postedBy") or ""
    published_at = record.get("publishedAt") or record.get("postDate") or record.get("postedAt") or ""
    page_content = record.get("pageContent") or record.get("content") or record.get("text") or json.dumps(record, ensure_ascii=False)
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


def call_json(prompt: str, schema: dict[str, Any], schema_name: str) -> dict[str, Any]:
    response = client.responses.create(
        model=MODEL,
        input=prompt,
        reasoning={"effort": "low"},
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
    return json.loads(text)


def extract_experience(record: dict[str, Any]) -> dict[str, Any]:
    fields = get_source_fields(record)
    prompt = (EXPERIENCE_PROMPT
              .replace("{{source_platform}}", fields["source_platform"])
              .replace("{{post_url}}", fields["post_url"])
              .replace("{{title}}", fields["title"])
              .replace("{{author}}", fields["author"])
              .replace("{{published_at}}", fields["published_at"])
              .replace("{{page_content}}", fields["page_content"]))
    return call_json(prompt, EXPERIENCE_SCHEMA, "experience_extraction")


def classify_question(question_text: str) -> dict[str, Any]:
    prompt = QUESTION_PROMPT.replace("{{question_text}}", question_text.strip())
    return call_json(prompt, QUESTION_SCHEMA, "question_metadata_extraction")


def extract_two_step(record: dict[str, Any]) -> dict[str, Any]:
    step1 = extract_experience(record)
    if not step1.get("authenticExperience"):
        return {**step1, "questions": []}

    enriched_questions = []
    for question_text in step1.get("questions", []):
        if not isinstance(question_text, str) or not question_text.strip():
            continue
        metadata = classify_question(question_text)
        enriched_questions.append({"questionText": question_text.strip(), **metadata})

    return {
        "authenticExperience": True,
        "experience": step1.get("experience", {}),
        "questions": enriched_questions,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default=os.getenv("INPUT_DIR", "/data"))
    parser.add_argument("--limit", type=int, default=INPUT_LIMIT)
    args = parser.parse_args()

    limit = max(1, args.limit)
    records = load_records(args.input, limit)
    print(f"Loaded {len(records)} records (limit={limit}, model={MODEL}, two-step=true)")

    for index, record in enumerate(records, 1):
        try:
            payload = extract_two_step(record)
            print(json.dumps(payload, ensure_ascii=False, indent=2))
            print(f"[OK] {index}/{len(records)} generated: {len(payload.get('questions', []))} questions")
        except Exception as exc:
            print(f"[ERROR] {index}/{len(records)}: {exc}")


if __name__ == "__main__":
    main()
