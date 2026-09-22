import calendar
import json
import os
import re
import time
from datetime import date, datetime, timezone
import requests

GRAPHQL_URL = "https://leetcode.com/graphql/"

HEADERS = {
    "Content-Type": "application/json",
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Referer": "https://leetcode.com/discuss/interview-experience",
}

TOPIC_LIST_QUERY = """
query categoryTopicList($categories: [String!]!, $first: Int!, $after: String, $query: String) {
  categoryTopicList(categories: $categories, first: $first, after: $after, query: $query) {
    totalNum
    edges {
      cursor
      node {
        id
        title
        commentCount
        viewCount
        pinned
        tags { name slug }
        post {
          id
          voteCount
          creationDate
          content
          author { username }
        }
      }
    }
    pageInfo { hasNextPage endCursor }
  }
}
"""

EXCLUDE_TITLE_PATTERNS = [
    r"\b(referral|refer me|need referral|looking for referral)\b",
    r"\b(how to prepare|preparation strategy|resources for|roadmap)\b",
    r"\b(help needed|guidance needed|advice needed|can someone help)\b",
    r"\b(resume review|roast my resume)\b",
    r"\b(compensation|salary comparison|tc comparison)\b",
]

def is_real_interview_experience(title: str, content: str) -> bool:
    title_lower = title.lower()
    if any(re.search(pattern, title_lower) for pattern in EXCLUDE_TITLE_PATTERNS):
        return False

    content_lower = content.lower() if content else ""
    experience_cues = [
        "round 1", "round 2", "round 3", "onsite", "phone screen", "oa",
        "online assessment", "technical round", "behavioral round",
        "offer", "rejected", "result", "interview experience",
    ]
    matches = sum(
        1 for cue in experience_cues
        if cue in content_lower or cue in title_lower
    )
    return matches >= 2 and len(content_lower) > 200


def subtract_months(value: date, months: int) -> date:
    month_index = value.year * 12 + (value.month - 1) - months
    year = month_index // 12
    month = month_index % 12 + 1
    day = min(value.day, calendar.monthrange(year, month)[1])
    return date(year, month, day)


def parse_creation_date(value):
    if value is None:
        return None
    try:
        # LeetCode may return creationDate as either an ISO string or a Unix timestamp.
        if isinstance(value, (int, float)):
            return datetime.fromtimestamp(value, tz=timezone.utc).date()
        normalized = str(value).replace("Z", "+00:00")
        return datetime.fromisoformat(normalized).date()
    except (TypeError, ValueError, OverflowError):
        return None


def month_key(value: date) -> str:
    return value.strftime("%Y-%m")


def month_file(output_dir: str, month: str) -> str:
    return os.path.join(output_dir, f"leetcode_interviews_{month}.jsonl")


def convert_jsonl_to_json(jsonl_file: str, json_file: str):
    records = []
    if os.path.exists(jsonl_file):
        with open(jsonl_file, "r", encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    records.append(json.loads(line))

    with open(json_file, "w", encoding="utf-8") as f:
        json.dump(records, f, indent=2, ensure_ascii=False)

    print(f"Converted {len(records)} records into {json_file}", flush=True)


def crawl_interview_experiences(output_dir=None, checkpoint_file=None, lookback_months=None):
    output_dir = output_dir or os.getenv("OUTPUT_DIR", "/data")
    checkpoint_file = checkpoint_file or os.getenv("CHECKPOINT_FILE", "/data/checkpoint.txt")
    lookback_months = lookback_months or int(os.getenv("LOOKBACK_MONTHS", "24"))

    os.makedirs(output_dir, exist_ok=True)

    today = datetime.now(timezone.utc).date()
    cutoff_date = subtract_months(today, lookback_months)

    print(
        f"Starting LeetCode interview crawl. "
        f"Lookback: {lookback_months} months; cutoff: {cutoff_date}; "
        f"output: {output_dir}",
        flush=True,
    )

    after_cursor = ""
    if os.path.exists(checkpoint_file):
        with open(checkpoint_file, "r", encoding="utf-8") as f:
            after_cursor = f.read().strip()
            if after_cursor:
                print(f"Resuming from cursor: {after_cursor}", flush=True)

    session = requests.Session()
    session.headers.update(HEADERS)

    has_next = True
    page = 1
    total_saved = 0
    total_seen = 0
    oldest_seen = None

    while has_next:
        payload = {
            "query": TOPIC_LIST_QUERY,
            "variables": {
                "categories": ["interview-experience"],
                "first": 50,
                "after": after_cursor or None,
                "query": "",
            },
            "operationName": "categoryTopicList",
        }

        try:
            response = session.post(GRAPHQL_URL, json=payload, timeout=15)

            if response.status_code == 429:
                print("Rate limited (429). Sleeping for 60 seconds...", flush=True)
                time.sleep(60)
                continue

            if response.status_code != 200:
                print(f"Error {response.status_code}: {response.text[:500]}", flush=True)
                raise RuntimeError(
                    f"LeetCode GraphQL request failed with HTTP {response.status_code}"
                )

            data = response.json()
            if data.get("errors"):
                print(
                    f"GraphQL errors: {json.dumps(data['errors'], ensure_ascii=False)}",
                    flush=True,
                )
                raise RuntimeError("LeetCode GraphQL request failed")

            topic_data = data.get("data", {}).get("categoryTopicList", {})
            edges = topic_data.get("edges", [])
            page_info = topic_data.get("pageInfo", {})

            if not edges:
                print("No more topics found.", flush=True)
                break

            page_saved = 0
            page_dates = []

            for edge in edges:
                node = edge.get("node", {})
                post = node.get("post") or {}
                total_seen += 1

                creation_date = parse_creation_date(post.get("creationDate"))
                if creation_date:
                    page_dates.append(creation_date)
                    if oldest_seen is None or creation_date < oldest_seen:
                        oldest_seen = creation_date

                # Freshness is a hard gate. Undated posts are excluded.
                if creation_date is None or creation_date < cutoff_date:
                    continue

                title = node.get("title", "")
                content = post.get("content", "")

                if not is_real_interview_experience(title, content):
                    continue

                slug = re.sub(r"[^a-zA-Z0-9]+", "-", title.lower()).strip("-")
                topic_id = node.get("id")
                post_url = (
                    f"https://leetcode.com/discuss/interview-experience/"
                    f"{topic_id}/{slug}"
                )

                record = {
                    "id": topic_id,
                    "url": post_url,
                    "title": title,
                    "page_content": content,
                    "tags": [
                        t.get("name")
                        for t in node.get("tags", [])
                        if t.get("name")
                    ],
                    "votes": post.get("voteCount", 0),
                    "views": node.get("viewCount", 0),
                    "creation_date": post.get("creationDate"),
                    "author": (post.get("author") or {}).get("username"),
                }

                target_month = month_key(creation_date)
                target_file = month_file(output_dir, target_month)
                with open(target_file, "a", encoding="utf-8") as out_f:
                    out_f.write(json.dumps(record, ensure_ascii=False) + "\n")

                total_saved += 1
                page_saved += 1

            has_next = page_info.get("hasNextPage", False)
            after_cursor = page_info.get("endCursor", "")

            with open(checkpoint_file, "w", encoding="utf-8") as cf:
                cf.write(after_cursor or "")

            oldest_page = min(page_dates) if page_dates else None
            print(
                f"Page {page} processed. "
                f"Page saved: {page_saved}. Total saved: {total_saved}. "
                f"Oldest on page: {oldest_page}. Cutoff: {cutoff_date}.",
                flush=True,
            )

            # The API currently returns the connection in descending creation order.
            # Stop once the page has crossed the six-month boundary.
            if oldest_page and oldest_page < cutoff_date:
                print(
                    f"Reached lookback boundary ({oldest_page} < {cutoff_date}). "
                    f"Stopping crawl.",
                    flush=True,
                )
                break

            page += 1
            time.sleep(1.5)

        except Exception as e:
            print(f"Exception during request: {e}. Retrying in 10s...", flush=True)
            time.sleep(10)

    # Convert each monthly JSONL file into a JSON file.
    monthly_files = sorted(
        filename for filename in os.listdir(output_dir)
        if filename.startswith("leetcode_interviews_") and filename.endswith(".jsonl")
    )
    for filename in monthly_files:
        jsonl_path = os.path.join(output_dir, filename)
        json_path = os.path.splitext(jsonl_path)[0] + ".json"
        convert_jsonl_to_json(jsonl_path, json_path)

    print(
        f"Crawl completed. Seen: {total_seen}; saved: {total_saved}; "
        f"oldest seen: {oldest_seen}; cutoff: {cutoff_date}.",
        flush=True,
    )


if __name__ == "__main__":
    crawl_interview_experiences()
