# FileGrid PPC — comenzi rapide pentru demo

Rulează din rădăcina repo-ului. **PS** = Windows PowerShell 5.1/7, inclusiv pentru demo și haos. **Bash** pentru pornire pe Linux, WSL și macOS Intel. În WSL activează integrarea Docker Desktop. Comenzile Compose sunt comune.

**⚠ Temporar** — schimbă starea, declanșează alerte sau generează sarcină. Rulează haosul pe rând. Datele de test pot rămâne în stack. **Autentifică-te în afara proiecției.** Rezumatul conturilor afișează implicit utilizatorii și rolurile. Pentru parolele de test, folosește în privat `.\scripts\start.ps1 -ShowPasswords` sau `bash scripts/start.sh --show-passwords`.

## Pornire, roluri și date

**Apple Silicon** — SQL Server 2022 cere `linux/amd64`. Rulează stack-ul pe o mașină x86-64 validată și folosește MacBook-ul pentru vizualizare. Pe Mac Intel, verifică stack-ul înainte de demo. [Compatibilitate](../../README.md#compatibilitate)

| Demonstrație | Comandă / acțiune | Verifică | Pentru juriu |
|---|---|---|---|
| Pornire și health | **PS** `.\scripts\start.ps1` · **Bash** `bash scripts/start.sh`. Pe ecran: `docker compose ps` și, în PS, `curl.exe -ks -o NUL -w '%{http_code}' https://localhost/api/v1/health` sau, în Bash, `curl -ks -o /dev/null -w '%{http_code}' https://localhost/api/v1/health`. | API `healthy` și HTTP `200` prin nginx. | Scriptul construiește, pornește și verifică stack-ul. |
| Fișiere de demo | Dacă lipsesc, **PS** `.\scripts\generate-sample-data.ps1` · **WSL** `bash scripts/generate-sample-data.sh`. | Confirmă că PDF-ul, CSV-ul și video-ul din rândul **Upload DB / S3 / FTP** există în `sample-data/` înainte de demo. | Folosim date fictive, inclusiv fișiere mari pentru reluarea upload-ului. |
| Autentificare și roluri | Deschide `https://localhost/login` în taburi separate ca `teren`, `racordari` și `dispecer`. Pentru refuz, folosește comanda de la **Alerte**. | `teren` poate încărca avarii și video, `racordari` documente, `dispecer` vede Statistici și primește `403 FORBIDDEN` la accesul demonstrat. | Drepturile sunt verificate pe server pentru fiecare rol și categorie. |
| Upload DB / S3 / FTP | În `https://localhost/upload`, ca `racordari`, încarcă `sample-data/connection-docs/contract-racordare-fictiv-003.pdf`. Ca `teren`, încarcă `sample-data/outage-records/avarie-01-ST-BRASOV-114.csv` la **Avarie**, zona **Muntenia**, apoi `sample-data/inspection-videos/drona-inspectie-100MB.mp4` la **Video dronă**. | Storage afișat **DB** la document, **S3** la avarie, **FTP** la video, cu storage-urile sănătoase și politica implicită. | Politica categoriei alege destinația. Videoclipurile se trimit în bucăți. |
| Criptare | După uploadul PDF-ului, **PS** `.\docs\demo\demo.ps1 criptare -File .\sample-data\connection-docs\contract-racordare-fictiv-003.pdf`. | `storage DB`, `encrypted True`, octeții stocați diferă de antetul `%PDF`, SHA-256 al descărcării este egal cu al originalului. | Conținutul și numele sunt criptate la stocare. Utilizatorul autorizat descarcă fișierul original. |
| Deduplicare | **PS** `.\docs\demo\demo.ps1 duplicat`. | `201` → `200 duplicate:true` cu același `fileId` și `96 / 96 / 1` în SQL. | Retrimiterea cu aceeași cheie de idempotență păstrează un singur lot confirmat. |

## Scalare, bulkhead și reziliență

Pornește **PS** `.\tests\chaos\watch-health.ps1` într-un terminal separat. Verifică `meta UP` și `UP/CLOSED` pentru FTP, S3 și DB înainte și după scenarii. Ieși cu `Ctrl+C`. Pentru pauzele din scenariile FTP, MinIO, DB și corupere, setează `$env:CHAOS_PAUSE='1'` în terminalul de haos și apasă **Enter** pentru revenire. La final, `Remove-Item Env:CHAOS_PAUSE -ErrorAction SilentlyContinue`.

| Demonstrație | Comandă | Verifică | Pentru juriu / revenire |
|---|---|---|---|
| Scalare ⚠ Temporar | `docker compose up -d --no-deps --scale api=3 api`, apoi `docker compose ps api`. | 3 replici `healthy` și 3 instanțe colectate în Grafana **Operații** după următorul scrape. | nginx distribuie cererile între replici. Dacă ai pornit de la o replică, revii la ea cu `docker compose up -d --no-deps --scale api=1 api` după demo. |
| Bulkhead ⚠ Temporar | **PS** `.\tests\chaos\f-bulkhead.ps1`. Cu grafice: **PS** `.\tests\load\run.ps1 peak -Short -Dashboard` · **WSL** `bash tests/load/run.sh peak --short --dashboard` → `http://localhost:5665` cât rulează. | În f: citiri `429 CATEGORY_SATURATED` cu `Retry-After`, avarii `201` și căutări `200`, fără `429` la sonde. În peak: verifică separat pragurile k6 și verdictul relativ, care poate fi `DEGRADAT`. | Limita este aplicată pe categorie. Lasă sarcina să se termine și confirmă revenirea cu monitorul de health. |
| FTP → S3 ⚠ Temporar | **PS** `.\tests\chaos\a-ftp-down.ps1`. | FTP `DOWN/OPEN`, video nou `201` pe S3, descărcarea video-ului de pe FTP `503` cu `Retry-After`. După revenire: upload pe FTP și hash-uri corecte. | Scrierile folosesc rezerva când FTP cade. Dacă scriptul a fost întrerupt și FTP a rămas oprit, `docker compose start ftp`. |
| S3 → DB ⚠ Temporar | **PS** `.\tests\chaos\b-minio-down.ps1`. | Avariile trec pe DB, apoi revin pe S3, cu hash-uri corecte. | Fallback-ul depinde de categorie. Dacă MinIO a rămas oprit, `docker compose start minio`. |
| DB → S3 ⚠ Temporar | **PS** `.\tests\chaos\c-storage-db-offline.ps1`. | Citirile ajung pe S3, retrimiterea este deduplicată, iar după revenire loturile noi merg pe DB. | Se oprește doar `FileGridStorage`, cu metadatele online. Dacă baza a rămas offline, **PS** `. .\tests\chaos\lib\chaos.ps1; Invoke-Sql 'ALTER DATABASE [FileGridStorage] SET ONLINE;'`. |
| Integritate ⚠ Temporar | **PS** `.\tests\chaos\e-corruption.ps1`. | `409 FILE_CORRUPT` la verificare și descărcare; după reparare, `200 ok:true` și hash corect. | Un octet schimbat este suficient pentru detectarea coruperii. În pauză, apasă **Enter** și lasă scriptul să restaureze octetul și să verifice fișierul. |
| Rețea client întreruptă ⚠ Temporar | **PS** `.\docs\demo\demo.ps1 retea-start`. În `https://localhost:8443` pornește upload video ca `teren`, apoi rulează `.\docs\demo\demo.ps1 retea-taie` și `.\docs\demo\demo.ps1 retea-reia`. | Progresul reia de la bucata confirmată. La final, **Încărcat** și verificarea integrității OK. | Clientul reîncearcă, iar serverul păstrează progresul. După upload, `.\docs\demo\demo.ps1 retea-stop` oprește proxy-ul. |

**Simulator ⚠ Temporar.** Pornește-l după ce API-ul este sănătos cu `docker compose --profile sim up -d --build --no-deps meter-simulator`.
Fără `--no-deps`, Compose include dependența `api` și poate modifica numărul de replici. Verifică `docker compose ps api`.
Oprire: `docker compose --profile sim stop meter-simulator`.

## Statistici, Grafana și alerte

| Demonstrație | Comandă / acțiune | Verifică | Pentru juriu |
|---|---|---|---|
| Statistici | Ca `dispecer`, `https://localhost/stats` → **Reîncarcă**. În pregătire, dacă lipsesc datele de comparație **⚠ Temporar**, **PS** `.\tests\load\run.ps1 storm -Short` · **WSL** `bash tests/load/run.sh storm --short`. | Cerințe R1–R4, anomalii, zone cu avarii. Pentru comparație, `200` și patru categorii când există date suficiente pentru `normal` și `storm` în ultimele 24 h. | Comparația folosește operațiile măsurate pe categorii și include latențe, erori și respingeri. |
| Storage-advisor | **PS** `.\docs\demo\demo.ps1 advisor`. Dacă lipsesc măsurători, în pregătire **⚠ Temporar** `.\docs\demo\demo.ps1 advisor -RunBenchmark`. | `200` și recomandare per categorie. Benchmark-ul este sincron și salvează măsurători. | Advisorul compară performanța și costul estimat, iar politica rămâne decizia operatorului. |
| Grafana | Deschide [Operații](https://localhost/grafana/d/filegrid-operatii), [Storage și reziliență](https://localhost/grafana/d/filegrid-storage) și [Cerințe și contoare](https://localhost/grafana/d/filegrid-cerinte). | Latențe și replici în Operații, respingeri bulkhead și acces la documente în Storage, indicatorii R1–R4 în Cerințe. | Grafana agregă metricile Prometheus și arată efectul scenariului tocmai rulat. |
| X-Trace-Id și Loki | La login ca `dispecer`, copiază din DevTools → Network → răspunsul `POST /api/v1/auth/login` doar valoarea headerului `X-Trace-Id`. **PS** `.\docs\demo\demo.ps1 trace -TraceId 'ID_COPIAT'`, cu valoarea copiată în loc de `ID_COPIAT`. | În Loki, linia „Autentificare reușită” și eticheta `replica`. | ID-ul leagă răspunsul de logul instanței API. Unele operații reușite, de exemplu un lot ingerat cu succes, nu scriu o linie în Loki. |
| Alerte ⚠ Temporar | **PS** `.\docs\demo\demo.ps1 acces-interzis`. Pentru confirmare: `docker compose logs --no-color -t --tail 10 alert-webhook`. | `403 FORBIDDEN` pentru `dispecer`, apoi `status=firing` cu `business_alerts=4`. | Accesul refuzat la documente produce un semnal de audit și o notificare prin Alertmanager. |

## Teste automate, în pregătire

| Test | Comandă | Verifică | Pentru juriu |
|---|---|---|---|
| Smoke k6 | **PS** `.\tests\load\run.ps1 smoke` · **WSL** `bash tests/load/run.sh smoke`. | `TRECUT`, toate verificările trecute, 0 erori neașteptate. | Smoke verifică fluxurile principale prin API, inclusiv upload, download și deduplicare. |
| Matricea de roluri ⚠ Temporar | **PS** `.\tests\load\run.ps1 security` · **WSL** `bash tests/load/run.sh security`. Rulează dinainte, cu timp pentru stingerea alertei 4. | `TRECUT`, ≥ 20 răspunsuri `403` așteptate și 0 erori neașteptate. | Testul verifică și accesul permis, și refuzurile din matricea de autorizare. |
| C14 ⚠ Temporar, proiect separat | **WSL** `bash tests/integration/c14/run.sh all`. La final, inclusiv după eșec, `bash tests/integration/c14/run.sh stop`. | 3 API sănătoase, `0 FAIL`, hash-uri corecte și bucăți confirmate de replici diferite. | Testul verifică upload-ul reluabil și transferul în streaming de 1,1 GiB în proiectul izolat `filegrid-c14`. |

## Dacă ceva nu merge în timpul demo-ului

- **Docker / 502 / 503 la pornire** — verifică `docker info` și `docker compose ps -a`, așteaptă câteva secunde și repetă health-ul. Dacă persistă, citește în privat `docker compose logs --tail 40 api nginx`. Pentru un conflict pe 443, identifică procesul înainte să îl oprești. Păstrează volumele.
- **Certificat sau login** — acceptă certificatul local și pe `:8443`, dacă îl folosești. Reloghează tabul cu rolul corect. Dacă PowerShell blochează scripturile, `Set-ExecutionPolicy -Scope Process Bypass` în terminalul de demo.
- **Upload blocat** — rulează `.\docs\demo\demo.ps1 retea-reia`. Dacă UI afișează **Upload video neterminat**, reselectează același fișier și apasă **Continuă**. Pentru restul demo-ului folosește `https://localhost`.
- **Storage încă DOWN** — termină pauza cu **Enter**, apoi folosește revenirea din rândul FTP, MinIO sau DB. Verifică `UP/CLOSED` înainte de următorul scenariu. Dacă restaurarea octetului a eșuat, scoate fișierul de test din prezentare.
- **Statistici 503 / grafice goale** — la `REQUEST_METRICS_UNAVAILABLE` arată `details.missing`. La `STORAGE_MEASUREMENTS_INCOMPLETE` pune benchmark-ul în pregătire. În Grafana verifică intervalul și ajustează-l să includă rularea.
- **Alerta 4 nu apare din nou** — verifică logul. Dacă este deja `firing`, arată notificarea existentă și panoul de acces refuzat. Înainte de o nouă încercare, așteaptă `resolved`.

[Traseul de 5 minute](demo-script.md) · [Scenarii k6](../../tests/load/README.md) · [Scenarii de haos și recuperare](../../tests/chaos/README.md)
