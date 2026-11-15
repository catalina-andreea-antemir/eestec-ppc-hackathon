# O6 SQL analytics

Run each numbered script in `FileGridMeta` on SQL Server 2022 (tested at compatibility level 160). Edit the `DECLARE` values at the top of the script to set the analysis parameters. Timestamps are UTC and time windows include the start and exclude the end.

| Script | Result and defaults |
| --- | --- |
| `01_meter_consumption_anomalies.sql` | Confirmed readings above the meter's median plus three sample standard deviations within the last 24 hours. Meters with fewer than two readings or zero variance yield no anomalies. The first result set is the requested page (20 rows by default, newest first). The second always returns one `total_items` row, including when the page is empty. Matches go to a temporary table rather than a table variable, so the scan can use a parallel plan. |
| `02_silent_meters.sql` | Known meters whose latest confirmed receipt at or before `@as_of_utc` is strictly older than 60 minutes. Reports completed minutes since `last_received_at` and independently exposes the latest sample's `last_reading_ts` and zone for the API. Receipts that tie contribute the same maximum timestamp. Meters with no confirmed reading cannot be identified from this schema. The latest sample comes from a grouped MAX per meter and is read back by the unique clustered key, with no per-row window functions. |
| `03_consumption_by_zone_hour.sql` | Reading count and sum of `kwh` by zone and UTC hour start over the last 24 hours. Uses confirmed readings and returns only observed zone-hour groups. |
| `04_outages_by_zone.sql` | Two result sets over the last 30 days. The first ranks the three schema zones by outage count, with shared ranks for ties, share of all outages, ratio to the mean across zones, and the earliest UTC peak hour when hours tie. The second groups observed outages by zone and UTC hour of day across the window. A missing station ID still counts as an outage. |
| `05_peak_vs_normal.sql` | One row per category and scenario slot. Defaults to the API's `normal` and `storm` labels and a 24-hour window for each. Set `@peak_scenario` to `N'peak'` for the simulator's peak profile. Counts recorded live operations except bulkhead rejections (`operation = 'rejected'`, counted separately by the API), divides by the full selected window for throughput, and reports p50/p95/p99 and error percentages. The p95 change is on the peak row and is NULL when either percentile is missing or the normal p95 is zero. |
| `06_storage_recommendations.sql` | Scores 12 category/storage combinations for a single operation, defaulting to `upload` over the last 30 days. Pools observed benchmark and live samples with equal weight per row and reports each source's count. Rank 1 identifies the highest-scoring observed storage for that category. |

O7 can read the anomaly page and then advance to the second result set for `totalItems`. It can calculate `totalPages` from that count and the requested page size. A JDBC caller should enable `SET NOCOUNT ON` or skip the update count from populating the table variable. A consumer that reads only the page rows cannot obtain the count when the page is empty.

The meter queries require a linked file with status `CONFIRMED` because ingestion reserves reading rows before confirming storage. Receipt reflects when the repository reserved or reclaimed the reading. A retry of an already-confirmed batch and the time when a pending batch became confirmed are unavailable as separate communication events in this query. Outage rankings retain the three zones with zero counts and NULL shares, ratios, and peak hours when the window is empty. Empty performance groups have zero operations and NULL latency and error rates. Unmeasured storage combinations have zero samples and NULL measurements, scores, and ranks.

`operation_metrics` records operations. The schema cannot supply distinct HTTP request counts or bulkhead rejection totals. The API contract sources bulkhead rejections from `filegrid_bulkhead_rejected_total` in Prometheus.

## Storage score

The documented relative costs per GB are FTP 1.0, S3 1.4, and DB 3.2. See [storage-costs.yml](storage-costs.yml) and the `costAssumption` values in the [API contract](../../docs/api/openapi.yaml). The SQL parameters carry the same defaults and can be overridden when running the query.

The score is the sum of three terms on a 0–100 scale.

- Latency, 50 points. Multiply 50 by `(best category p95 + 1 ms) / (storage p95 + 1 ms)`. The offset permits zero-latency samples.
- Reliability, 35 points. Multiply 35 by the fraction of operations with `ok = 1`.
- Cost, 15 points. Multiply 15 by `lowest configured cost / storage cost`.

Rank uses the score before rounding. Each category is scored against the measured alternatives in the selected window. Changes in sample mix, including payload sizes or the ratio of live to benchmark traffic, can change the ranking.

## Verification

From the repository root, with the local Compose stack running and Flyway migrations applied, run the verifier with Python 3 and the container's `sqlcmd`.

```bash
python3 db/analytics-queries/verification/verify.py -- \
  docker compose exec -T mssql bash -c \
  'SQLCMDPASSWORD="$MSSQL_SA_PASSWORD" exec /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C "$@"' sqlcmd
```

The verifier first runs the six scripts unchanged against `FileGridMeta`. For assertions it copies the migrated column definitions into session-local temporary tables and runs the same SQL against those tables. It checks empty data, anomaly pagination, confirmed-reading handling, inactivity boundaries, outage ties, and metrics window and source filters. [The operation-metrics seed](../seed/o6_operation_metrics_fixture.sql) contains synthetic test inputs for the two performance queries. The temporary tables disappear when each test session ends.

The meter and outage analyses have SQL-level coverage independently of C12. Final integration testing for normal versus peak needs live rows recorded by C12 under the intended scenario labels. Storage recommendations also need measured samples from C12's benchmark endpoint across the category/storage matrix and representative live traffic.
