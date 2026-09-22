#!/usr/bin/env python3
import argparse, glob, hashlib, json, os
from datetime import datetime, timezone
from typing import Any
from openai import OpenAI
MODEL = os.getenv("OPENAI_MODEL", "gpt-5-nano")
DYNAMODB_TABLE = os.getenv("DYNAMODB_TABLE", "InterviewExperiences")
AWS_REGION = os.getenv("AWS_REGION", "ap-south-1")
client = OpenAI(api_key=os.environ["OPENAI_API_KEY"])
PROMPT = """You are an advanced technical interview question and problem listing engine.

The crawler has already fetched this interview-experience post and supplied its content below.
DO NOT browse the web, search, open URLs, or use tools.

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
DO NOT extract questions from interview preparation articles, question lists, study guides, tutorials, question banks, generic interview tips, or collections of commonly asked questions.
If the post is primarily educational rather than a personal interview experience, return an empty questions list.

COMPANY REQUIREMENT:
A valid interview experience must identify the company explicitly from the supplied post content or reliable metadata. Never guess a company. If none can be identified, return an empty questions list. Every extracted question must have a non-null company.

Extract genuine technical interview questions, coding problems, system design prompts, or technical scenarios actually reported in the text. List the ACTUAL CORE TECHNICAL QUESTION OR PROBLEM, not surrounding interview narrative.

QUESTION TEXT:
- questionText is the actual interview question/problem in the shortest faithful form.
- Keep it direct, natural, and <=140 characters.
- Preserve the core wording and meaning from the source.
- Remove surrounding interview narrative, explanations, labels, and editorial wording.
- Do not add product/category labels such as "(Product)", "(System Design)", or "(Coding)".
- Do not embellish a short question into a more elaborate one.
- Do not invent requirements, scale, architecture, constraints, technologies, or objectives.
- Prefer "Design Instagram." over "Design Instagram (Product): architect a scalable photo-sharing app."
- Prefer "Design a social media news feed." over adding unstated scalability requirements.
- If the source only gives a short topic such as "Design: Calendar", preserve it as a concise question such as "Design a calendar." Do not invent details.

QUESTION DESCRIPTION:
- questionDescription elaborates the question using ONLY information explicitly supported by the source.
- Explain the requirements, constraints, context, or expected task that the source actually provides.
- Start directly with the problem/task.
- Never mention candidate, interviewer, interview, post, author, or prompt.
- Never invent constraints, examples, algorithms, solutions, scale, APIs, storage, traffic, or other requirements.
- If the source provides insufficient detail, keep the description short rather than filling gaps with general knowledge.
- Use roughly 40-80 words only when the source contains enough detail to support that level of description.
- The questionText and questionDescription must describe the same actual question; description adds supported detail but does not reinterpret or expand the question.

NORMALIZATION:
- Preserve original meaning and technical context.
- Remove conversational prefixes and qualifiers.
- Preserve constraints, requirements, data structures and scale requirements that materially describe the problem.
- Do not infer canonical LeetCode/GFG/HackerRank names unless explicitly named by the author.
- If explicitly named, preserve the exact problem name.
- List every distinct technical question separately; do not merge or split questions improperly.

EVIDENCE:
Include an item only when the supplied post provides evidence that it was part of an actual interview or assessment. Valid evidence includes "they asked", "coding problem was", "I was asked to write", "system design round", "coding round", or a clearly described sequence of questions.
Do not list technologies merely mentioned, previous projects without a concrete prompt, hypothetical examples, generic skills, or non-technical recruiter questions.

QUALITY:
For every item, there must be specific evidence in the supplied post that it was an actual technical question/problem. questionText must describe the actual technical problem rather than narrative.

Rules:
- Never invent a question or metadata.
- Use null when metadata is unsupported.
- questionType: CODING, SYSTEM_DESIGN, LOW_LEVEL_DESIGN, BEHAVIORAL, TECHNICAL, DATABASE, DEVOPS, AI_ML, or OTHER.
- difficulty: Easy, Medium, or Hard only when supported.
- candidateApproach only when explicitly stated.
- candidateYoE only from candidate content.
- problemUrl only when confidently identified in supplied content.
- postDate should use supplied publication timestamp converted to UTC date when available.
- confidence must be between 0.0 and 1.0.

Return ONLY this JSON shape:
{"questions":[{"company":"string","questionText":"string","questionDescription":"string","questionType":"CODING","difficulty":null,"candidateApproach":null,"candidateYoE":null,"problemUrl":null,"postDate":null,"confidence":0.0}]}
"""
def load_records(directory: str) -> list[dict[str, Any]]:
    records = []
    for path in sorted(glob.glob(os.path.join(directory, "*.json"))):
        try:
            with open(path, encoding="utf-8") as f: data = json.load(f)
            for item in (data if isinstance(data, list) else [data]):
                if isinstance(item, dict):
                    records.append(item)
                    if len(records) == 5: return records
        except Exception as e: print(f"[WARN] {path}: {e}")
    return records
def extract(record):
    source_platform = record.get("platform") or record.get("source") or record.get("sourcePlatform") or ""
    post_url = record.get("url") or record.get("sourceUrl") or record.get("originalPostUrl") or ""
    title = record.get("title") or ""
    author = record.get("author") or record.get("postedBy") or ""
    published_at = record.get("publishedAt") or record.get("postDate") or record.get("postedAt") or ""
    page_content = record.get("pageContent") or record.get("content") or record.get("text") or json.dumps(record, ensure_ascii=False)
    prompt = PROMPT
    prompt = prompt.replace("{source_platform}", source_platform)
    prompt = prompt.replace("{post_url}", post_url)
    prompt = prompt.replace("{title}", title)
    prompt = prompt.replace("{author}", author)
    prompt = prompt.replace("{published_at}", published_at)
    prompt = prompt.replace("{page_content}", page_content)
    response = client.responses.create(model=MODEL, input=[{"role":"system","content":prompt},{"role":"user","content":"Return the JSON extraction for the supplied source."}])
    text = response.output_text.strip()
    if text.startswith("```"): text = text.replace("```json", "").replace("```", "").strip()
    return json.loads(text)
def experience_id(payload):
    value = payload.get("sourceUrl") or json.dumps(payload, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
def print_payload(payload):
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return True

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--input", default=os.getenv("INPUT_DIR", "/data"))
    args = p.parse_args()
    records = load_records(args.input)
    print(f"Loaded {len(records)} records (maximum 5)")
    for i, record in enumerate(records, 1):
        try:
            payload = extract(record)
            print_payload(payload)
            print(f"[OK] {i}/{len(records)} generated: {len(payload.get('questions', []))} questions")
        except Exception as e: print(f"[ERROR] {i}/{len(records)}: {e}")
if __name__ == "__main__": main()