# Teste de haos (F9)

Scenariile strică pe rând câte o componentă a stack-ului real și verifică, **prin API**, că FileGrid se comportă ca în
contract (`docs/api/openapi.yaml`) și în politica 4.2: unde ajunge fișierul (`storage` din metadate), ce coduri HTTP
primește utilizatorul și dacă hash-urile se potrivesc. Fiecare script are aceeași structură:
**pregătește → strică → verifică → repară → verifică revenirea**. Repararea rulează mereu, și la eșec (`try/finally`).

| Script | Ce strică | Ce demonstrează |
|---|---|---|
| `a-ftp-down.ps1` | `docker compose stop ftp` | `INSPECTION_VIDEO` merge pe **S3** (rezerva), circuitul FTP se deschide (`/admin/health`: `DOWN`, `OPEN`), video-ul de pe FTP primește `503` + `Retry-After`; după `start ftp`, upload-urile noi revin pe **FTP**, toate fișierele se descarcă cu hash corect. |
| `b-minio-down.ps1` | `docker compose stop minio` | `OUTAGE_RECORD` merge pe **DB**; după pornire revine pe **S3**; fișierele de dinainte, din timpul și de după cădere au hash corect. |
| `c-storage-db-offline.ps1` | `ALTER DATABASE FileGridStorage SET OFFLINE WITH ROLLBACK IMMEDIATE` (FileGridMeta rămâne pornită) | Loturile `METER_READING` merg pe **S3**, sunt confirmate (`201`), retrimiterea dă `200 duplicate:true` cu același `fileId`; după `SET ONLINE` revin pe **DB**. În FileGridMeta: 288 citiri, 288 momente distincte, 3 fișiere, 0 neconfirmate (fără duplicate). |
| `d-toxiproxy.ps1` | toxiproxy pe S3 și pe FileGridStorage: latență 400 ms, erori `reset_peer` intermitente (150 ms din 2,35 s), apoi proxy dezactivat | Cu latență totul trece pe principal. Cu erori scurte: citirile primesc `200` (retry C11), scrierile `201` (cele lovite trec singure pe rezervă). La eroare persistentă: circuit `OPEN`, scrieri pe rezervă, fișierele de pe storage-ul căzut `503` + `Retry-After`; după reparare, înapoi pe principal. |
| `e-corruption.ps1` | un octet schimbat cu `dd conv=notrunc` în `/srv/ftp/u/<uploadId>/c1` | `GET /files/{id}/verify` → `409 FILE_CORRUPT` (cu `expectedSha256` / `actualSha256`), descărcarea → `409`; după ce octetul original e pus la loc: `ok:true`, hash corect. |
| `f-bulkhead.ps1` + `bulkhead.js` | vârf de citiri de contor (k6, 120 VU × numărul de replici api, 30 s, direct la `api:8080`) | `429 CATEGORY_SATURATED` (cu `Retry-After`) **doar** pe `METER_READING`; în paralel avariile primesc toate `201`, căutările dispecerului `200`. |
| `g-chunked-resume.ps1` | upload video de 40 MiB (3 bucăți), clientul e omorât în mijlocul bucății 2 | `GET /uploads/{id}` arată `nextChunk = 2`; reluarea de la bucata 2 merge, `complete` → `201`, SHA-256 final = hash-ul local. |
| `h-client-network.ps1` | rețeaua **client → nginx** degradată prin toxiproxy (`https://localhost:8443`): latență 1000 ms ± 100 și toxicul `timeout` (3 s) pe ~20% din conexiuni | Un video de `-SizeMiB` (implicit 200 MiB, 13 bucăți) se încarcă în bucăți; clientul reîncearcă singur bucățile pierdute, ca interfața F4 (backoff 1, 2, 4… max 10 s, `Retry-After`, până la 2 minute, resincronizare din `GET /uploads/{id}`), fără intervenție manuală. `complete` → `201`, SHA-256 final = hash-ul local, `verify` → `ok:true`. |

Fișierele și contoarele create au prefixul `chaos-<MMddHHmmss>` (ex. `chaos-1115031421-avarie-1-inainte.csv`,
contorul `chaos-1115031421-MUN-0000001`), ca să se distingă de datele reale. Nu se șterg volume și nu se oprește
FileGridMeta.

## Cum se rulează

Stack-ul trebuie să fie pornit (`scripts\start.ps1`). Din rădăcina repo-ului:

```powershell
.\tests\chaos\run-all.ps1                 # toate (~6 minute), apoi verifică stack-ul: toate healthy, storage-uri UP
.\tests\chaos\run-all.ps1 -Only a,e       # doar unele
.\tests\chaos\a-ftp-down.ps1              # un singur scenariu
.\tests\chaos\d-toxiproxy.ps1 -Targets s3 # d doar pe S3 (implicit s3 și db)
.\tests\chaos\h-client-network.ps1 -SizeMiB 1130   # la demo: 1,1 GB (71 de bucăți), câteva minute
```

Scenariile merg și cu mai multe replici api (C14, `docker compose up -d --scale api=3`): starea din `/admin/health`
se așteaptă pe **fiecare** replică (câmpul `instance`; nginx le rotește, iar fiecare replică are circuitul ei),
scenariul d recreează api păstrând numărul de replici, iar f scalează vârful (50 de locuri de bulkhead pe instanță).
Fișierele temporare (inclusiv video-ul de 200 MiB / 1,1 GB din h) stau în `%TEMP%\filegrid-chaos-*` și se șterg la
final.

Din bash (Git Bash, Linux, macOS): `./tests/chaos/run-all.sh` sau `./tests/chaos/run-all.sh a-ftp-down`. Scenariile
sunt scrise o singură dată, în PowerShell (merg în Windows PowerShell 5.1 și PowerShell 7); wrapper-ul le pornește cu
`pwsh` sau `powershell.exe`. Un echivalent bash nativ ar fi cerut `jq` pentru JSON, care nu e pe mașinile echipei.

Fiecare script iese cu `0` dacă toate verificările au trecut și afișează `[OK]` / `[ESEC]` pe fiecare pas, cu codul
HTTP, `storage`-ul și începutul hash-ului. Parolele se citesc din `.env` (`FILEGRID_USER_*_PASSWORD`), nu se afișează
și nu apar în linia de comanda (login-ul trece printr-un fișier temporar șters imediat; la k6 se dă doar numele
variabilei). SQL-ul rulează ca `sa` în containerul `mssql`, cu parola din mediul containerului.

Timpii de așteptare vin din `filegrid.resilience` (`application.yml`): health la 3 s, circuit deschis după 2 verificări
eșuate, `open-wait` 5 s, deci căderea se vede în `/admin/health` în ~5–10 s, iar revenirea în ~3–7 s.

### Toxiproxy (scenariile d și h)

`toxiproxy` e în `infra/docker/docker-compose.yml` cu `profiles: ["chaos"]`: fără profil stack-ul e identic, iar api
vorbește direct cu storage-urile. Proxy-urile sunt în `toxiproxy.json`: `s3` → `minio:9000` și `storage-db` →
`mssql:1433` (doar pe `storage-net`, pentru d) și `nginx` → `nginx:443` pe portul `8443` (drumul client → nginx,
pentru h; același nume și port ca în instrucțiunile C14). Porturile publicate sunt doar pe `127.0.0.1`: `8474` (API-ul
toxiproxy) și `8443` (intrarea clientului prin proxy). În h, api nu se recreează: doar drumul client → nginx e degradat.
api trece prin proxy numai cu suprascrierea `infra/docker/docker-compose.chaos.yml` (`S3_ENDPOINT` și
`STORAGE_DB_URL` spre `toxiproxy`); FileGridMeta și FTP rămân directe (FTP pasiv ar cere câte un proxy pe fiecare port
de date). Scriptul face singur:

```powershell
docker compose --profile chaos up -d toxiproxy
docker compose -f docker-compose.yml -f infra/docker/docker-compose.chaos.yml up -d --no-deps api
# ... teste ...
docker compose up -d --no-deps api                 # înapoi la normal
docker compose --profile chaos rm -sf toxiproxy
```

Manual, cu stack-ul în modul chaos: `curl -X POST http://127.0.0.1:8474/proxies/s3/toxics -d '{"type":"latency","attributes":{"latency":800}}'`
și `curl -X POST http://127.0.0.1:8474/reset` pentru revenire.

### În browser: upload pe rețea degradată (h)

Comenzile de mai jos sunt pentru Git Bash (sau orice shell cu `curl` și ghilimele simple); în Windows PowerShell 5.1
folosește `curl.exe` și pune JSON-ul într-un fișier (`-d "@toxic.json"`), fiindcă PowerShell 5.1 strică ghilimelele.

1. Pornește toxiproxy; proxy-ul `nginx` vine din `toxiproxy.json` (dacă lipsește, îl creezi cu al doilea rând):

   ```bash
   docker compose --profile chaos up -d toxiproxy
   curl -s -X POST http://127.0.0.1:8474/proxies -d '{"name":"nginx","listen":"0.0.0.0:8443","upstream":"nginx:443","enabled":true}'
   ```

2. Deschide **https://localhost:8443** (același certificat self-signed ca la https://localhost: „Advanced” →
   „Proceed to localhost”), loghează-te ca `teren` și mergi pe pagina **Upload**. Fără toxice, merge ca de obicei.
3. Strică rețeaua: latență 1000 ms și ~20% din conexiuni care se blochează 3 s și se închid:

   ```bash
   curl -s -X POST http://127.0.0.1:8474/proxies/nginx/toxics -d '{"name":"lat","type":"latency","stream":"downstream","attributes":{"latency":1000,"jitter":100}}'
   curl -s -X POST http://127.0.0.1:8474/proxies/nginx/toxics -d '{"name":"loss","type":"timeout","stream":"upstream","toxicity":0.2,"attributes":{"timeout":3000}}'
   ```

4. Încarcă un video mare (ex. 1,1 GB): progresul se oprește din când în când, pagina arată reîncercarea bucății și
   continuă singură. După un refresh în mijloc, pagina cere re-selectarea fișierului și reia de la `nextChunk`. La
   final, „Verifică integritatea” pe fișier → OK.
5. Scoate toxicele (proxy-ul rămâne curat), apoi oprește toxiproxy:

   ```bash
   curl -s -X DELETE http://127.0.0.1:8474/proxies/nginx/toxics/lat
   curl -s -X DELETE http://127.0.0.1:8474/proxies/nginx/toxics/loss
   curl -s -X POST http://127.0.0.1:8474/reset      # sau: toate toxicele de pe toate proxy-urile
   docker compose --profile chaos rm -sf toxiproxy
   ```

## La demo

Recomandat: **a** (FTP), **e** (corupere), **f** (bulkhead) — fiecare sub un minut — și **h** (rețea degradată, cu
`-SizeMiB 1130` pentru 1,1 GB sau din browser, ca mai sus).

1. Trei ferestre: interfața (http://localhost:4201 cu `ng serve` sau https://localhost), logat ca `admin` (vede toate
   categoriile), pagina **Căutare**; un terminal cu `.\tests\chaos\watch-health.ps1` (starea `/admin/health` o dată
   pe secundă: `FTP UP/CLOSED` → `DOWN/OPEN` → `UP/CLOSED`; interfața nu are încă pagină pentru health); un terminal
   pentru scenariu.
2. Cu `$env:CHAOS_PAUSE = '1'`, scenariile a, b, c și e se opresc cu componenta stricată până apeși Enter, ca să ai
   timp să arăți efectul în UI.
3. **a)** `.\tests\chaos\a-ftp-down.ps1`: în `watch-health` FTP trece pe `DOWN/OPEN`; în Căutare (filtru `chaos-`),
   coloana storage arată `...-inspectie-1-inainte.mp4` pe **FTP** și `...-inspectie-2-in-cadere.mp4` pe **S3**. În pauză
   se poate încărca și un video din UI (ca `teren`): ajunge pe S3. După Enter, `...-inspectie-3-dupa.mp4` e din nou pe
   **FTP**, iar „Descarcă” pe primul video merge.
4. **e)** `.\tests\chaos\e-corruption.ps1`: în pauză, „Verifică integritatea” pe `...-inspectie-corupere.mp4`
   arată fișier corupt (409 `FILE_CORRUPT`); după Enter, aceeași verificare arată OK.
5. **f)** `.\tests\chaos\f-bulkhead.ps1` și, în paralel, încarcă o avarie din UI ca `teren`: trece imediat, deși
   scriptul raportează zeci de mii de `429` pe citirile de contor.
6. Când există metricile C12 și Prometheus (O8), în Grafana (https://localhost/grafana/, dashboard-ul FileGrid):
   `filegrid_storage_up` scade la 0 pe storage-ul oprit (a, b, c, d), `filegrid_bulkhead_rejected_total{category="METER_READING"}`
   crește în f, iar `filegrid_integrity_failures_total` crește în e. Până atunci, dovada e în `/admin/health` și în
   ieșirea scripturilor.

## Ce am observat (rularea din 15.11.2026)

- Toate scenariile trec pe stack-ul real (vezi rezumatul `run-all.ps1`).
- **d)** Retry-ul C11 (3 încercări, backoff 100 ms → 200 ms) acoperă erori de până la ~0,3 s: cu ferestre de
  300 ms o descărcare din ~47 a primit `503` + `Retry-After` (conform contractului, clientul reîncearcă); cu 150 ms,
  0 erori. Scrierile (`put`) nu au retry, prin design; cele lovite de eroare trec pe storage-ul de rezervă, deci
  utilizatorul primește tot `201`.
- **d)** Latența pe FileGridStorage se amplifică mult: 400 ms pe conexiune → un lot de citiri de ~3,8 s (față de
  ~0,1 s), o descărcare ~5,6 s, fiindcă scrierea în `blob_chunks` face multe drumuri dus-întors.
- **h)** Cu latență 1000 ms și ~20% conexiuni pierdute, 200 MiB (13 bucăți) se încarcă în ~1 minut, cu 5–7
  reîncercări automate (4 bucăți reluate) și SHA-256 final corect; cu 3 replici api la fel.
- **f)** Izolarea pe categorie funcționează (0 × `429` pe avarii și căutări), dar căutarea dispecerului are p95 de
  17–27 s în timpul vârfului (cu 3 replici și 360 VU: 27 s; avariile p95 1,7 s) (aceeași cauză ca la F8: blocări pe `dbo.files`, RCSI oprit pe FileGridMeta).
