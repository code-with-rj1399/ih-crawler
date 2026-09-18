-- InterviewHQ shared schema
-- PostgreSQL (AWS RDS in prod, Neon on deploy, PGLite in preview)
-- Consumed by ih-crawler and the backend. Do not add extensions.

create table if not exists companies (
  id          serial primary key,
  name        text not null unique,
  slug        text not null unique,
  aliases     jsonb not null default '[]'::jsonb,
  created_at  timestamptz not null default now()
);

create table if not exists crawl_sources (
  id                     serial primary key,
  slug                   text not null unique,
  name                   text not null,
  url                    text not null,
  source_kind            text not null,
  enabled                boolean not null default true,
  rate_limit_rpm         integer not null default 8,
  crawl_delay_ms         integer not null default 1500,
  per_host_concurrency   integer not null default 1,
  robots_mode            text not null default 'honor',
  parser_config          jsonb not null default '{}'::jsonb,
  last_crawled_at        timestamptz,
  last_success_at        timestamptz,
  last_http_status       integer,
  last_error             text,
  consecutive_failures   integer not null default 0,
  circuit_open_until     timestamptz,
  notes                  text,
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now()
);

create table if not exists crawler_config (
  key          text primary key,
  value        text not null,
  description  text,
  updated_at   timestamptz not null default now()
);

create table if not exists crawl_jobs (
  id                  serial primary key,
  status              text not null default 'queued',
  trigger             text not null default 'manual',
  lookback_hours      integer not null default 24,
  started_at          timestamptz,
  finished_at         timestamptz,
  sources_planned     integer not null default 0,
  sources_ok          integer not null default 0,
  sources_failed      integer not null default 0,
  pages_fetched       integer not null default 0,
  pages_skipped       integer not null default 0,
  posts_discovered    integer not null default 0,
  posts_extracted     integer not null default 0,
  questions_upserted  integer not null default 0,
  http_429_count      integer not null default 0,
  blocked_count       integer not null default 0,
  error_summary       text,
  created_at          timestamptz not null default now()
);

create table if not exists crawl_job_logs (
  id          serial primary key,
  job_id      integer not null references crawl_jobs(id) on delete cascade,
  source_id   integer references crawl_sources(id) on delete set null,
  level       text not null default 'info',
  event_code  text not null default 'note',
  message     text not null,
  meta        jsonb not null default '{}'::jsonb,
  created_at  timestamptz not null default now()
);

create table if not exists crawl_pages (
  id              serial primary key,
  source_id       integer references crawl_sources(id) on delete set null,
  job_id          integer references crawl_jobs(id) on delete set null,
  url             text not null unique,
  canonical_url   text,
  http_status     integer,
  content_type    text,
  content_hash    text,
  body_excerpt    text,
  etag            text,
  robots_allowed  boolean,
  published_at    timestamptz,
  fetched_at      timestamptz not null default now(),
  error           text
);

create table if not exists interview_posts (
  id               serial primary key,
  source_id        integer references crawl_sources(id) on delete set null,
  page_id          integer references crawl_pages(id) on delete set null,
  job_id           integer references crawl_jobs(id) on delete set null,
  external_id      text,
  url              text not null,
  title            text,
  author           text,
  posted_at        timestamptz,
  raw_company      text,
  raw_role         text,
  body_text        text,
  content_hash     text,
  extracted        boolean not null default false,
  extraction_json  jsonb,
  created_at       timestamptz not null default now(),
  unique (source_id, url)
);

create table if not exists interview_questions (
  id              serial primary key,
  post_id         integer references interview_posts(id) on delete cascade,
  company         text,
  role            text,
  level           text,
  round_type      text,
  question_type   text,
  question_text   text not null,
  asked_at        date,
  location        text,
  poster_name     text,
  difficulty      text,
  topics          jsonb not null default '[]'::jsonb,
  confidence      real,
  model_name      text,
  dedupe_hash     text unique,
  extracted_at    timestamptz not null default now(),
  created_at      timestamptz not null default now()
);

create index if not exists crawl_sources_kind_idx on crawl_sources (source_kind);
create index if not exists crawl_jobs_created_idx on crawl_jobs (created_at desc);
create index if not exists crawl_job_logs_job_idx on crawl_job_logs (job_id, id);
create index if not exists crawl_pages_source_idx on crawl_pages (source_id, fetched_at desc);
create index if not exists interview_posts_posted_idx on interview_posts (posted_at desc);
create index if not exists interview_questions_company_idx on interview_questions (company);
create index if not exists interview_questions_asked_idx on interview_questions (asked_at desc);
create index if not exists interview_questions_type_idx on interview_questions (question_type);

insert into companies (name, slug, aliases) values
  ('Google', 'google', '["Alphabet","GCP"]'::jsonb),
  ('Amazon', 'amazon', '["AWS","Amazon.com"]'::jsonb),
  ('Meta', 'meta', '["Facebook","Instagram"]'::jsonb),
  ('Microsoft', 'microsoft', '["Azure","MSFT"]'::jsonb),
  ('Apple', 'apple', '["AAPL"]'::jsonb),
  ('Netflix', 'netflix', '[]'::jsonb),
  ('Uber', 'uber', '[]'::jsonb),
  ('Stripe', 'stripe', '[]'::jsonb),
  ('OpenAI', 'openai', '[]'::jsonb),
  ('xAI', 'xai', '[]'::jsonb),
  ('Anthropic', 'anthropic', '[]'::jsonb),
  ('Oracle', 'oracle', '[]'::jsonb),
  ('Bloomberg', 'bloomberg', '[]'::jsonb),
  ('DoorDash', 'doordash', '[]'::jsonb),
  ('Walmart', 'walmart', '[]'::jsonb)
on conflict (slug) do nothing;

insert into crawler_config (key, value, description) values
  ('cron.enabled', 'true', 'Run ih-crawler on an interval'),
  ('cron.interval_minutes', '60', 'Minutes between automatic crawl jobs'),
  ('crawl.lookback_hours', '24', 'Only ingest posts newer than this many hours'),
  ('crawl.max_concurrency', '4', 'Max sources fetched in parallel'),
  ('crawl.per_host_concurrency', '1', 'Max in-flight requests per hostname'),
  ('crawl.user_agent', 'InterviewHQBot/1.0 (ih-crawler; +https://interviewhq.ai/bot)', 'Identifying User-Agent sent to origin servers'),
  ('crawl.timeout_ms', '15000', 'Per-request timeout'),
  ('crawl.max_bytes', '1048576', 'Max response body bytes retained'),
  ('crawl.max_retries', '3', 'Retries on 429/503/network errors'),
  ('extract.enabled', 'true', 'Run Grok extraction after a crawl'),
  ('extract.model', 'grok-4.5', 'xAI model used to structure interview posts'),
  ('extract.max_posts_per_job', '12', 'Cap LLM calls per job (quota guard)'),
  ('extract.max_tokens', '1200', 'Max tokens per extraction completion')
on conflict (key) do nothing;

-- 15 seed sources. Protected sites (LeetCode / Glassdoor / Blind) are included
-- with very low RPM. The crawler honors robots.txt and opens a circuit on 403.
insert into crawl_sources
  (slug, name, url, source_kind, rate_limit_rpm, crawl_delay_ms, per_host_concurrency, parser_config, notes)
values
  (
    'gfg-interview-rss',
    'GeeksforGeeks Interview Experiences',
    'https://www.geeksforgeeks.org/category/interview-experiences/feed/',
    'rss', 10, 1200, 1,
    '{"entryKind":"rss"}'::jsonb,
    'Public RSS. High signal for company + round writeups.'
  ),
  (
    'reddit-leetcode',
    'Reddit r/leetcode',
    'https://www.reddit.com/r/leetcode/new.json',
    'reddit_json', 8, 2000, 1,
    '{"subreddit":"leetcode"}'::jsonb,
    'Official JSON listing. Requires a descriptive User-Agent.'
  ),
  (
    'reddit-cscareerquestions',
    'Reddit r/cscareerquestions',
    'https://www.reddit.com/r/cscareerquestions/new.json',
    'reddit_json', 8, 2000, 1,
    '{"subreddit":"cscareerquestions"}'::jsonb,
    'Career + interview debriefs.'
  ),
  (
    'reddit-csmajors',
    'Reddit r/csMajors',
    'https://www.reddit.com/r/csMajors/new.json',
    'reddit_json', 8, 2000, 1,
    '{"subreddit":"csMajors"}'::jsonb,
    'New-grad OA / onsite reports.'
  ),
  (
    'reddit-experienceddevs',
    'Reddit r/ExperiencedDevs',
    'https://www.reddit.com/r/ExperiencedDevs/new.json',
    'reddit_json', 8, 2000, 1,
    '{"subreddit":"ExperiencedDevs"}'::jsonb,
    'Senior-loop writeups.'
  ),
  (
    'hn-interview',
    'Hacker News — interview experience',
    'https://hn.algolia.com/api/v1/search_by_date?query=interview%20experience&tags=story',
    'hn_algolia', 20, 400, 1,
    '{"query":"interview experience"}'::jsonb,
    'Algolia public API. Filter by created_at_i in the adapter.'
  ),
  (
    'devto-interview',
    'Dev.to interview tag',
    'https://dev.to/feed/tag/interview',
    'rss', 12, 800, 1,
    '{"tag":"interview"}'::jsonb,
    'Public RSS of tagged posts.'
  ),
  (
    'hashnode-interview',
    'Hashnode interview tag',
    'https://hashnode.com/n/interview/rss',
    'rss', 10, 1000, 1,
    '{"tag":"interview"}'::jsonb,
    'Public RSS. Adapter falls back to the tag index if the feed 404s.'
  ),
  (
    'leetcode-experience',
    'LeetCode Discuss — Interview Experience',
    'https://leetcode.com/discuss/interview-experience',
    'leetcode_discuss', 4, 4000, 1,
    '{"category":"interview-experience"}'::jsonb,
    'Tries public GraphQL then HTML. Expect 403s; circuit-breaker will cool down.'
  ),
  (
    'leetcode-question',
    'LeetCode Discuss — Interview Question',
    'https://leetcode.com/discuss/interview-question',
    'leetcode_discuss', 4, 4000, 1,
    '{"category":"interview-question"}'::jsonb,
    'Same adapter as interview-experience, different category.'
  ),
  (
    'glassdoor-interviews',
    'Glassdoor Interviews',
    'https://www.glassdoor.com/Interview/index.htm',
    'glassdoor', 2, 8000, 1,
    '{}'::jsonb,
    'Heavy bot protection. Polite fetch + robots.txt; record blocks instead of bypassing them.'
  ),
  (
    'glassdoor-google',
    'Glassdoor — Google interviews',
    'https://www.glassdoor.com/Interview/Google-Interview-Questions-E9079.htm',
    'glassdoor', 2, 8000, 1,
    '{"company":"Google","employerId":"9079"}'::jsonb,
    'Company interview page. Same politeness rules as the index.'
  ),
  (
    'blind-interview',
    'Teamblind — Interview',
    'https://www.teamblind.com/s/Interview',
    'blind', 2, 8000, 1,
    '{"tag":"Interview"}'::jsonb,
    'Likely gated. Honor robots.txt, backoff on 403/429, open circuit.'
  ),
  (
    'blind-home',
    'Teamblind — Home',
    'https://www.teamblind.com/',
    'blind', 2, 8000, 1,
    '{}'::jsonb,
    'Home listing fallback for the Interview tag.'
  ),
  (
    'interviewbit-blog',
    'InterviewBit Blog',
    'https://www.interviewbit.com/blog/',
    'html', 8, 1500, 1,
    '{"itemSelector":"article a, .post-title a, h2 a","titleSelector":"h1, h2","bodySelector":"article, .post-content, .entry-content"}'::jsonb,
    'Generic HTML list adapter.'
  )
on conflict (slug) do nothing;
