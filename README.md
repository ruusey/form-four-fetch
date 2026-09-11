# form-four-fetch

Standalone Spring Boot 3 service that polls the SEC EDGAR Form 4 atom feed,
parses each new ownership document, persists it to MongoDB, and publishes
new filings over a STOMP WebSocket on `/topic/filings`.

Extracted and modernized from the `tradenet` monolith — Form 4 only.

## Run

```
mvn spring-boot:run
```

Set `MONGO_URI` and `FORM_FOUR_UA` env vars (the SEC requires a
descriptive User-Agent identifying the requester). Set `DISCORD_WEBHOOK_URL`
to also post new filings to a Discord channel (optional; posting is skipped
when unset).

The app listens on port `8085` by default (`server.port` in
`application.yml`).

## API explorer

A self-contained web UI is served at the app root — just start the app and
open **<http://localhost:8085/>**. No build step or separate server: it's a
static `index.html` under `src/main/resources/static/`.

It has a panel for every REST endpoint below, plus a **live feed** that
subscribes to the WebSocket and streams new Form 4s as they arrive. Filing
cards highlight buys (green) / sells (orange) and flag **abnormal**
transactions in red with their anomaly score and reasons. The backfill
actions are gated behind a confirmation dialog, so nothing long-running
starts by accident.

## REST endpoints

- `GET /api/form4` — paged list of saved filings (params: `page`, `size`)
- `GET /api/form4/anomalies` — filings whose most abnormal buy/sell leg
  scored at/above `minScore` (params: `minScore`, `page`, `size`)
- `GET /api/form4/{cik}/{accession}` — fetch + parse a specific filing
- `GET /api/form4/by-entity/{ticker}` — saved filings for a ticker
- `GET /api/form4/recent` — pull the EDGAR atom feed and return parsed
  filings without persisting
- `POST /api/form4/backfill/recent` — throttled backfill of every Form 4
  filed in the last `days` (default 30) via the EDGAR daily index
- `POST /api/form4/backfill/{cikOrTicker}` — throttled backfill of one
  issuer's recent Form 4s (param: `max`)

## Anomaly detection

Each open-market buy (P) / sell (S) leg is scored against a rolling
per-ticker baseline; anything outside a ticker's trained normal range is
written to a dedicated log at `logs/abnormal-transactions.log` and flagged
in the API/Discord output. Backfilling history (see the endpoints above)
seeds these baselines. Tuning knobs live under `formfour.anomaly.*` in
`application.yml`.

## WebSocket

- STOMP endpoint: `ws://localhost:8085/ws` (SockJS enabled)
- Subscribe to `/topic/filings` to receive `OwnershipDocument` payloads as
  they arrive.
