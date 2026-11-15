# Aplicarea controlată a RCSI în FileGridMeta

`READ_COMMITTED_SNAPSHOT` (RCSI) schimbă citirile efectuate la `READ COMMITTED` în
`FileGridMeta`. Fiecare instrucțiune vede datele confirmate la începutul ei.
În `GET /files`, numărarea și pagina pot vedea commit-uri diferite. Scriitorii
pot continua să se blocheze între ei, iar hint-urile de blocare din
deduplicare rămân relevante.

Pe o bază existentă, tratați schimbarea ca pe o operațiune de mentenanță.
Folosiți aceeași procedură într-un proiect Compose izolat înaintea aplicării
în mediul țintă.

1. Verificați instanța SQL și baza țintă. Opriți intrarea de trafic, toate
   replicile API și orice alte procese, joburi sau clienți care deschid conexiuni
   în `FileGridMeta`. Lăsați SQL Server pornit.
2. Conectați-vă ca administrator la `master`, dintr-o sesiune dedicată.
   Rulați verificarea și schimbarea de mai jos. Dacă găsiți conexiuni,
   închideți-le controlat și reluați verificarea.
3. Confirmați `is_read_committed_snapshot_on = 1` pentru `FileGridMeta`,
   apoi reporniți API-ul, verificați health-ul și restabiliți traficul.
   Urmăriți erorile de ingestie, epuizarea pool-ului, așteptările între
   scriitori și creșterea spațiului de versionare în timpul încărcării.

```sql
SELECT @@SERVERNAME AS server_name, DB_NAME() AS current_database;
SELECT name, is_read_committed_snapshot_on, is_accelerated_database_recovery_on
FROM sys.databases
WHERE name IN (N'FileGridMeta', N'FileGridStorage');
```

După verificarea instanței, executați în aceeași sesiune administrativă:

```sql
IF DB_NAME() <> N'master' OR DB_ID(N'FileGridMeta') IS NULL
    THROW 50001, N'Conectați-vă la master pe instanța țintă cu FileGridMeta.', 1;

IF EXISTS (
    SELECT 1
    FROM sys.dm_exec_sessions
    WHERE database_id = DB_ID(N'FileGridMeta') AND session_id <> @@SPID
) OR EXISTS (
    SELECT 1
    FROM sys.dm_tran_locks
    WHERE resource_database_id = DB_ID(N'FileGridMeta')
      AND request_session_id <> @@SPID
)
    THROW 50002, N'FileGridMeta are încă sesiuni sau lock-uri active.', 1;

ALTER DATABASE [FileGridMeta] SET READ_COMMITTED_SNAPSHOT ON;

SELECT name, is_read_committed_snapshot_on
FROM sys.databases
WHERE name IN (N'FileGridMeta', N'FileGridStorage');
```

Pentru revenire, repetați fereastra de mentenanță și verificările, cu
`SET READ_COMMITTED_SNAPSHOT OFF` în instrucțiunea de mai sus. Verificați
`is_read_committed_snapshot_on = 0` înainte de a reporni aplicația.

Rândurile modificate generează versiuni. Cu ADR dezactivat, monitorizați
`tempdb` și spațiul rezervat pentru `FileGridMeta` în
`sys.dm_tran_version_store_space_usage`. Cu ADR activat, monitorizați
spațiul folosit de Persistent Version Store în baza de date.
[Documentație Microsoft despre versionare](https://learn.microsoft.com/en-us/sql/relational-databases/sql-server-transaction-locking-and-row-versioning-guide?view=sql-server-ver17)
