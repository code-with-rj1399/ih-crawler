#!/usr/bin/env python3
import argparse, glob, json, os
from typing import Any
import requests
from openai import OpenAI

MODEL = os.getenv('OPENAI_MODEL', 'gpt-5-nano')
API_URL = os.getenv('HQ_API_URL', 'http://localhost:8080/api/v1/interview-experiences')
client = OpenAI(api_key=os.environ['OPENAI_API_KEY'])

PROMPT = '''Extract interview questions from this crawled record.
Return JSON only:
{"sourceUrl":null,"source":null,"company":null,"role":null,"experienceDate":null,"postedAt":null,"author":null,"questions":[{"question":"","type":"DSA|SYSTEM_DESIGN|LLD|JAVA|SPRING_BOOT|AWS|DATABASE|BEHAVIORAL|OTHER","round":null,"difficulty":"EASY|MEDIUM|HARD|UNKNOWN","topics":[]}]}.
Do not invent data. Extract only explicit interview questions. Do not generate answers.'''

def load_records(directory: str) -> list[dict[str, Any]]:
    records = []
    for path in sorted(glob.glob(os.path.join(directory, '*.json'))):
        try:
            with open(path, encoding='utf-8') as f:
                data = json.load(f)
            items = data if isinstance(data, list) else [data]
            for item in items:
                if isinstance(item, dict):
                    records.append(item)
                    if len(records) == 5: return records
        except Exception as e:
            print(f'[WARN] {path}: {e}')
    return records

def extract(record: dict[str, Any]) -> dict[str, Any]:
    response = client.responses.create(model=MODEL, input=[
        {'role': 'system', 'content': PROMPT},
        {'role': 'user', 'content': json.dumps(record, ensure_ascii=False)}
    ])
    text = response.output_text.strip()
    if text.startswith('```'): text = text.replace('```json', '').replace('```', '').strip()
    return json.loads(text)

def push(payload: dict[str, Any], api_url: str):
    r = requests.post(api_url, json=payload, timeout=30)
    r.raise_for_status()

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--input', default=os.getenv('INPUT_DIR', '/data'))
    p.add_argument('--api-url', default=API_URL)
    args = p.parse_args()
    records = load_records(args.input)
    print(f'Loaded {len(records)} records (maximum 5)')
    for i, record in enumerate(records, 1):
        try:
            payload = extract(record)
            push(payload, args.api_url)
            print(f'[OK] {i}/5 pushed: {len(payload.get("questions", []))} questions')
        except Exception as e:
            print(f'[ERROR] {i}/5: {e}')

if __name__ == '__main__': main()