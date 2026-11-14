# O3 SQL Server concurrency verification

Verified on 14–15 November 2026, branch `fix/o3-db-concurrency`. Before changes,
`HEAD`, local `origin/main` and `git ls-remote origin refs/heads/main` all resolved
to `43f987f107071cf515e40fba1b6d2e88cd14188a` (including C9 / PR #23).
PR #22 remained a separate draft on `ovidiu`; no Git refs or teammate files changed.

## Confirmed FileGridStorage defect

The original adapter failed a workload of 50 distinct-key puts with 12 workers.
The final baseline repetition produced **47 SQL 1222 lock timeouts and 2 SQL 1205
victims**, leaving only one successful put. These were JDBC calls directly to
`FileGridStorage` as `filegrid_storage_app`, without HTTP, ingestion retries or C9.
All test keys and committed physical versions were removed by test cleanup.

Historical graphs from the reported simulator incident were not retained on the
available instance. New graphs were retrieved from the existing `system_health`
XEL files and ring buffer, without creating or changing an Extended Events session.
Three distinct graph timestamps from the baseline repetitions were
`2026-11-14T21:46:19.808Z`, `21:47:07.427Z`, and `21:47:44.999Z`.
File/ring copies were deduplicated by timestamp; a graph can contain several victims.

The first graph has a concrete cycle:

- SPID 90 owns an X key lock in `FileGridStorage.dbo.blob_chunks.PK_blob_chunks`
  and waits for U on `blob_versions.PK_blob_versions`, while deleting old versions.
- SPID 98 owns the latter X key lock and waits for U on the former, while executing
  `DELETE c FROM blob_chunks c JOIN blob_versions v ... WHERE v.logical_key = ? AND v.published = 1`.

Cached plans show a clustered scan of `blob_chunks` for this joined DELETE,
followed by lookups into `blob_versions`. Other cached plans for the same statement
use seeks, so the defect is plan/data dependent. The logical-key retirement UPDATE
also had a clustered scan of `blob_versions`. Filtering the final result by logical
key did not prevent locking another transaction's rows during the scan.
Different logical keys hold different application locks, so those locks cannot
prevent this cycle. Merely increasing timeouts or retrying ingestion does not fix it.

## O3 change and lock order

`publishVersion()` and `delete()` now first enumerate only their logical key's
versions through `IX_blob_versions_logical_key` with `FORCESEEK`. The result set is
closed before any mutation. Binary deletion and metadata deletion/retirement then
use the exact physical key. There is no joined binary DELETE or logical-key-wide
metadata mutation. The existing index suffices; no migration or permission change
is needed. The index name is now an explicit runtime dependency, already provided
by O3 initialization.

The transaction still acquires the per-key SQL application lock before storage
access. A put inserts its pending metadata and binary rows, removes/retire the
previous version by physical key, and publishes the new version before committing.
Delete enumerates the same key, then removes each version's binary rows and metadata.
Abort already targets one exact version. Reads retain their shared application lock
until the stream closes. No FileGridMeta transaction manager is involved.

This preserves pending-upload invisibility, atomic replacement, rollback on failed
streams, multipart finish receipts and exact-upload abort semantics. The input is
consumed once; no transaction replay, file buffering, snapshot isolation, global
serialization or timeout/pool increase was introduced. The list contains only version identifiers and flags, not file contents; its size
grows with the number of retained versions for that logical key.

## FileGridMeta remains independently affected — Catalina follow-up

The corrected API still reproduced metadata deadlocks. Its transactions are separate:

1. `MeterReadingRepository.tryBegin()` reserves readings and commits before storage.
2. O3 writes and commits in FileGridStorage.
3. `MeterReadingIngestionService` inserts `files`, then calls `attachFile()` to update
   `meter_readings` in a FileGridMeta transaction. Failure triggers compensation.

Actual metadata graphs show X locks on `files.PK_files` and U/X locks on
`meter_readings.UQ_meter_readings_meter_id_reading_ts`. An attachment scan can own
reading locks and wait for a file owned by another attachment, whose scan waits for
those readings. Compensation DELETEs also appear in these cycles. Cached plans for
both ATTACH_FILE and DELETE_PENDING scan the clustered reading index despite the
existing `IX_meter_readings_attempt_id`.

Catalina should review bounded targeting by attempt/reading keys and a consistent
files/readings acquisition order in attachment, duplicate classification and
compensation, with real concurrent metadata regression tests. Retries reduced final
failures but did not remove this cause. `isDeadlock()` also accepts the broad Spring
`CannotAcquireLockException`; vendor code 1205 should be distinguished from 1222 and
pool acquisition failures. Storage logs currently omit the SQL cause/code, so
observability should preserve those categories without exposing credentials.
No changes to these files, C9, S3Adapter or the simulator were made here.

C9 settings inspected: meter bulkhead 50; meta pool 40 / acquisition 3 s; storage
pool 30 / acquisition 750 ms; storage SQL lock timeout 1 s, statement timeout 4 s,
socket timeout 6 s. Metadata blocking can occupy its pool and amplify HTTP 503;
HTTP 429 is a separate, expected bulkhead rejection, not evidence of a SQL defect.

## Verification results

| Check | Result |
| --- | --- |
| `./mvnw test` | 255 discovered, 241 passed, 14 opt-in SQL tests skipped; 0 failures/errors |
| `./mvnw -Dfilegrid.sql.integration=true -Dtest=DbStorageAdapterSqlServerTest,DbStorageAdapterTest,MeterReadingRepositoryTest test` | 48/48 passed: 14 real SQL, 21 adapter unit, 13 repository unit |
| Simulator `package` (includes its tests) | 24/24 passed |
| `git diff --check` | Passed |

The final regression starts **50 workers / 50 distinct random keys**, using the
production-sized 30-connection storage pool, warmed before the start gate.
Non-repeatable input streams are never reset. Every object is read back, compared
byte-for-byte and by SHA-256, and checked for exactly one published version.
Independent JDBC cleanup verifies no test versions or binary rows remain, including
when a worker fails. Existing SQL tests cover ranges, commit/rollback, app-lock
release, permissions, multipart replacement, abort and stale completion. A unit test
specifically injects 1205 after consuming the source and verifies rollback/close
without acquiring another connection or retrying publication.

Simulator verification used a separate corrected API on `127.0.0.1:18080`, with
Flyway disabled and existing SQL/MinIO/FTP services; no containers were restarted.
Credentials were supplied only via child-process environment. The original normal
and peak profiles were used with 30-second generation windows, disjoint previously
empty meter ranges (9751000 and 9752000), and scenarios `o3_normal` / `o3_peak`.
Outstanding deliveries were allowed to finish within the simulator's retry policy.
Because rounds wait for completion, this is not a full five-minute capacity benchmark.
Some existing stack tests overlapped these windows; their load is an additional
limitation when interpreting throughput. Exact isolated O3 concurrency passes above
are the regression evidence, while the API graphs establish the separate metadata defect.

| Observed category | Normal (12 configured meters) | Peak (120 configured meters) |
| --- | ---: | ---: |
| Confirmed new batches, all DB | 12 | 65 |
| Confirmed persisted readings | 1,152 | 6,240 |
| S3 fallback batches | 0 | 0 |
| HTTP 201 | 12 | 65 |
| HTTP 200 idempotent responses | 0 | 10 |
| HTTP 429 / C9 counter delta | 0 | 214 |
| HTTP 503 | 16 | 133 |
| Simulator retransmissions | 16 | 357 |
| Metadata pool timeouts | 0 | 21 |
| Storage pool timeouts | 0 | 0 |
| FileGridMeta 1205 victims for this API process, from XE | 34 | 247 |
| New FileGridStorage 1205 victims | 0 | 0 |
| Storage-write failures logged by this API | 0 | 0 |
| Persisted duplicate reading groups | 0 | 0 |
| Pending/abandoned simulator readings at completion | 0 / 0 | 0 / 0 |

SQL 1222 was observed in the direct pre-fix workload. No 1222 surfaced in the
corrected workload logs; `system_health` deadlock reports alone are not a complete
1222 audit. One additional peak metadata victim was our read-only administrative
aggregate query, not an API request, and is excluded from the API counts above.
HTTP metrics are before/after deltas; SQL victim and HTTP retry counts are different
measurements and must not be added together as failed logical batches.

All 77 simulator objects also matched the size and SHA-256 in `files`. Cleanup used
an explicit 77-file/77-version manifest and asserted 7,392 attached readings in a
transaction before removing only this run's data. Afterwards, test files, readings,
versions and chunks were all zero. The original one storage version and one binary
row remained. Test API process was stopped; existing containers were left running.

## Transaction-log issue and limits

The server error log independently records cancellation of `FileGridStorage_log`
autogrowth after **6,128 ms at 2026-11-14 20:49:58.20 server time**, before this task's
workloads. The default trace also records an earlier successful 64 MiB log growth
lasting 3,743 ms. These timings can exceed the adapter's bounded statement/socket
budgets; they are not evidence of an application-lock cycle.

Initially storage data/log sizes were 1,224/648 MiB, with fixed 64 MiB growth and FULL
recovery. Both databases reported `log_reuse_wait_desc=NOTHING`; the storage log stats
reported no log backup timestamp. Existing integration tests naturally grew the
storage log to 840 MiB and metadata data file to 72 MiB; no ALTER DATABASE, shrink,
backup/recovery change, restart or volume removal was performed.

Recommendation for review: pre-size the log from measured sustained load and the
largest active transaction, check disk/volume latency and free space, and agree the
backup/recovery policy. Keep autogrowth as a reserve, not the normal capacity plan.
SQL Server 2022 can benefit from instant initialization for log growth up to 64 MiB;
this instance already uses 64 MiB, so blindly reducing growth is not a demonstrated
fix. See [Microsoft log sizing guidance](https://learn.microsoft.com/en-us/sql/relational-databases/logs/manage-the-size-of-the-transaction-log-file?view=sql-server-ver16)
and [instant initialization](https://learn.microsoft.com/en-us/sql/relational-databases/databases/database-instant-file-initialization?view=sql-server-ver16).

Large-object deletion remains a bounded-time risk: the existing C7 upload stack
suite printed abort/delete timeout warnings for its 1.1 GiB DB fixture, then its
own bounded-batch cleanup completed and the test suite passed. O3 did not extend
timeouts or redesign large-object reclamation here. Longer soak tests, multiple API
instances, page/table lock escalation under larger workloads, and infrastructure
latency still need separate validation; this change does not promise zero blocking
under every load.
