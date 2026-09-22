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
PROMPT = """Extract interview questions from this crawled record.
Return JSON only:
{"sourceUrl":null,"source":null,"company":null,"role":null,"experienceDate":null,"postedAt":null,"author":null,"questions":[{"question":"","type":"DSA|SYSTEM_DESIGN|LLD|JAVA|SPRING_BOOT|AWS|DATABASE|BEHAVIORAL|OTHER","round":null,"difficulty":"EASY|MEDIUM|HARD|UNKNOWN","topics":[]}]}
Do not invent data. Extract only explicit interview questions. Do not generate answers."""
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
def push_to_dynamodb(payload):
    now = datetime.now(timezone.utc).isoformat()
    item = {"experienceId": experience_id(payload), **payload, "createdAt": now, "updatedAt": now, "crawledAt": now}
    try:
        table.put_item(Item=item, ConditionExpression="attribute_not_exists(experienceId)")
        return True
    except ClientError as e:
        if e.response.get("Error", {}).get("Code") == "ConditionalCheckFailedException":
            print(f"[SKIP] Already exists: {item["experienceId"]}")
            return False
        raise
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
            inserted = push_to_dynamodb(payload)
            print(f"[OK] {i}/{len(records)} {"pushed" if inserted else "already exists"}: {len(payload.get("questions", []))} questions")
        except Exception as e: print(f"[ERROR] {i}/{len(records)}: {e}")
if __name__ == "__main__": main()