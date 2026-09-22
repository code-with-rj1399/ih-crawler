#!/usr/bin/env python3
import argparse, glob, hashlib, json, os
from datetime import datetime, timezone
from typing import Any
import boto3
from botocore.exceptions import ClientError
from openai import OpenAI
MODEL = os.getenv("OPENAI_MODEL", "gpt-5-nano")
DYNAMODB_TABLE = os.getenv("DYNAMODB_TABLE", "InterviewExperiences")
AWS_REGION = os.getenv("AWS_REGION", "ap-south-1")
client = OpenAI(api_key=os.environ["OPENAI_API_KEY"])
table = boto3.resource("dynamodb", region_name=AWS_REGION).Table(DYNAMODB_TABLE)
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

questionText must be one concise line, one sentence whenever possible, <=140 characters, containing only the essential technical problem and constraints.

questionDescription must read like a LeetCode problem statement, 3-4 concise sentences and roughly 40-80 words. Start directly with the problem/task. Describe only requirements and constraints explicitly known. Never mention candidate, interviewer, interview, post, author, or prompt. Never invent constraints, examples, algorithms, solutions, or metadata.

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
    response = client.responses.create(model=MODEL, input=[{"role":"system","content":PROMPT},{"role":"user","content":json.dumps(record, ensure_ascii=False)}])
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
    print(f"DynamoDB table: {DYNAMODB_TABLE}, region: {AWS_REGION}")
    for i, record in enumerate(records, 1):
        try:
            payload = extract(record)
            print_payload(payload)
            print(f"[OK] {i}/{len(records)} generated: {len(payload.get('questions', []))} questions")
        except Exception as e: print(f"[ERROR] {i}/{len(records)}: {e}")
if __name__ == "__main__": main()