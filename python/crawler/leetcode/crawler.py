import json
import os
import re
import time
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


def crawl_interview_experiences(
    output_file=None,
    checkpoint_file=None,
):
    output_file = output_file or os.getenv("OUTPUT_FILE", "leetcode_interviews.jsonl")
    checkpoint_file = checkpoint_file or os.getenv("CHECKPOINT_FILE", "checkpoint.txt")
    after_cursor = ""
    if os.path.exists(checkpoint_file):
        with open(checkpoint_file, "r", encoding="utf-8") as f:
            after_cursor = f.read().strip()
            if after_cursor:
                print(f"Resuming from cursor: {after_cursor}")

    session = requests.Session()
    session.headers.update(HEADERS)

    has_next = True
    page = 1
    total_saved = 0

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
                print("Rate limited (429). Sleeping for 60 seconds...")
                time.sleep(60)
                continue

            if response.status_code != 200:
                print(f"Error {response.status_code}: {response.text[:500]}", flush=True)
                if response.status_code == 429:
                    time.sleep(60)
                    continue
                raise RuntimeError(f"LeetCode GraphQL request failed with HTTP {response.status_code}")

            data = response.json()
            if data.get("errors"):
                print(f"GraphQL errors: {json.dumps(data["errors"], ensure_ascii=False)}", flush=True)
                raise RuntimeError("LeetCode GraphQL request failed")
            topic_data = data.get("data", {}).get("categoryTopicList", {})
            edges = topic_data.get("edges", [])
            page_info = topic_data.get("pageInfo", {})

            if not edges:
                print("No more topics found.")
                break

            with open(output_file, "a", encoding="utf-8") as out_f:
                for edge in edges:
                    node = edge.get("node", {})
                    post = node.get("post") or {}

                    title = node.get("title", "")
                    content = post.get("content", "")
                    topic_id = node.get("id")

                    if not is_real_interview_experience(title, content):
                        continue

                    slug = re.sub(
                        r"[^a-zA-Z0-9]+", "-", title.lower()
                    ).strip("-")
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

                    out_f.write(
                        json.dumps(record, ensure_ascii=False) + "\n"
                    )
                    total_saved += 1

            has_next = page_info.get("hasNextPage", False)
            after_cursor = page_info.get("endCursor", "")

            with open(checkpoint_file, "w", encoding="utf-8") as cf:
                cf.write(after_cursor or "")

            print(
                f"Page {page} processed. Total saved: {total_saved}. "
                f"Next cursor: {after_cursor}"
            )
            page += 1
            time.sleep(1.5)

        except Exception as e:
            print(f"Exception during request: {e}. Retrying in 10s...")
            time.sleep(10)

    print(
        f"Crawl completed. Extracted {total_saved} real interview "
        f"experiences to {output_file}."
    )


def convert_jsonl_to_json(
    jsonl_file="leetcode_interviews.jsonl",
    json_file="leetcode_interviews.json",
):
    records = []
    if os.path.exists(jsonl_file):
        with open(jsonl_file, "r", encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    records.append(json.loads(line))

        with open(json_file, "w", encoding="utf-8") as f:
            json.dump(records, f, indent=2, ensure_ascii=False)

        print(f"Converted {len(records)} records into {json_file}")


if __name__ == "__main__":
    output_file = os.getenv("OUTPUT_FILE", "leetcode_interviews.jsonl")
    crawl_interview_experiences(output_file=output_file)
    convert_jsonl_to_json(
        jsonl_file=output_file,
        json_file=os.path.splitext(output_file)[0] + ".json",
    )
