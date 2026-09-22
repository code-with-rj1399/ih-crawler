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


def chunk_file(output_dir: str, chunk_number: int) -> str:
    return os.path.join(
        output_dir,
        f"leetcode_interviews_{chunk_number:05d}.json",
    )


def write_chunk(output_dir: str, chunk_number: int, records: list[dict]) -> None:
    if not records:
        return

    path = chunk_file(output_dir, chunk_number)
    with open(path, "w", encoding="utf-8") as out_f:
        json.dump(records, out_f, indent=2, ensure_ascii=False)

    print(
        f"Wrote chunk {chunk_number}: {len(records)} records -> {path}",
        flush=True,
    )


def load_checkpoint(checkpoint_file: str):
    if not os.path.exists(checkpoint_file):
        return "", 0, 1

    with open(checkpoint_file, "r", encoding="utf-8") as f:
        raw = f.read().strip()

    if not raw:
        return "", 0, 1

    # Backward compatibility with the old cursor-only checkpoint.
    try:
        checkpoint = json.loads(raw)
        if isinstance(checkpoint, dict):
            return (
                checkpoint.get("cursor", ""),
                int(checkpoint.get("saved", 0)),
                int(checkpoint.get("chunk", 1)),
            )
    except (json.JSONDecodeError, TypeError, ValueError):
        pass

    return raw, 0, 1


def save_checkpoint(
    checkpoint_file: str,
    cursor: str,
    saved: int,
    chunk_number: int,
) -> None:
    tmp_file = f"{checkpoint_file}.tmp"
    checkpoint = {
        "cursor": cursor or "",
        "saved": saved,
        "chunk": chunk_number,
    }

    with open(tmp_file, "w", encoding="utf-8") as f:
        json.dump(checkpoint, f)

    os.replace(tmp_file, checkpoint_file)


def crawl_interview_experiences(
    output_dir=None,
    checkpoint_file=None,
    target_records=None,
    chunk_size=None,
):
    output_dir = output_dir or os.getenv("OUTPUT_DIR", "/data")
    checkpoint_file = checkpoint_file or os.getenv(
        "CHECKPOINT_FILE",
        "/data/checkpoint.json",
    )
    target_records = target_records or int(
        os.getenv("TARGET_RECORDS", "20000")
    )
    chunk_size = chunk_size or int(
        os.getenv("CHUNK_SIZE", "100")
    )

    if target_records <= 0:
        raise ValueError("TARGET_RECORDS must be greater than zero")
    if chunk_size <= 0:
        raise ValueError("CHUNK_SIZE must be greater than zero")

    os.makedirs(output_dir, exist_ok=True)

    print(
        f"Starting LeetCode interview crawl. "
        f"Target: {target_records} qualifying records; "
        f"chunk size: {chunk_size}; output: {output_dir}",
        flush=True,
    )

    after_cursor, total_saved, chunk_number = load_checkpoint(checkpoint_file)

    if total_saved:
        print(
            f"Resuming: saved={total_saved}, next chunk={chunk_number}, "
            f"cursor={after_cursor}",
            flush=True,
        )

    session = requests.Session()
    session.headers.update(HEADERS)

    has_next = True
    page = 1
    total_seen = 0
    oldest_seen = None
    total_in_window = 0
    total_outside_window = 0
    total_rejected_as_non_experience = 0
    chunk_records = []

    while has_next and total_saved < target_records:
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
            response = session.post(
                GRAPHQL_URL,
                json=payload,
                timeout=15,
            )

            if response.status_code == 429:
                print(
                    "Rate limited (429). Sleeping for 60 seconds...",
                    flush=True,
                )
                time.sleep(60)
                continue

            if response.status_code != 200:
                print(
                    f"Error {response.status_code}: "
                    f"{response.text[:500]}",
                    flush=True,
                )
                raise RuntimeError(
                    f"LeetCode GraphQL request failed with "
                    f"HTTP {response.status_code}"
                )

            data = response.json()

            if data.get("errors"):
                print(
                    f"GraphQL errors: "
                    f"{json.dumps(data['errors'], ensure_ascii=False)}",
                    flush=True,
                )
                raise RuntimeError("LeetCode GraphQL request failed")

            topic_data = data.get("data", {}).get(
                "categoryTopicList",
                {},
            )
            edges = topic_data.get("edges", [])
            page_info = topic_data.get("pageInfo", {})

            if not edges:
                print("No more topics found.", flush=True)
                break

            page_saved = 0
            page_dates = []

            for edge in edges:
                if total_saved >= target_records:
                    break

                node = edge.get("node", {})
                post = node.get("post") or {}
                total_seen += 1

                creation_date = parse_creation_date(
                    post.get("creationDate")
                )

                if creation_date:
                    page_dates.append(creation_date)
                    if oldest_seen is None or creation_date < oldest_seen:
                        oldest_seen = creation_date

                if creation_date is None or creation_date < cutoff_date:
                    total_outside_window += 1
                    continue

                total_in_window += 1

                title = node.get("title", "")
                content = post.get("content", "")

                if not is_real_interview_experience(title, content):
                    total_rejected_as_non_experience += 1
                    continue

                slug = re.sub(
                    r"[^a-zA-Z0-9]+",
                    "-",
                    title.lower(),
                ).strip("-")

                topic_id = node.get("id")
                post_url = (
                    "https://leetcode.com/discuss/"
                    f"interview-experience/{topic_id}/{slug}"
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

                chunk_records.append(record)
                total_saved += 1
                page_saved += 1

                if len(chunk_records) >= chunk_size:
                    write_chunk(
                        output_dir,
                        chunk_number,
                        chunk_records,
                    )
                    chunk_records = []
                    chunk_number += 1

            has_next = page_info.get("hasNextPage", False)
            after_cursor = page_info.get("endCursor", "")

            save_checkpoint(
                checkpoint_file,
                after_cursor,
                total_saved,
                chunk_number,
            )

            oldest_page = min(page_dates) if page_dates else None

            print(
                f"Page {page} processed. "
                f"Page saved: {page_saved}. "
                f"Total saved: {total_saved}/{target_records}. "
                f"In window: {total_in_window}. "
                f"Outside window: {total_outside_window}. "
                f"Rejected: {total_rejected_as_non_experience}. "
                f"Oldest on page: {oldest_page}.",
                flush=True,
            )

            if total_saved >= target_records:
                break

            if not has_next:
                print(
                    "LeetCode returned no next page before reaching "
                    f"the target of {target_records}.",
                    flush=True,
                )
                break

            page += 1
            time.sleep(1.5)

        except Exception as e:
            print(
                f"Exception during request: {e}. Retrying in 10s...",
                flush=True,
            )
            time.sleep(10)

    if chunk_records:
        write_chunk(
            output_dir,
            chunk_number,
            chunk_records,
        )

    print(
        f"Crawl completed. Seen: {total_seen}; "
        f"saved: {total_saved}; "
        f"in window: {total_in_window}; "
        f"outside window: {total_outside_window}; "
        f"rejected: {total_rejected_as_non_experience}; "
        f"oldest seen: {oldest_seen}; "
        f"target: {target_records}.",
        flush=True,
    )


if __name__ == "__main__":
    crawl_interview_experiences()
