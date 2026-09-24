# InterviewHQ Admin UI

Next.js admin dashboard for crawler operations.

## Run

```bash
cd ui
npm install
cp .env.example .env.local
npm run dev
```

Open `/admin`.

Set `NEXT_PUBLIC_HQ_API_URL` to the Spring Boot crawler/hq-API base URL.

## Admin APIs used

- `GET/PUT /admin/crawl/config`
- `GET/POST /admin/seeds`
- `PUT /admin/seeds/{id}/enabled`
- `GET /admin/crawls`
- `GET /admin/crawls/{id}`
- `GET /admin/crawls/{id}/subruns`
