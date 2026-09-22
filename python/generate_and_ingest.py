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
You are an advanced technical interview question and problem listing engine.

The crawler has already fetched this interview-experience post and supplied its content below.
Do not browse the web, search, open URLs, or use tools.

SOURCE PLATFORM: {source_platform}
POST URL: {post_url}
TITLE: {title}
AUTHOR: {author}
PUBLISHED AT: {published_at}

PAGE CONTENT:
---
{page_content}
---

EXPERIENCE AUTHENTICITY GATE:
First determine whether this post describes a REAL interview, assessment, or hiring interaction experienced by the author/candidate.

Extract technical questions ONLY when they are reported as having been asked, given, or encountered by the candidate during their own interview or assessment.

Do NOT extract questions from:
- interview preparation articles
- "Top X interview questions" lists
- study guides
- tutorials or educational articles
- question banks or practice problems
- generic interview tips
- collections of commonly asked questions
- posts that provide questions and answers without describing the author's actual interview experience

Strong signals that the post IS an interview experience include:
- "I interviewed at..."
- "My interview experience..."
- "I was asked..."
- "The interviewer asked..."
- "In the coding round..."
- "In the system design round..."
- "During my interview..."
- interview rounds, dates, companies, roles, outcomes, or candidate experience

If the post is primarily an educational/question-list article rather than a personal interview experience, return an empty questions list.

COMPANY REQUIREMENT:
A valid interview experience must identify the company involved in the interview.
The company must be explicitly supported by the supplied post content or reliable post metadata.
Never guess or infer a company from the technology, author, job title, or context.
If no company can be identified, return an empty questions list.

If the title or content indicates a generic collection such as "Top 50 Interview Questions",
"100 Java Interview Questions", "Frequently Asked Questions", "Interview Questions with Answers",
or similar educational content, treat it as a preparation article unless the post clearly contains
a separate personal interview experience.

Your task is to generate a list of genuine technical interview questions, coding problems,
system design prompts, or technical scenarios reported in this text.

IMPORTANT:
You are listing the ACTUAL CORE TECHNICAL QUESTION OR PROBLEM, not blindly copying sentences
from the post.

For every candidate item, determine:
1. What was actually asked or given to the candidate?
2. What is the core technical problem or prompt?
3. Which words are merely describing, qualifying, comparing, or explaining that question?

CORE PROBLEM EXTRACTION:
Separate the source text into:
A. The actual technical problem/question
B. Description or qualification of that problem
C. Surrounding interview narrative

questionText must represent A.
Use B only to accurately describe A.
Never include C in questionText.

QUESTION TEXT FORMAT:
- questionText must be ONE concise line and ONE sentence whenever possible.
- Target <= 140 characters so it is easy to scan in a table.
- Keep only the essential technical problem and constraints.
- Do not include interview narrative, candidate approach, or solution explanation.

QUESTION DESCRIPTION:
- questionDescription must read like a LeetCode problem statement, NOT an interview recap.
- Write 3 to 4 concise sentences, roughly 40-80 words.
- Start directly with the problem/task.
- Describe the input/problem, the required output/goal, and only constraints or allowed operations
  that are explicitly known.
- NEVER mention the candidate, interviewer, interview, coding round, post, author, or prompt.
- NEVER add commentary about commonness, algorithms, difficulty, or educational value.
- NEVER describe a solution, algorithm, data structure, complexity, or approach unless the source explicitly
  makes that part of the problem requirement.
- Do not invent constraints or examples.
- If the post only gives a problem name and does not provide enough details for a faithful problem statement,
  keep the description minimal rather than filling gaps from generic knowledge.
- Preserve actual requirements and wording from the supplied content while making it concise.

QUESTION NORMALIZATION:
- Preserve the original meaning and technical context.
- Remove conversational prefixes and interview narrative.
- Remove qualifiers such as "a variation of", "a modified version of", "similar to", "based on",
  "something like", "one question was", "the question was", "they asked me", "was asked", and
  "just explanation" when they are not part of the actual problem.
- Preserve technical constraints, requirements, data structures, scale requirements, and other details
  that materially describe the problem.
- Do not assume two problems are the same because they sound similar.
- Do not replace a problem with a standard/canonical name based on similarity.
- Do not use external knowledge to determine what the author meant.
- When the author gives only a descriptive technical problem statement, preserve that description.
- When the author explicitly names a problem, prefer the explicit problem name.

EVIDENCE REQUIREMENT:
Include an item only when the post provides sufficient evidence that it was part of an actual
technical interview or assessment.

Valid evidence includes:
- "they asked..."
- "the coding problem was..."
- "I was asked to write a function..."
- "system design round: ..."
- "coding round: ..."
- a clearly described sequence of questions from the author's own interview.

Do NOT list:
- interview preparation advice
- technologies or frameworks merely mentioned
- candidate background/previous projects without a concrete technical prompt
- hypothetical examples or generic skills
- recruiter/screening questions unless they contain an actual technical problem

MULTIPLE QUESTIONS:
- List every distinct technical question/problem separately.
- Do not merge different questions.
- Do not split one question into multiple questions merely because it contains multiple requirements or constraints.

CANONICAL NAME RULE:
- If the post explicitly names the problem, preserve that exact problem name.
- If the post describes a known problem but does not explicitly provide its name, do NOT infer or substitute
  a canonical LeetCode, GFG, HackerRank, or other platform problem name.
- If the post only provides a descriptive problem statement, create a concise description using ONLY information
  contained in the supplied content.
- Never hallucinate or rename the author's problem based on external knowledge.

QUALITY TEST:
Before including each technical question, ask internally:
"Could I point to a specific part of the supplied post that shows it was an actual technical question or problem?"
If NO, do not list it.

Then ask:
"Does questionText describe the actual technical problem, rather than the author's narrative?"
If NO, normalize it before adding it to the list.

METADATA:
For each question extract only metadata supported by the supplied content:
- sourcePlatform
- problemUrl
- postDate
- company
- role
- level
- location
- candidateYoE
- outcome
- roundType
- questionType
- candidateApproach
- difficulty
- topics
- confidence

Use null when a metadata value is not supported.
questionType must be one of: CODING, SYSTEM_DESIGN, LOW_LEVEL_DESIGN, BEHAVIORAL, TECHNICAL,
DATABASE, DEVOPS, AI_ML, OTHER.
difficulty must be Easy, Medium, or Hard only when supported.
candidateApproach must contain only the candidate's explicitly stated approach.
candidateYoE must come from the candidate's content.
problemUrl only when identified in the supplied content.
postDate should use the supplied publication timestamp when available.
confidence must be between 0.0 and 1.0.

Never invent a question or metadata.

Return ONLY the required JSON object.
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
        reasoning={"effort": "minimal"},
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
        details = []
        incomplete = getattr(response, "incomplete_details", None)
        if incomplete:
            details.append(f"incomplete_details={incomplete}")
        usage = getattr(response, "usage", None)
        if usage:
            details.append(f"usage={usage}")
        raise ValueError(
            "OpenAI returned an empty output"
            + (f" ({'; '.join(details)})" if details else "")
        )
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        details = []
        incomplete = getattr(response, "incomplete_details", None)
        if incomplete:
            details.append(f"incomplete_details={incomplete}")
        usage = getattr(response, "usage", None)
        if usage:
            details.append(f"usage={usage}")
        suffix = f" ({'; '.join(details)})" if details else ""
        raise ValueError(
            f"Invalid JSON from OpenAI: {text[:2000]}{suffix}"
        ) from exc



EXTRACTION_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "questions": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "sourcePlatform": {"type": ["string", "null"]},
                    "problemUrl": {"type": ["string", "null"]},
                    "postDate": {"type": ["string", "null"]},
                    "company": {"type": ["string", "null"]},
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
                    "sourcePlatform", "problemUrl", "postDate", "company", "role", "level",
                    "location", "candidateYoE", "outcome", "roundType", "questionType",
                    "questionText", "questionDescription", "candidateApproach", "difficulty",
                    "topics", "confidence",
                ],
            },
        }
    },
    "required": ["questions"],
}



def extract(record: dict[str, Any]) -> dict[str, Any]:
    fields = get_source_fields(record)
    return call_json(
        EXTRACTION_PROMPT.format(**fields),
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
