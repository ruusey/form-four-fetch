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
descriptive User-Agent identifying the requester).

## REST endpoints

- `GET /api/form4` — paged list of saved filings (params: `page`, `size`)
- `GET /api/form4/{cik}/{accession}` — fetch + parse a specific filing
- `GET /api/form4/by-entity/{ticker}` — saved filings for a ticker
- `GET /api/form4/recent` — pull the EDGAR atom feed and return parsed
  filings without persisting

## WebSocket

- STOMP endpoint: `ws://localhost:8080/ws`
- Subscribe to `/topic/filings` to receive `OwnershipDocument` payloads as
  they arrive.
