# FileGrid PPC

Sistem de gestiune a fișierelor pentru un distribuitor de energie: citiri de contor, înregistrări de avarie, documente
de racordare (criptate) și video-uri de inspecție, fiecare categorie pe storage-ul potrivit (SQL Server, S3/MinIO,
FTP), cu fallback, observabilitate și statistici. Backend Spring Boot 3.5, interfață Angular 21 servită de nginx,
totul în Docker Compose.

## Cuprins

1. [Cerințe](#1-cerințe)
2. [Pornire cu o singură comandă](#2-pornire-cu-o-singură-comandă)
3. [Adrese și porturi](#3-adrese-și-porturi)
4. [Utilizatori de test și roluri](#4-utilizatori-de-test-și-roluri)
5. [Operare: oprire, resetare, scalare, dezvoltare](#5-operare)
6. [Politica per categorie (fără cod)](#6-politica-per-categorie-fără-cod)
7. [Simulatorul de contoare](#7-simulatorul-de-contoare)
8. [Teste](#8-teste)
9. [Cerință PPC → unde se demonstrează](#9-cerință-ppc--unde-se-demonstrează)
10. [Depanare](#10-depanare)
11. [Documentație](#11-documentație)

## 1. Cerințe

| Ce | Detalii |
|---|---|
| Docker | Docker Desktop pe Windows/macOS (pe Windows: containere Linux, motor WSL 2 sau Hyper-V) sau Docker Engine pe Linux |
| Docker Compose | **minim 2.20** (stack-ul folosește `include`); scriptul de pornire verifică versiunea |
| Memorie | **~8 GB RAM alocați lui Docker** (bugetul din `infra/docker/docker-compose.yml`: ~7,1 GB cu 3 replici api) |
| Disc | minim ~10 GB liberi: imaginile au ~6 GB (SQL Server 2022 singur ~2,2 GB), plus cache-ul de build și volumele; plus ~1,7 GB dacă generezi video-urile de test |
| Git | pentru clonare |
| Rețea | acces la internet la prima pornire (descărcarea imaginilor și build-ul) |

Nu e nevoie de Java, Maven sau Node.js local: build-ul se face în imaginile Docker. Ghid detaliat pentru Windows 11
(WSL, Hyper-V, SSMS): [docs/setup/Windows-Local-Setup.md](docs/setup/Windows-Local-Setup.md).

## 2. Pornire cu o singură comandă

Din rădăcina repo-ului, cu Docker pornit:

```powershell
.\scripts\start.ps1
```

Dacă politica de execuție PowerShell blochează scriptul:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start.ps1
```

Linux, macOS, WSL sau Git Bash:

```bash
./scripts/start.sh
```

Ce face scriptul ([ADR-002](docs/decisions/ADR-002-pornire-o-singura-comanda.md)):

1. verifică `docker`, că Docker răspunde și că Docker Compose e cel puțin 2.20;
2. creează `.env` (ignorat de git) cu **secrete aleatorii**: parolele SQL Server (`sa`, `filegrid_meta_app`,
   `filegrid_storage_app`), MinIO, FTP, Grafana, cheia JWT, cheia AES pentru documente, cheia HMAC pentru link-urile
   semnate și parolele celor 5 utilizatori de test. Dacă `.env` există, adaugă doar variabilele lipsă și **nu schimbă
   niciodată** valorile existente (parolele SQL sunt deja în volumul `mssql`);
3. rulează `docker compose up -d --build` — **prima construire durează câteva minute** (imagini + build Maven și
   Angular); următoarele porniri sunt rapide;
4. așteaptă ca toate instanțele `api` să fie `healthy` (implicit maxim 600 s; `-HealthTimeoutSeconds` în PowerShell,
   `FILEGRID_START_TIMEOUT` în bash), apoi verifică `GET https://localhost/api/v1/health` prin nginx;
5. afișează adresa, Grafana și utilizatorii de test **cu parolele lor** — nu face capturi de ecran / nu partaja
   ecranul în acel moment.

La eșec, scriptul afișează ultimele linii din logurile relevante (`mssql-init`, `minio-init`, `api` sau `nginx`).

Verificare rapidă:

```bash
docker compose ps
```

## 3. Adrese și porturi

| Adresă | Ce este |
|---|---|
| https://localhost | interfața FileGrid (login, încărcare, căutare/descărcare, statistici). Certificat **self-signed**, generat la prima pornire: acceptă-l în browser |
| http://localhost | redirecționare 301 la HTTPS |
| https://localhost/api/v1/ | API-ul REST (contract: [docs/api/openapi.yaml](docs/api/openapi.yaml)); `GET /api/v1/health` fără autentificare |
| https://localhost/grafana/ | Grafana: utilizator `admin`, parola `GRAFANA_ADMIN_PASSWORD` din `.env` |

**Singurul serviciu cu porturi publicate este `nginx` (80 și 443).** `api`, SQL Server, MinIO, FTP, Prometheus,
Alertmanager, Loki și Grafana stau în rețeaua internă `storage-net` (`internal: true`, fără porturi publicate, fără
ieșire spre internet). `/actuator` nu e expus de nginx.

Porturi suplimentare, doar la cerere și doar pe `127.0.0.1`:

| Când | Porturi |
|---|---|
| suprascrierea de dezvoltare `infra/docker/docker-compose.dev.yml` | `1433` SQL Server, `9000`/`9001` MinIO (S3 / consolă), `21` + `21000-21049` FTP |
| profilul `chaos` (toxiproxy) | `8474` API-ul toxiproxy, `8443` proxy client → nginx |
| testul C14 (proiect izolat `filegrid-c14`) | `18443` (HTTPS, configurabil cu `C14_HTTPS_PORT`) |
| k6 cu `-Dashboard` | `5665` (dashboard-ul web k6, cât rulează testul) |

## 4. Utilizatori de test și roluri

Câte un utilizator per rol. **Parolele sunt în `.env`** (`FILEGRID_USER_<NUME>_PASSWORD`, de exemplu
`FILEGRID_USER_DISPECER_PASSWORD`) și sunt afișate la finalul scriptului de pornire.

| Utilizator | Rol | Poate încărca | Poate căuta și descărca | Altele |
|---|---|---|---|---|
| `contor` | `METER_SERVICE` | `METER_READING` (`POST /meter-readings`) | – | folosit de simulator |
| `teren` | `FIELD` | `OUTAGE_RECORD`, `INSPECTION_VIDEO` | `OUTAGE_RECORD`, `INSPECTION_VIDEO` | – |
| `dispecer` | `DISPATCH` | – | `OUTAGE_RECORD`, `METER_READING` | `/stats/*` |
| `racordari` | `CONNECTIONS` | `CONNECTION_DOC` | `CONNECTION_DOC` | acces auditat |
| `admin` | `ADMIN` | toate | toate | `/stats/*`, `/admin/*` (benchmark, health) |

Matricea 4.4 din [docs/api/openapi.yaml](docs/api/openapi.yaml) (`components.x-role-matrix`). Orice acces în afara ei
primește `403 FORBIDDEN`; la documentele de racordare se incrementează `filegrid_doc_access_total{result="denied"}`.
Autentificarea: `POST /api/v1/auth/login` → JWT de 15 minute + refresh de 8 ore.

## 5. Operare

```bash
docker compose down        # oprește stack-ul; datele (volumele) rămân
docker compose down -v     # resetare completă: ȘTERGE toate datele (baze, MinIO, FTP, Grafana, Prometheus, Loki)
```

`.env` rămâne după `down -v`; la următoarea pornire bazele se recreează cu aceleași parole. Scriptul de pornire se
poate rula oricând din nou (e idempotent).

**Scalare (C14)** — `api` nu are `container_name` și nici porturi publicate, deci se poate replica; nginx re-rezolvă
`api` la 5 s și împarte cererile între replici, iar Prometheus colectează fiecare replică separat:

```bash
docker compose up -d --scale api=3
```

Upload-ul în bucăți funcționează cu bucăți trimise la replici diferite (starea e în FileGridMeta, lease per sesiune —
[ADR-003](docs/decisions/ADR-003-upload-in-bucati.md)).

**Dezvoltare** (SSMS / Azure Data Studio pe `127.0.0.1:1433`, login `sa` sau `filegrid_*_app`, parolele din `.env`;
consola MinIO pe `127.0.0.1:9001`; FTP pe `127.0.0.1:21`):

```bash
docker compose -f docker-compose.yml -f infra/docker/docker-compose.dev.yml up -d
```

**Profilul `split`** (al doilea SQL Server doar pentru FileGridStorage, răspunsul la punctul unic de cădere din
[ADR-001](docs/decisions/ADR-001-sql-server-punct-unic-de-cadere.md)): `STORAGE_DB_HOST=mssql-storage` în `.env`, apoi
`docker compose --profile split up -d`.

## 6. Politica per categorie (fără cod)

Politica 4.2 stă în câte un fișier YAML per categorie, în
[backend/filegrid-api/src/main/resources/policies/](backend/filegrid-api/src/main/resources/policies/):

| Fișier | Categorie | Principal | Rezervă | Mărime maximă | Criptare | Bulkhead |
|---|---|---|---|---|---|---|
| `meter-reading.yml` | `METER_READING` | DB | S3 | 256 KB | nu | 50 |
| `outage-record.yml` | `OUTAGE_RECORD` | S3 | DB | 20 MB | nu | 20 |
| `connection-doc.yml` | `CONNECTION_DOC` | DB | S3 | 20 MB | **AES-GCM** | 10 |
| `inspection-video.yml` | `INSPECTION_VIDEO` | FTP | S3 | 5 GB (bucăți de 16 MiB) | nu | 4 |

Directorul e montat read-only în fiecare container `api` (`/app/policies`, `FILEGRID_POLICY_DIR`). Editezi fișierul pe
disc și salvezi: fiecare instanță îl **recitește fără restart**, la cel mult 2 s
(`filegrid.policies.reload-interval` în `application.yml`). Câmpurile sunt descrise în comentariul din capul fiecărui
fișier. Un YAML invalid la recitire se loghează (ERROR) și se păstrează politica anterioară; la pornire, un YAML invalid
oprește aplicația.

## 7. Simulatorul de contoare

Simulatorul (profilul `sim`) trimite loturi de citiri ca utilizatorul `contor`, cu `Idempotency-Key`, retry cu
backoff și duplicate intenționate; metricile lui ajung în Prometheus.

```powershell
$env:SIM_PROFILE = 'peak'   # sau 'normal' (implicit)
docker compose --profile sim up --build meter-simulator
```

```bash
SIM_PROFILE=peak docker compose --profile sim up --build meter-simulator
```

| Profil | Contoare | Interval | Durată | Anormale | Tăcute | Duplicate |
|---|---:|---:|---:|---:|---:|---:|
| `normal` | 12 | 5 s | 5 min | 0% | 0% | 0% |
| `peak` | 120 | 1 s | 5 min | 5% | 5% | 10% |

Detalii și suprascrieri (`SIM_CONFIG`): [simulator/meter-simulator/README.md](simulator/meter-simulator/README.md).

Date de test pentru încărcări manuale (citiri, avarii, documente fictive, video-uri de 100 MiB–1,1 GiB) în
`sample-data/`:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\generate-sample-data.ps1 -SkipLargeVideos
```

(sau `./scripts/generate-sample-data.sh`).

## 8. Teste

| Test | Cum se rulează | Detalii |
|---|---|---|
| Unit backend | `cd backend/filegrid-api` apoi `./mvnw test` (Windows: `.\mvnw.cmd test`); necesită JDK 21 | JUnit, inclusiv politica, adaptoarele de storage și securitatea. Pentru testele de integrare SQL și pe stack, vezi [limitările și condițiile de rulare](docs/LIMITARI-CUNOSCUTE.md). |
| Unit frontend | `cd frontend/filegrid-ui`, `npm ci`, `npm test`; necesită Node.js | `ng test` |
| Încărcare (k6) | `.\tests\load\run.ps1 smoke` / `tests/load/run.sh smoke`; scenarii `smoke`, `security`, `meter-readings`, `mixed`, `peak` | [tests/load/README.md](tests/load/README.md) — k6 rulează în Docker |
| Haos | `.\tests\chaos\run-all.ps1` (sau un scenariu, ex. `.\tests\chaos\a-ftp-down.ps1`); profilul `chaos` pentru toxiproxy | [tests/chaos/README.md](tests/chaos/README.md) — FTP/MinIO căzute, FileGridStorage offline, rețea degradată, corupere, bulkhead, reluarea upload-ului |
| C14 (3 replici + 1,1 GiB) | `tests/integration/c14/run.sh all` (bash) | proiect Docker izolat `filegrid-c14`, nu atinge stack-ul principal; comentariul din capul scriptului |
| Benchmark | `POST /api/v1/admin/benchmark/run` ca `admin` (sincron, poate dura minute) | cele 12 combinații categorie × storage; alimentează `/stats/storage-advisor` |
| Alerte | `promtool test rules` | [observability/alertmanager/README.md](observability/alertmanager/README.md) |

Testele de haos și de încărcare lucrează pe stack-ul pornit și creează date cu prefixe (`chaos-…`, `k6-…`).

## 9. Cerință PPC → unde se demonstrează

### Cerințe funcționale

| Cerință | Unde se demonstrează |
|---|---|
| Pornire pe o mașină curată, o singură comandă | `scripts/start.ps1` / `scripts/start.sh` ([ADR-002](docs/decisions/ADR-002-pornire-o-singura-comanda.md)) |
| Încărcare pe 4 categorii, storage ales din politică (4.2) | UI **Încărcare**; `POST /files` (avarii, documente), `POST /meter-readings` (citiri), `POST /uploads` (video); câmpul `storage` din metadate |
| Upload în bucăți, reluabil, pentru fișiere mari | UI **Încărcare** (video); `/uploads/{id}/chunks/{n}`, `GET /uploads/{id}` → `nextChunk`; haos `g-chunked-resume.ps1`; C14 `run.sh big` (1,1 GiB în streaming) |
| Citiri fără pierderi și fără dubluri | `Idempotency-Key` pe `POST /meter-readings` (retrimiterea → `200 duplicate`); simulatorul; k6 `meter-readings`; haos `c-storage-db-offline.ps1` |
| Căutare și descărcare | UI **Căutare**; `GET /files` (filtre, paginare), `GET /files/{id}/download` (cu `Range`), link semnat `GET /downloads/{token}` ([ADR-004](docs/decisions/ADR-004-descarcare-prin-link-semnat.md)) |
| Integritate (SHA-256) | `X-Content-SHA256` la descărcare; `GET /files/{id}/verify` (și `deep`) → `409 FILE_CORRUPT`; haos `e-corruption.ps1` |
| Politica per categorie configurabilă fără cod | `policies/*.yml`, recitite la 2 s ([secțiunea 6](#6-politica-per-categorie-fără-cod)) |
| Roluri | matricea 4.4; k6 `security` (≥ 20 de `403`) |

### Statistici

| Statistică | Endpoint (`DISPATCH`/`ADMIN`) | UI / Grafana | Stare |
|---|---|---|---|
| Starea celor 4 cerințe de business | `GET /stats/requirements` | UI **Statistici**; Grafana *FileGrid – Cerințe de business și contoare* | funcțional |
| Anomalii de consum, contoare tăcute | `GET /stats/anomalies` | UI **Statistici** | funcțional |
| Zone cu cele mai multe avarii | `GET /stats/outage-zones` | UI **Statistici** | funcțional |
| Recomandare de storage | `GET /stats/storage-advisor` | UI **Statistici** | răspunde `503` (`reason: STORAGE_MEASUREMENTS_INCOMPLETE`) până rulează benchmark-ul (`POST /admin/benchmark/run`) |
| Zi normală vs. zi de vârf | `GET /stats/peak-vs-normal` | UI **Statistici**, Grafana *FileGrid – Operații*, rândul „Zi normală vs. vârf” | `200` cu metrici de operații și respingeri bulkhead pe categorie când datele pentru `normal` și `storm` sunt suficiente în ultimele 24 h. `tests/load/run.sh storm` generează date pentru ambele faze. Cu date insuficiente, `503` cu `details.reason: REQUEST_METRICS_UNAVAILABLE` și `details.missing`. |

### Cerințe nefuncționale

| Cerință | Unde se demonstrează |
|---|---|
| **Scalabilitate** — mai multe instanțe api | `docker compose up -d --scale api=3`; Grafana *Operații* → „Instanțe api colectate”, heap per instanță; `tests/integration/c14/run.sh` |
| Scalabilitate — izolare per categorie (bulkhead) | `maxConcurrentRequests` din politică → `429 CATEGORY_SATURATED` + `Retry-After` doar pe categoria saturată; k6 `peak`; haos `f-bulkhead.ps1`; Grafana *Storage și reziliență* → „Cereri respinse de bulkhead” |
| **Observabilitate** — metrici | Prometheus (metricile 4.3: `filegrid_operation_seconds`, `filegrid_transfers_failed_total`, `filegrid_doc_access_total`, `filegrid_meter_unconfirmed` …), 3 dashboard-uri Grafana provizionate: *Operații*, *Storage și reziliență*, *Cerințe de business și contoare* |
| Observabilitate — loguri | Loki + Promtail (loguri JSON din api, `job="filegrid-api"`); Grafana → Explore → Loki; fiecare răspuns are `X-Trace-Id`, același id în loguri |
| Observabilitate — alerte | 4 reguli în `observability/prometheus/rules/business.yml` (citiri neconfirmate, p95 avarii > 2 s, > 5% video eșuate, acces neașteptat la documente) → Alertmanager → `alert-webhook` (`docker compose logs alert-webhook`) |
| Observabilitate — sănătate | `GET /api/v1/health`; `GET /api/v1/admin/health` (storage-uri UP/DOWN, starea circuitelor); healthcheck pe fiecare container |
| **Securitate** — transport și expunere | doar nginx publicat (TLS, 80 → 443); storage-urile în rețea internă fără porturi; secrete aleatorii în `.env`, niciuna în repo |
| Securitate — autentificare și autorizare | JWT (15 min) + refresh; matricea 4.4; k6 `security` |
| Securitate — date personale | `CONNECTION_DOC` criptat AES-GCM înainte de storage; jurnal de audit cu lanț de hash-uri (`dbo.audit_log` în FileGridMeta); alerta 4; `/stats/requirements` R3 |
| Securitate — link-uri de descărcare | token HMAC cu expirare (`GET /downloads/{token}`, `401` dacă e modificat/expirat) |
| **Reziliență** — storage căzut | fallback pe storage-ul de rezervă + circuit breaker; haos `a-ftp-down`, `b-minio-down`, `c-storage-db-offline`; Grafana *Storage și reziliență* |
| Reziliență — rețea instabilă | retry cu backoff (C11); toxiproxy, haos `d-toxiproxy.ps1` |
| Reziliență — client întrerupt | reluarea upload-ului din `nextChunk`; haos `g-chunked-resume.ps1` |
| Reziliență — punct unic de cădere | un singur SQL Server pentru metadate și storage DB: decizie asumată în [ADR-001](docs/decisions/ADR-001-sql-server-punct-unic-de-cadere.md), profilul `split` ca atenuare |

## 10. Depanare

| Problemă | Soluție |
|---|---|
| Browserul avertizează la certificat | Normal: certificat self-signed. „Advanced” → „Proceed to localhost”. Pentru `curl` folosește `-k` |
| `start.ps1 cannot be loaded … running scripts is disabled` | `powershell -ExecutionPolicy Bypass -File .\scripts\start.ps1` |
| `Docker nu răspunde` | Pornește Docker Desktop și așteaptă „Engine running”; `docker info` trebuie să meargă |
| `Docker Compose … este prea vechi` | Actualizează Docker Desktop (Compose ≥ 2.20) |
| Porturile 80/443 ocupate (`bind: address already in use`) | Oprește serviciul care le ține (IIS, Skype, alt nginx/Apache, alt container). Windows: `netstat -ano \| findstr ":443"` apoi identifică PID-ul în Task Manager |
| Containere oprite cu `OOMKilled` sau `mssql` care repornește | Mărește memoria Docker la ~8 GB (Docker Desktop → Settings → Resources, sau `.wslconfig` cu motorul WSL 2); verifică cu `docker stats` |
| Prima pornire durează mult / pare blocată | Normal la primul build (descărcări + Maven + Angular). Urmărește cu `docker compose logs -f api`. Pe mașini lente: `.\scripts\start.ps1 -HealthTimeoutSeconds 1200` |
| `502`/`503` imediat după pornire | nginx re-rezolvă `api` la 5 s; reîncearcă după câteva secunde |
| Ai schimbat manual parolele SQL în `.env` și `mssql-init` eșuează | Parolele sunt deja în volum: pune-le înapoi sau resetează cu `docker compose down -v` (șterge datele) |

## 11. Documentație

- Arhitectură (servicii, rețele, porturi, limite): [docs/architecture/arhitectura.md](docs/architecture/arhitectura.md); fluxuri: [docs/architecture/fluxuri.md](docs/architecture/fluxuri.md)
- Decizii (ADR): [docs/decisions/](docs/decisions/) — [ADR-001](docs/decisions/ADR-001-sql-server-punct-unic-de-cadere.md) SQL Server punct unic de cădere, [ADR-002](docs/decisions/ADR-002-pornire-o-singura-comanda.md) pornire cu o singură comandă, [ADR-003](docs/decisions/ADR-003-upload-in-bucati.md) upload în bucăți, [ADR-004](docs/decisions/ADR-004-descarcare-prin-link-semnat.md) link semnat
- API: [docs/api/openapi.yaml](docs/api/openapi.yaml) (contractul complet, roluri, coduri de eroare, metrici 4.3); statistici: [docs/api/o7-analytics.md](docs/api/o7-analytics.md)
- Storage și concurență în baza de date: [docs/storage/o3-db-concurrency.md](docs/storage/o3-db-concurrency.md). RCSI se activează la crearea `FileGridMeta`. Pentru o bază existentă, verifică starea și urmează, dacă e nevoie, [procedura de mentenanță](docs/storage/filegridmeta-rcsi.md).
- Setup Windows: [docs/setup/Windows-Local-Setup.md](docs/setup/Windows-Local-Setup.md)
- Componente: [tests/load](tests/load/README.md), [tests/chaos](tests/chaos/README.md), [simulator](simulator/meter-simulator/README.md), [alerte](observability/alertmanager/README.md), [promtail](observability/promtail/README.md)
