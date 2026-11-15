# Teste de încărcare k6 (F8)

Scenarii k6 pentru FileGrid PPC, după contractul `docs/api/openapi.yaml`. Rulează în Docker (`grafana/k6:1.3.0`),
fără instalare locală. Datele de test se generează în memorie; contoarele și fișierele au prefixul `k6-<RUN_ID>-`,
ca să se distingă de datele reale.

## Rulare

Stack-ul trebuie să ruleze (`scripts/start.ps1`). Scriptul citește parolele `FILEGRID_USER_*_PASSWORD` din `.env` și
le dă containerului doar prin nume de variabilă (nu apar în comenzi, loguri sau fișiere).

```powershell
.\tests\load\run.ps1 smoke
.\tests\load\run.ps1 security
.\tests\load\run.ps1 meter-readings -Short
.\tests\load\run.ps1 mixed -Short
.\tests\load\run.ps1 peak -Short
.\tests\load\run.ps1 storm -Short
.\tests\load\run.ps1 big-upload        # o singura data, la cerere: creeaza un fisier de 1,1 GB pe FTP
```

```bash
tests/load/run.sh smoke
tests/load/run.sh peak --short
```

- Dintr-un terminal PowerShell nou, dacă politica de execuție blochează scriptul:
  `powershell -ExecutionPolicy Bypass -File tests\load\run.ps1 smoke`.
- Cu `-Dashboard` / `--dashboard` pornește dashboard-ul web al k6, live pe `http://localhost:5665` cât rulează
  testul (rata de cereri, latențe, erori; tab-ul Timings are graficele pe operație). La final se salvează raportul
  HTML în `results/<scenariu>-<moment>-dashboard.html`. Pentru demo, rulează varianta lungă (de exemplu
  `.\tests\load\run.ps1 mixed -Dashboard`) ca să ai timp să urmărești graficele.
- Fără `-Short` / `--short` rulează varianta lungă (1–2 minute de palier).
- Argumentele de după `--` ajung la k6 (de exemplu `-- --vus 5`).
- Variabile opționale: `METER_RATE` (loturi/s în `meter-readings`), `MIXED_RATE` (multiplicator în `mixed`),
  `PEAK_RATE` (VU-uri în furtuna din `peak`), `STORM_FACTOR` (multiplicatorul avariilor în `storm`, implicit 10),
  `BIG_SIZE_MB` (mărimea upload-ului mare, implicit 1100), `DURATION`, `DUP_PCT`, `RUN_ID`, `K6_DEBUG_ERRORS=1` (afișează
  fiecare răspuns neașteptat).
- Implicit k6 rulează în rețeaua `filegrid_storage-net`, unde există și nginx, și api: utilizatorii trec prin nginx
  (`https://nginx/api/v1`, TLS self-signed, `insecureSkipTLSVerify`). La `peak`, citirile merg direct la
  `http://api:8080/api/v1`, ca `meter-simulator`. Cu `-Localhost` / `--localhost`, totul trece prin
  `https://localhost` (portul 443 publicat).

## Scenarii

| Script | Ce face | Praguri (`thresholds`) |
|---|---|---|
| `smoke.js` | Login pentru toate cele 5 roluri; teren: avarie + video în bucăți (cu bucată retrimisă idempotent), căutare, download, `Range` → 206, verify, link semnat; racordari: document criptat, `sha256Plain`, `Range` ignorat, verify `deep`; contor: lot 201, același lot → 200 `duplicate`, cheie greșită → 400; dispecer: căutare, download, verify, `/stats/*`; admin: `/admin/health`. | toate verificările trec, 0 răspunsuri neașteptate |
| `security.js` | Matricea 4.4 (C10): fiecare rol încearcă operații nepermise → 403 `FORBIDDEN`; fără token / token invalid / parolă greșită / link semnat modificat → 401; ADMIN are acces (control pozitiv). | toate verificările trec, minim 20 de 403 |
| `meter-readings.js` | Contor (METER_SERVICE): loturi de 48–287 citiri ca simulatorul, câte un contor per VU, timp sintetic; 10% duplicate intenționate (aceeași `Idempotency-Key` → 200). | p95 < 500 ms, p99 < 1500 ms, erori < 1% |
| `mixed.js` | Încărcare mixtă: avarii (`POST /files`), video chunked (`/uploads/*`), documente criptate (upload + download + verify `deep`), căutare/download/Range/link semnat și `/stats/*` pentru dispecer, citiri în fundal. | p95 pe operație (căutare < 800 ms, upload < 1500 ms, chunk/complete < 3000 ms...), erori < 1%, 0 nepotriviri de hash |
| `storm.js` | Planul echipei: faza `normal` (toate categoriile la rată normală), apoi faza `storm`, cu de 10 ori mai multe avarii (`POST /files` OUTAGE_RECORD, 2/s → 20/s), în timp ce căutarea, documentele, citirile și video-ul continuă la aceeași rată. `X-Scenario` = `normal`, apoi `storm`. | celelalte categorii își păstrează p95 (căutare < 800 ms, documente < 1500 ms, citiri < 500 ms, chunk < 3000 ms) și < 1% erori; avarii < 5% erori, 429 numărate separat |
| `big-upload.js` | Upload de 1,1 GB prin `/uploads/*` (69 de bucăți de 16 MiB). Conținutul se generează bucată cu bucată, iar SHA-256 se calculează incremental (k6 nu ține fișierul întreg în memorie); la final, hash local = hash server, `/verify` și un `Range` pe ultimii 1024 de octeți. Rulează-l la cerere: contractul nu are `DELETE`, deci fișierul rămâne pe FTP (id-ul apare în log, linia `fisier <id>`). | toate verificările trec, p95 bucată < 15 s |
| `peak.js` | Izolare pe categorie: sondele (căutare dispecer, avarii teren) rulează întâi singure (baseline), apoi în paralel cu o furtună de citiri (implicit 150 VU, peste limita de 50 a bulkhead-ului; la 429 respectă `Retry-After`). | p95 căutare < 800 ms și p95 avarii < 1500 ms în ambele faze; sonde: < 1% erori neașteptate și 0 răspunsuri 429; citiri în furtună: p95 < 3000 ms și < 5% erori neașteptate |

`429 CATEGORY_SATURATED` (bulkhead C9) nu e eroare: se numără separat în `filegrid_bulkhead_429`. Răspunsurile
așteptate (de exemplu 429 la citiri, 403 în `security`) nu intră în `http_req_failed`. k6 acceptă 200 și 503
pentru `/stats/peak-vs-normal` și `/stats/storage-advisor`. Comparația răspunde 200 când există date suficiente
pentru fiecare categorie în ambele scenarii (`normal` și `storm` implicit, în ultimele 24 h). Cu date insuficiente
răspunde 503 `REQUEST_METRICS_UNAVAILABLE`. Advisorul poate răspunde 503 dacă măsurătorile sunt incomplete.
La 200 se verifică `interpretation`, iar pentru `/stats/requirements` se verifică cele 4 cerințe R1–R4 cu `status`.

## Rulare pe 3 replici (C14) și ce se vede în Grafana

```powershell
docker compose up -d --scale api=3
.\tests\load\run.ps1 storm -Short
```

nginx împarte cererile pe replici, iar Prometheus colectează fiecare replică separat (eticheta `instance`). În
Grafana (`https://localhost/grafana`), pe intervalul „Last 15 minutes”, sau direct în Prometheus:

```promql
sum by (category) (rate(filegrid_operation_seconds_count[1m]))
```

- `storm`: în faza a doua crește doar OUTAGE_RECORD (de la ~2/s la ~17–20/s); CONNECTION_DOC, METER_READING și
  INSPECTION_VIDEO rămân la aceeași rată, iar p95-ul lor nu se schimbă. Asta e „gata” din planul echipei.
- `sum by (instance) (rate(filegrid_operation_seconds_count[1m]))` arată că încărcarea se împarte pe cele 3 replici.
- `big-upload`: în `docker stats` memoria replicilor rămâne constantă în timpul upload-ului (streaming, fără
  buffer de mărimea fișierului).
- `peak`: `sum by (category) (filegrid_bulkhead_rejected_total)` crește doar pentru METER_READING.

Ultimul `smoke` pe stack-ul lui Ovidiu (15.11.2026) a trecut 72/72 verificări, cu 0 erori neașteptate.
La validarea finală F8, `peak --short` a trecut pragurile absolute după activarea RCSI pe `FileGridMeta`.
Verdictul relativ față de baseline poate rămâne `DEGRADAT`. Verifică starea RCSI pe bazele existente.
La concurență extremă pot apărea în continuare deadlock-uri SQL Server. Vezi [limitările cunoscute](../../docs/LIMITARI-CUNOSCUTE.md).

În `mixed`, pragul agregat pentru `/stats/*` rămâne p95 < 2 s. Latența la volume mari rămâne de verificat
pe stack-ul țintă după optimizarea `/stats/anomalies`. Pragurile nu se relaxează ca testele să treacă.

## Rezultate

Fiecare rulare scrie în `tests/load/results/` (ignorat de git) `<scenariu>-<moment>.txt` (rezumatul afișat și în
consolă) și `.json` (toate metricile k6). Rezumatul are:

- `Rezultat: TRECUT / ESUAT` după praguri; codul de ieșire k6 e 99 când un prag e depășit;
- latența totală și p95 pe operație (`http_req_duration{op:...}`);
- `filegrid_unexpected`: rata răspunsurilor cu cod neașteptat (erorile reale);
- numărul de 429 de la bulkhead, de 403 așteptate, de loturi duplicate și de nepotriviri de hash;
- la `peak`: p95 baseline → furtună pentru căutare și avarii, cu verdict `IZOLAT` dacă p95 în furtună este
  cel mult maximul dintre 3× baseline și baseline + 200 ms. Altfel apare `DEGRADAT`. Verdictul relativ apare
  doar în rezumat. Rezultatul k6 este dat de pragurile din `thresholds`.
