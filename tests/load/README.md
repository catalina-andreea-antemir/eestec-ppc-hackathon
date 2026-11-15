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
  `PEAK_RATE` (VU-uri în furtuna din `peak`), `DURATION`, `DUP_PCT`, `RUN_ID`, `K6_DEBUG_ERRORS=1` (afișează
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
| `peak.js` | Izolare pe categorie: sondele (căutare dispecer, avarii teren) rulează întâi singure (baseline), apoi în paralel cu o furtună de citiri (implicit 150 VU, peste limita de 50 a bulkhead-ului; la 429 respectă `Retry-After`). | p95 căutare < 800 ms și p95 avarii < 1500 ms în furtună; sondele fără erori și fără 429 |

`429 CATEGORY_SATURATED` (bulkhead C9) nu e eroare: se numără separat în `filegrid_bulkhead_429`. Răspunsurile
așteptate (de exemplu 429 la citiri, 403 în `security`) nu intră în `http_req_failed`. `/stats/peak-vs-normal` și
`/stats/storage-advisor` pot răspunde 503 cât lipsesc C12/O8, iar `/stats/requirements` 404 cât nu e implementat;
toate trei sunt acceptate.

## Rezultate

Fiecare rulare scrie în `tests/load/results/` (ignorat de git) `<scenariu>-<moment>.txt` (rezumatul afișat și în
consolă) și `.json` (toate metricile k6). Rezumatul are:

- `Rezultat: TRECUT / ESUAT` după praguri; codul de ieșire k6 e 99 când un prag e depășit;
- latența totală și p95 pe operație (`http_req_duration{op:...}`);
- `filegrid_unexpected`: rata răspunsurilor cu cod neașteptat (erorile reale);
- numărul de 429 de la bulkhead, de 403 așteptate, de loturi duplicate și de nepotriviri de hash;
- la `peak`: p95 baseline → furtună pentru căutare și avarii, cu verdict `IZOLAT` (cel mult 3× sau +200 ms) sau
  `DEGRADAT`.
