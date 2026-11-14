# O7 SQL analytics

The four `GET /api/v1/stats/*` routes use the existing JWT and require `DISPATCH` or `ADMIN`. Queries use the `db` profile's FileGridMeta connection. The API packages the six O6 scripts and the relative-cost YAML from [db/analytics-queries](../../db/analytics-queries/README.md) and binds the declaration values with JDBC. Only the test suite loads synthetic fixtures.

- `anomalies` defaults to the last 24 hours, a 60-minute inactivity threshold, page 1 and size 20. The SQL count is independent of the page, including when the page is empty. Inactivity is evaluated as of `to`, using the latest confirmed receipt through that instant. The reported `lastReadingTs` and zone come from the latest eligible reading. Hourly consumption includes observed UTC buckets.
- `outage-zones` defaults to 30 days and returns the three O6 ranks and the observed UTC hour-of-day counts. When a zone has no outages, `peakHour` is `null`. When the whole window has no outages, `sharePercent` and `vsAverageRatio` are also `null` for all three zones. The API preserves the SQL results and returns 200 so counts and rankings remain available. Angular already handles these null values as part of F6. The OpenAPI schema now declares `nullable: true` for `sharePercent`, `vsAverageRatio`, and `peakHour`.
- Both interval endpoints default `to` to the current UTC time and derive a missing `from` from `to`. The range is half-open. Explicit timestamps require a timezone offset and must be representable as SQL Server `datetime2(7)` after conversion to UTC.

## Outage consistency

O6 query 04 reads `outage_records` once into the batch-local `@zone_hours` table variable. Both the ranking and hourly distribution read those same aggregates. The repository uses the connection's normal isolation without an enclosing `SERIALIZABLE` transaction. Inserts after materialization can finish while the response is being calculated and appear in a later request. Ordinary read locks can still apply during the initial aggregate scan.

The query preserves half-open UTC bounds, shared dense ranks, earliest-hour tie breaking, three schema zones, and SQL NULL values for undefined ratios and peak hours. JDBC skips the table-variable INSERT update count before reading the two result sets.

## C12 and shared contract gaps

| Endpoint | Current behavior and evidence needed |
| --- | --- |
| `peak-vs-normal` | Runs O6 query 05 on live rows only, using two 24-hour windows with the same UTC boundaries and configurable scenario labels (defaults `normal` and `storm`). `operations` and operations per minute count database rows. The required `requests`, request throughput and `rejected` fields need distinct HTTP-request counts and category/scenario-aligned bulkhead counters for both windows. The contract names `filegrid_bulkhead_rejected_total`, whose specified labels currently identify category but omit scenario. Empty groups also lack the required percentiles and error rate. The change in p95 is undefined when either percentile is missing or normal p95 is zero. The endpoint returns a JSON 503 with `details.reason=REQUEST_METRICS_UNAVAILABLE` and lists the unavailable fields. Finishing the operation recorder alone still leaves the request/rejection source and undefined-value representation to be agreed. The endpoint also returns the standard JSON 400 for invalid scenario labels. The peak comparison operation currently omits that response from its OpenAPI response list. |
| `storage-advisor` | Runs O6 query 06 for `upload` over 30 days and uses its relative costs, observed scores and ranks. The contract requires all 12 cells to carry numeric p50, p95, error rate and score. Missing measurements leave those columns undefined. The endpoint returns a JSON 503 with `details.reason=STORAGE_MEASUREMENTS_INCOMPLETE` until every one of the 12 category/storage cells has a score and at least one benchmark sample in the selected window. Live samples supplement benchmark evidence. The contract specifies no positive minimum for live samples, so a complete benchmark matrix may return recommendations with an actual live sample count of zero. Once measured, it compares each rank-1 storage with the current primary policy. Exact ties use the first storage in O6 order. Benchmark and live sample counts come from O6. The optional `benchmarkRunAt` is omitted because operation timestamps cannot identify a benchmark run. |

The unavailable responses reuse `STORAGE_UNAVAILABLE`, `Retry-After` and `X-Trace-Id`. The shared OpenAPI update covers the three nullable `outage-zones` fields.

## Verification

From `backend/filegrid-api` with Java 21 and the local SQL Server stack running:

```sh
bash mvnw -Dfilegrid.sql.integration=true -Dtest=StatsApiTest,StatsRepositorySqlServerTest test
```

The JDBC tests use the FileGridMeta app account and session-local tables based on the migrated schema. They read `META_DB_PASSWORD` from the environment or the root `.env`. Set `O7_TEST_META_DB_URL` if SQL Server is listening on an address other than the local Compose port.

The concurrent-insert tests use two JDBC connections and uniquely named global temporary tables that are removed after each test. A test-only SQL barrier pauses the reader after materialization. The writer inserts an outage with a one-second lock timeout, then releases the reader. Both result sets must exactly match the pre-insert results for empty and populated datasets, and the inserted row must be committed.
