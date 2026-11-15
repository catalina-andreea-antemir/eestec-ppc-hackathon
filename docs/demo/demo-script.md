# Demo live FileGrid PPC (F11)

Demo-ul se face **live**, în fața juriului, pe stack-ul real pornit local (3 replici `api`). Nu există video de
rezervă, iar juriul poate întrerupe și cere alte lucruri. De aceea scriptul are două părți:

1. **Traseul principal**: 6 pași, cel mult **5 minute**, în ordinea de mai jos.
2. **Meniul de rezervă**: demonstrații scurte (M1–M12), fiecare independentă, pentru întrebările juriului.

Toate comenzile se rulează din rădăcina repo-ului, în PowerShell (5.1 sau 7). Parolele nu apar nicăieri pe ecran:
scripturile le citesc din `.env`. Comenzile scurte ale pașilor sunt în [`demo.ps1`](demo.ps1), care refolosește
biblioteca testelor de haos (`tests/chaos/lib/chaos.ps1`).

Legendă: 🗣 ce spune prezentatorul · ⌨ ce se rulează sau pe ce se dă click · ✅ rezultatul așteptat ·
👁 unde se vede · 🛟 dacă nu merge. Mențiunea **[de verificat la repetiție]** marchează ce nu s-a putut confirma pe
stack la scrierea scriptului (vezi și [tabelul de la final](#ce-s-a-verificat-și-ce-rămâne-de-verificat)).

---

## Pregătire (cu 30–45 de minute înainte)

### Stack-ul

- [ ] Docker Desktop pornit (8 GB alocați).
- [ ] `.\scripts\start.ps1`: creează `.env` dacă lipsește, `docker compose up -d --build`, așteaptă health-ul și
      afișează adresa și utilizatorii.
- [ ] `docker compose up -d --scale api=3`, apoi `docker compose ps`: 3 rânduri `api` și toate serviciile `healthy`.
- [ ] Datele de test, dacă lipsește `sample-data\`:
      `powershell -ExecutionPolicy Bypass -File scripts\generate-sample-data.ps1` (scrie și video-urile de 100 MiB,
      500 MiB și 1,1 GiB; durează câteva minute).
- [ ] Benchmark-ul pentru `storage-advisor`, ca admin: `.\docs\demo\demo.ps1 advisor -RunBenchmark`
      (`POST /api/v1/admin/benchmark/run`, sincron, 1–3 minute; `409 BENCHMARK_RUNNING` dacă rulează deja unul).
      La final apare tabelul cu recomandările.
- [ ] Testele de siguranță rulate **cu cel puțin 10 minute înainte** (nu mai târziu, vezi alerta 4 mai jos):
      `.\tests\load\run.ps1 smoke` (71/71 la F8) și, opțional, `.\tests\chaos\run-all.ps1 -Only a,e`.
- [ ] Rețeaua degradată pentru pasul 5, pornită dinainte: `.\docs\demo\demo.ps1 retea-start` (toxiproxy din profilul
      `chaos`, proxy `nginx` pe `https://localhost:8443` → `nginx:443`, limită de upload 20 MB/s).
      **[de verificat la repetiție]**

### Alerta 4 trebuie să fie „resolved” înainte de început

Alerta 4 (`FileGridUnexpectedDocumentAccess`) rămâne `firing` cât există un acces refuzat în ultimele 5 minute
(`increase(filegrid_doc_access_total{result!="ok"}[5m]) > 0`). Dacă e deja `firing`, Alertmanager nu trimite o
notificare nouă la pasul 2. Verificare:

```powershell
docker compose logs --no-color -t --tail 3 alert-webhook
```

Ultima linie trebuie să fie `status=resolved ... business_alerts=4` (sau să nu existe nicio linie cu `firing`
nerezolvat). `tests\load\run.ps1 security` și orice 403 pe documente o declanșează: după ele, așteaptă ~6 minute.

### Ecranele

Token-urile stau în `sessionStorage`, deci **fiecare tab are propriul login**. Nu duplica tab-urile (copiază și
sesiunea); deschide-le noi și loghează-te în fiecare cu ~10 minute înainte (token 15 min, reînnoit automat cu
refresh-ul de 8 h).

| Fereastră | Adresă | Cont | Pagina |
|---|---|---|---|
| **B1** | `https://localhost` | `racordari` | Upload, categoria „Document de racordare” |
| **B2** | `https://localhost` | `teren` | Upload, categoria „Avarie”, zona Muntenia |
| **B3** | `https://localhost` | `admin` | Căutare (vede toate categoriile) |
| **B4** | `https://localhost` | `dispecer` | Statistici (`/stats`) |
| **B5** | `https://localhost:8443` (acceptă certificatul) | `teren` | Upload, „Video dronă”, fișierul `sample-data\inspection-videos\drona-linie-110kV-tronson-7.mp4` ales, **neînceput** |
| **G1** | `https://localhost/grafana/d/filegrid-operatii` | Grafana `admin` (`GRAFANA_ADMIN_PASSWORD` din `.env`) | FileGrid – Operații |
| **G2** | `https://localhost/grafana/d/filegrid-storage` | idem | FileGrid – Storage și reziliență |
| **G3** | `https://localhost/grafana/d/filegrid-cerinte` | idem | FileGrid – Cerințe de business și contoare |

Grafana: interval „Last 15 minutes”, refresh 10 s (implicit în dashboard-uri).

| Terminal | Ce rulează | Pregătire |
|---|---|---|
| **T1** | comenzile `demo.ps1` | `Set-ExecutionPolicy -Scope Process Bypass` |
| **T2** | scenariile de haos | `Set-ExecutionPolicy -Scope Process Bypass; $env:CHAOS_PAUSE = '1'` |
| **T3** | `.\tests\chaos\watch-health.ps1` (starea `/admin/health` o dată pe secundă) | pornit, verde: `UP  meta UP  \|  FTP UP/CLOSED  \|  S3 UP/CLOSED  \|  DB UP/CLOSED` |

Font mare în terminale (Ctrl + rotița), fereastra T3 vizibilă mereu, într-un colț.

### Roluri în echipă (propunere)

Flavius prezintă și conduce UI-ul; Catalina rulează terminalele T1/T2 și răspunde la întrebările de arhitectură;
Ovidiu ține Grafana și răspunde la întrebările despre SQL Server, statistici și observabilitate.

---

## Traseul principal (≤ 5 minute)

| Pas | Ce arată | Timp | Cumulat |
|---|---|---|---|
| 1 | Lot retrimis → duplicat respins | 0:35 | 0:35 |
| 2 | Document criptat + acces cu rol greșit → 403 și alertă | 1:00 | 1:35 |
| 3 | „Zi de furtună”: 429 doar pe citiri | 0:50 | 2:25 |
| 4 | FTP oprit: video-urile trec pe S3 | 0:55 | 3:20 |
| 5 | Video de 1,1 GB pe rețea tăiată, se reia singur | 0:50 | 4:10 |
| 6 | Statistici + storage-advisor | 0:45 | 4:55 |

Dacă se depășește timpul: la pasul 4 nu se mai așteaptă revenirea FTP pe ecran (scriptul repară singur), iar la
pasul 6 se arată doar cartea `storage-advisor`.

### Pasul 1 – Duplicat respins (0:35)

🗣 „Un contor trimite citirile unei zile într-un lot. Rețeaua cade înainte să primească răspunsul, așa că îl
retrimite cu aceeași cheie de idempotență. FileGrid recunoaște lotul și nu dublează nicio citire.”

⌨ T1:

```powershell
.\docs\demo\demo.ps1 duplicat
```

Ca utilizatorul `contor` (METER_SERVICE), scriptul trimite de două ori `POST /api/v1/meter-readings` cu
`Idempotency-Key: <meterId>_<ts primei citiri>` (formatul din contract) și 96 de citiri pentru un contor nou
`RO-MUN-DEMO<hhmmss>`, apoi numără rândurile din `FileGridMeta.dbo.meter_readings`.

✅ (verificat pe stack)

```text
POST /meter-readings (prima trimitere) -> HTTP 201   X-Trace-Id: …
    {"fileId":"b694cee3-…","meterId":"RO-MUN-DEMO052030","readingsCount":96,"sha256":"eecc99f6…","duplicate":false}
POST /meter-readings (retrimis, aceeasi cheie) -> HTTP 200   X-Trace-Id: …
    {"fileId":"b694cee3-…","meterId":"RO-MUN-DEMO052030","readingsCount":96,"sha256":"eecc99f6…","duplicate":true}
acelasi fileId: True
FileGridMeta.dbo.meter_readings: 96 citiri, 96 momente distincte, 1 fisier(e)  (asteptat 96 / 96 / 1)
```

👁 Terminal. Opțional, G3 → „Loturi retrimise și duplicate respinse” crește cu 1 **[de verificat la repetiție]**.

🗣 (în timp ce apare) „201 prima dată, 200 cu `duplicate: true` și același `fileId` a doua oară; în bază sunt tot 96
de citiri, nu 192.”

### Pasul 2 – Document cu date personale, criptat (1:00)

🗣 „Departamentul de racordări încarcă o cerere cu date personale. Documentul se criptează înainte să ajungă în
storage, iar cine nu are dreptul să-l vadă primește 403 și declanșează o alertă.”

⌨ B1 (`racordari`, Upload): categoria **Document de racordare**, fișierul
`sample-data\connection-docs\contract-racordare-fictiv-003.pdf` → **Încarcă**.

✅ Rezultatul upload-ului arată storage **DB**, marcajul **criptat** și cele două hash-uri: „SHA-256 (original)” și
„SHA-256 (stocat, criptat)” **[de verificat la repetiție: ecranul UI pe stack-ul real]**.

⌨ T2, imediat după upload (rulează în fundal cât se explică restul):

```powershell
.\docs\demo\demo.ps1 acces-interzis
```

⌨ T1:

```powershell
.\docs\demo\demo.ps1 criptare -File .\sample-data\connection-docs\contract-racordare-fictiv-003.pdf
```

✅ T1 (verificat pe stack cu `cerere-racordare-fictiv-001.pdf`):

```text
document cerere-racordare-fictiv-001.pdf  id ac644816-…  storage DB  encrypted True
  sha256 local (original)      9d72d43c…
  sha256Plain din metadate     9d72d43c…
  sha256 stocat (criptat)      b44d1b64…
FileGridMeta.dbo.files: storage DB, encrypted 1, original_filename in baza: ASxz34Oisx307nzK… (si numele e criptat)
primii 16 octeti, fisierul original : 255044462D312E340A312030206F626A   (%PDF-1.4)
primii 16 octeti, FileGridStorage   : 0154406F4A1A93077A1AFE98177A4D11   (AES-GCM, nu se vede antetul %PDF)
GET /files/{id}/download (racordari) -> HTTP 200, X-Content-SHA256 9d72d43c…
sha256 fisier descarcat = original: True
```

🗣 „În `FileGridStorage` nu se vede antetul `%PDF`: sunt octeți criptați AES-GCM. Chiar și numele fișierului e
criptat în baza de metadate. Prin FileGrid, utilizatorul autorizat descarcă fișierul original, cu hash identic.”

✅ T2 (verificat: 403 la 03:16:53, notificarea la 03:17:23, deci ~30 s):

```text
dispecer: GET /files?category=CONNECTION_DOC -> HTTP 403   X-Trace-Id: …
    {"status":403,"code":"FORBIDDEN","message":"Rolul tău nu are acces la această categorie.",…}
astept alerta 4 (FileGridUnexpectedDocumentAccess) in alert-webhook (maxim 90 s)...
dupa 30 s: alert-webhook-1  | … alert_notification status=firing alerts=1 business_alerts=4
```

👁 T2; G2 → „Acces la documente după rezultat”; mai târziu, la pasul 6, cartea cerințelor arată **R3 AT_RISK**
(„1 accesuri refuzate în ultimele 5 minute”, verificat).

🗣 „Dispecerul nu are acces la documentele de racordare: primește 403, accesul e auditat, iar în mai puțin de un
minut alerta 4 ajunge la receptorul de alerte.”

🛟 „nicio notificare nouă în 90 s” = alerta era deja `firing` (vezi pregătirea). Arată atunci G2 și spune că
notificarea a plecat la primul acces refuzat.

### Pasul 3 – Zi de furtună: doar categoria lovită primește 429 (0:50)

🗣 „Acum vine furtuna: zeci de mii de citiri de contor pe secundă. Fiecare categorie are compartimentul ei, deci
citirile primesc 429 cu Retry-After, iar echipele de teren încarcă avarii în continuare.”

⌨ T2:

```powershell
.\tests\chaos\f-bulkhead.ps1
```

k6 (`grafana/k6:1.3.0`, în rețeaua `filegrid_storage-net`) trimite 30 s citiri cu 150 VU direct la `api:8080`
(peste limita `maxConcurrentRequests: 50` din `meter-reading.yml`), în paralel cu avarii și căutări ca utilizatorii.

⌨ În timpul furtunii, B2 (`teren`, Upload): **Avarie**, `sample-data\outage-records\avarie-01-ST-BRASOV-114.csv`,
zona **Muntenia** → **Încarcă**.

✅ Avaria se încarcă normal (201, storage S3). La final, scriptul afișează:

```text
citiri: N x 201, M x 429, alte coduri 0
avarii: K trimise, 0 esuate, 0 x 429
cautari: L trimise, 0 esuate, 0 x 429
[OK] METER_READING saturata: 429 primite
[OK] avariile (OUTAGE_RECORD) trec: toate 201, niciun 429
REZULTAT f-bulkhead : TRECUT
```

(la F9: zeci de mii de `429 CATEGORY_SATURATED` pe citiri, 0 pe avarii și căutări).

👁 G2 → „Cereri respinse de bulkhead per categorie” (doar `METER_READING`) și „Răspunsuri 429 / 503 (HTTP)”;
G1 → „Throughput per categorie” **[de verificat la repetiție]**.

⚠ **Nu se caută în UI în timpul furtunii**: căutarea dispecerului primește 200, dar cu p95 de ordinul secundelor
(blocări SQL pe `dbo.files`, vezi [răspunsurile pregătite](#căutarea-e-lentă-în-vârf)).

Alternative (nu în traseul principal): simulatorul `SIM_PROFILE=peak` (M12) sau `.\tests\load\run.ps1 peak -Short`
(M6; verdictul lui iese `ESUAT` pe pragul de latență a căutării, deci nu e potrivit live).

### Pasul 4 – FTP oprit: video-urile trec pe S3 (0:55)

🗣 „Video-urile de inspecție stau pe FTP. Oprim serverul FTP de tot.”

⌨ T2 (cu `$env:CHAOS_PAUSE = '1'` setat la pregătire):

```powershell
.\tests\chaos\a-ftp-down.ps1
```

Scriptul încarcă un video pe FTP, rulează `docker compose stop ftp`, încarcă al doilea video, verifică descărcările,
apoi **se oprește** la `[pauza demo] Enter pentru reparare`, cu FTP-ul oprit.

✅ / 👁 în pauză:

- T3: `FTP DOWN/OPEN`, starea generală `DEGRADED` (galben); S3 și DB rămân `UP/CLOSED`.
- T2: `[OK] video 2 (FTP cazut) -> 201 pe S3 (rezerva)` și
  `[OK] video 1 (pe FTP) nu se poate descarca acum: 503 cu Retry-After`.
- B3 (`admin`, Căutare), text în nume `chaos-`: `…-inspectie-1-inainte.mp4` pe **FTP**, `…-inspectie-2-in-cadere.mp4`
  pe **S3**.
- G2 → „Storage FTP” = DOWN și „Istoric UP/DOWN per storage”.

🗣 „Doar video-urile sunt afectate: cele noi merg pe S3, rezerva din politică. Video-ul vechi de pe FTP nu s-a
pierdut; până revine FTP primește 503 cu Retry-After, iar avariile, citirile și documentele nici nu observă.”

⌨ **Enter** în T2.

✅ `docker compose start ftp`, T3 revine la `FTP UP/CLOSED` în ~5–10 s, `…-inspectie-3-dupa.mp4` e din nou pe FTP,
toate trei se descarcă cu hash corect, `REZULTAT a-ftp-down : TRECUT`.

🛟 Dacă scriptul e întrerupt (Ctrl+C) cu FTP oprit: `docker compose start ftp`.

Opțional, dacă juriul întreabă de baza de date: M8 (`FileGridStorage` offline).

### Pasul 5 – Video de 1,1 GB pe rețea tăiată, se reia singur (0:50)

🗣 „O echipă de teren urcă un video de 1,1 GB de pe o conexiune proastă. Tăiem rețeaua în mijlocul upload-ului.”

⌨ B5 (`https://localhost:8443`, `teren`, prin toxiproxy, limită 20 MB/s): **Încarcă**
(`drona-linie-110kV-tronson-7.mp4`, 1 181 116 006 octeți, bucăți de 16 MiB).

⌨ T1, după ~10 s de progres:

```powershell
.\docs\demo\demo.ps1 retea-taie
```

✅ B5: faza **„Se reîncearcă”**, cu mesajul „… Reîncerc în N s (reîncercarea k).”; bara de progres nu se resetează.

⌨ T1, după ~10 s:

```powershell
.\docs\demo\demo.ps1 retea-reia
```

✅ B5 revine la **„Se încarcă”** de la bucata la care a rămas (`nextChunk` de pe server), fără să reia de la zero.
Upload-ul continuă în fundal; se revine la el după pasul 6: faza **„Încărcat”**, storage **FTP**, SHA-256 afișat.

🗣 „Clientul reîncearcă singur, cu backoff, până la 2 minute; serverul știe ce bucăți are și le confirmă idempotent.
La final hash-ul întregului fișier se compară cu cel calculat la client.”

👁 B5; T3 rămâne verde (doar rețeaua clientului e afectată).

Note:

- Între `retea-taie` și `retea-reia` să nu treacă mai mult de ~90 s: UI renunță după 2 minute de la primul eșec
  (`DEFAULT_RETRY.timeoutMs`); atunci rămâne „Upload video neterminat” și se continuă cu același fișier (M10).
- Varianta scriptată: `tests/chaos/h-client-network.ps1` (lucrat în altă sesiune; numele e fix) **[de verificat la
  repetiție: parametrii și ieșirea]**.
- **[de verificat la repetiție]**: durata reală a upload-ului de 1,1 GiB la 20 MB/s (~1 minut teoretic) și
  comportamentul UI la tăierea proxy-ului. Dacă e prea lent, `.\docs\demo\demo.ps1 retea-start -RateKBps 51200`
  (limita se schimbă doar la pornire; înainte, `retea-stop`) sau video-ul de 500 MiB.

### Pasul 6 – Statistici și storage-advisor (0:45)

🗣 „Pe lângă stocare, FileGrid răspunde la întrebări de decizie. Ecranul de statistici al dispecerului.”

⌨ B4 (`dispecer`, Statistici) → **Reîncarcă**.

✅ / 👁 Cele 5 cărți, fiecare cu propoziția de interpretare sus:

| Carte | Ce arată (date reale la scriere) |
|---|---|
| Cerințe R1–R4 | R1 OK (nicio citire neconfirmată), **R3 AT_RISK** după pasul 2, R4 OK (0% transferuri video eșuate) |
| Storage-advisor | matricea 4 × 3 cu scor, recomandarea per categorie, „politica actuală”, „★ recomandat”, ipoteza de cost (FTP 1,0 / S3 1,4 / DB 3,2 per GB) |
| Zi normală vs. vârf | mesaj informativ „indisponibil” (`/stats/peak-vs-normal` → 503 `REQUEST_METRICS_UNAVAILABLE`, lucrează Ovidiu) |
| Zone cu avarii | clasament: Muntenia 37,9%, Banat 31,8%, Dobrogea 30,3% (2 333 avarii în 30 de zile) |
| Anomalii | consumuri > mediană + 3σ per contor (cele mai mari abateri primele) și contoare tăcute |

⌨ Opțional, T1: `.\docs\demo\demo.ps1 advisor` (tabelul recomandărilor în terminal).

🗣 „Advisorul combină benchmark-ul rulat de admin pe toate cele 12 combinații cu datele live. Ce recomandă și cum
interpretăm diferențele față de politică explicăm imediat.” (vezi [storage-advisor](#de-ce-storage-advisor-nu-e-de-acord-cu-politica))

⌨ Înapoi la B5: upload-ul de 1,1 GB e **„Încărcat”**.

🗣 Încheiere (10 s): „Patru tipuri de fișiere, trei storage-uri, o politică în YAML; un storage cade, o categorie e
lovită de vârf, rețeaua clientului se rupe, iar datele confirmate nu se pierd și nu se dublează.”

---

## Răspunsuri pregătite

### „Ce se întâmplă dacă cade tot SQL Server?”

(din [ADR-001](../decisions/ADR-001-sql-server-punct-unic-de-cadere.md))

> E o limită pe care o recunoaștem: avem un singur container SQL Server, fiindcă fiecare instanță cere ~2 GB din
> cei 8 GB ai mediului. Dacă cade, ingestia răspunde 503 cu Retry-After și health arată DOWN, dar nimic confirmat nu
> se pierde (volum persistent), contoarele retrimit cu aceeași cheie fără duplicate, iar fișierele de pe FTP și S3
> rămân intacte. Scindarea pe două instanțe e deja pregătită din configurație (`STORAGE_DB_HOST`, profilul `split`);
> redundanța reală (replicare, cluster) rămâne pentru producție.

Ce arătăm live e căderea storage-ului DB cu metadatele în picioare (M8), nu oprirea containerului `mssql`.

### De ce storage-advisor nu e de acord cu politica

Pe stack, la scriere: politica și recomandarea coincid doar la `INSPECTION_VIDEO` (FTP). Advisorul recomandă S3 pentru
`METER_READING` (politica: DB), FTP pentru `OUTAGE_RECORD` (politica: S3) și S3 pentru `CONNECTION_DOC` (politica: DB).

> Advisorul măsoară latența, erorile și un cost relativ presupus, pe laptopul nostru, unde toate cele trei storage-uri
> sunt containere pe același disc, deci diferențele de latență sunt mici și nu reflectă un mediu real. Politica ține
> cont și de ce nu se măsoară: documentele cu date personale rămân în baza cu acces controlat și criptare, iar
> citirile au nevoie de tranzacții împreună cu metadatele. De aceea advisorul doar recomandă: decizia rămâne la om, iar
> o schimbare înseamnă un YAML recitit fără restart (M4).

Detaliile măsurătorilor sunt în analiza lui Ovidiu (O9).

### De ce „Zi normală vs. vârf” nu are date

`/stats/peak-vs-normal` răspunde încă 503 `REQUEST_METRICS_UNAVAILABLE` (comparația are nevoie de numărul de cereri
și de respingeri per categorie și scenariu). Aceeași comparație se vede în G1, secțiunea „Zi normală vs. vârf”
(throughput și p95 per categorie acum vs. acum − interval).

### Căutarea e lentă în vârf

> Izolarea pe categorie funcționează: în furtună, căutările nu primesc niciun 429. Dar p95 al căutării crește la
> câteva secunde, fiindcă ingestia citirilor ține blocări pe tabela de fișiere, iar READ_COMMITTED_SNAPSHOT e oprit pe
> FileGridMeta. Remedierea identificată e activarea lui sau un index pe `files(category, status, created_at)`.

### Alte întrebări probabile

- **De ce FTP nu trece prin toxiproxy?** Modul pasiv ar cere câte un proxy pentru fiecare port de date
  (21000–21049); căderea FTP o arătăm prin oprirea containerului (pasul 4).
- **Cum încape 1,1 GB în 512 MB de RAM per replică?** Upload în bucăți de 16 MiB, scrise în streaming
  ([ADR-003](../decisions/ADR-003-upload-in-bucati.md)); testul C14 `big` măsoară memoria replicilor (M2).
- **Cum se descarcă fără să expui storage-ul?** Link semnat HMAC, de scurtă durată, prin nginx
  ([ADR-004](../decisions/ADR-004-descarcare-prin-link-semnat.md)).

---

## Meniul de rezervă

Fiecare demonstrație pornește din starea normală a stack-ului și o lasă tot așa.

### M1 – Integritate: fișier corupt detectat

⌨ T2: `.\tests\chaos\e-corruption.ps1` (cu `CHAOS_PAUSE=1`). Scriptul încarcă un video pe FTP și schimbă un octet
direct pe disc (`dd conv=notrunc`).
✅ În pauză: B3 (`admin`), Căutare `inspectie-corupere` → **Verifică integritatea** → corupt (`409 FILE_CORRUPT`, cu
hash-ul așteptat și cel calculat); descărcarea primește tot 409. După Enter octetul revine și verificarea dă OK.
👁 G2 → „Fișiere corupte detectate (interval selectat)”.

### M2 – Scalare pe 3 replici

⌨ `docker compose up -d --scale api=3`, apoi `docker compose ps api` (3 replici `healthy`).
⌨ `docker compose logs --no-color --tail 60 nginx | Select-String 'upstream='` → cererile alternează între 3 adrese
`upstream=172.18.0.x:8080` (verificat: 3 adrese distincte).
👁 G1 → „Instanțe api colectate” (Prometheus are 3 ținte `job="api"`, verificat) și „Heap JVM per instanță”.
⌨ Testul C14, **rulat dinainte** (proiect Docker separat `filegrid-c14`, port 18443, ocupă memorie):
`bash tests/integration/c14/run.sh up`, apoi `small` (și `big` pentru 1,1 GiB), la final `stop`; se arată ieșirea
`PASS …`.

### M3 – Urmărirea unei operații după `X-Trace-Id`

⌨ În browser, DevTools → Network → cererea de login → header-ul de răspuns `X-Trace-Id`. Apoi G-Explore
(`https://localhost/grafana/explore`), sursa **Loki**:

```logql
{job="filegrid-api"} | json | traceId="<X-Trace-Id>"
```

✅ Linia „Autentificare reușită: dispecer (DISPATCH)”, cu eticheta `replica` (containerul care a răspuns).
Varianta din terminal: `.\docs\demo\demo.ps1 trace -TraceId <X-Trace-Id>` (verificat).
⚠ În Loki apar doar cererile care scriu un log (login, erori, căderi de storage); un lot ingerat cu succes sau un 403
nu au linie. Cel mai sigur: login-ul, sau o operație din timpul pasului 4.

### M4 – Politica schimbată fără cod

⌨ În `backend/filegrid-api/src/main/resources/policies/inspection-video.yml`: `primaryStorage: FTP` →
`primaryStorage: S3`, `fallbackStorage: S3` → `fallbackStorage: FTP` (trebuie să difere), salvează. Directorul e
montat read-only în `api` (`/app/policies`); `PolicyEngine` îl recitește în cel mult 2 s, fără restart.
✅ Un video mic încărcat din B2 ajunge pe **S3**. Se pune fișierul înapoi (`git checkout -- <fișier>`).
Un câmp invalid se loghează și se păstrează politica anterioară. **[de verificat la repetiție]**

### M5 – Securitate

- `docker compose ps --format "table {{.Service}}\t{{.Ports}}"`: doar `nginx` publică porturi (80 → redirect,
  443); `toxiproxy` numai pe 127.0.0.1, în profilul `chaos`. Rețeaua `storage-net` e `internal: true`.
- TLS (self-signed generat la prima pornire), JWT de 15 minute, rol în token; descărcare prin link semnat.
- Fără parole în repo: `.env` e generat de `start.ps1` cu secrete aleatorii și ignorat de git
  (`git check-ignore .env`); utilizatorii aplicației și ai storage-urilor au drepturi minime.
- ⌨ `.\tests\load\run.ps1 security`: matricea de roluri 4.4, 49/49 verificări, 29 × 403 (F8). ⚠ Declanșează
  alerta 4: nu înainte de pasul 2.

### M6 – Bulkhead, varianta k6 cu grafice

⌨ `.\tests\load\run.ps1 peak -Short -Dashboard` → `http://localhost:5665` (graficele k6 live, ~45 s).
✅ `429` doar pe citiri; avariile și căutările fără erori. Verdictul final iese `ESUAT` pe pragul de latență a
căutării (vezi [căutarea e lentă în vârf](#căutarea-e-lentă-în-vârf)): se spune deschis.

### M7 – Latență p50 / p95 / p99 pe categorie × storage

👁 G1 → „Latență per categorie (p50 / p95 / p99)”, „Latență per storage (p50 / p95 / p99)” și tabelul
„p95 categorie × storage (ultimele 5 minute)” (metrica `filegrid_operation_seconds`).

### M8 – FileGridStorage offline (storage-ul DB, cu metadatele în picioare)

⌨ T2: `.\tests\chaos\c-storage-db-offline.ps1` (cu `CHAOS_PAUSE=1`;
`ALTER DATABASE FileGridStorage SET OFFLINE WITH ROLLBACK IMMEDIATE`).
✅ În pauză: T3 `DB DOWN/OPEN`; loturile de citiri merg pe **S3** (201), retrimiterea dă `200 duplicate:true`; căutarea
dispecerului merge (FileGridMeta e pornită). După Enter: `SET ONLINE`, loturile revin pe DB; la final
`288 citiri, 288 momente distincte, 3 fișiere, 0 neconfirmate`.
🛟 Întrerupt cu baza offline: `.\tests\chaos\c-storage-db-offline.ps1` repară în `finally`; altfel, în PowerShell:
`. .\tests\chaos\lib\chaos.ps1; Invoke-Sql 'ALTER DATABASE [FileGridStorage] SET ONLINE;'`.

### M9 – MinIO (S3) oprit

⌨ `.\tests\chaos\b-minio-down.ps1` (cu `CHAOS_PAUSE=1`). ✅ Avariile (`OUTAGE_RECORD`) merg pe **DB**; după pornire,
înapoi pe S3; toate fișierele au hash corect. 🛟 `docker compose start minio`.

### M10 – Upload reluat după ce clientul moare

⌨ `.\tests\chaos\g-chunked-resume.ps1`: video de 40 MiB, clientul (curl) e omorât în bucata 2.
✅ `GET /uploads/{id}` arată `nextChunk = 2`, reluarea merge, `complete` → 201, SHA-256 final = hash-ul local.
Din UI: în timpul unui upload video, refresh la pagină → „Upload video neterminat” → alege același fișier →
**Continuă** de la `nextChunk`.

### M11 – Rețea degradată între api și storage

⌨ `.\tests\chaos\d-toxiproxy.ps1 -Targets s3` (latență, erori scurte, apoi proxy oprit). ⚠ Recreează `api` prin
override (durează), iar la final rulează `--profile chaos rm -sf toxiproxy`, deci **oprește și proxy-ul de pe 8443
de la pasul 5**. Doar după pasul 5, și doar dacă e timp.

### M12 – Simulatorul de contoare, profil de vârf

⌨ `$env:SIM_PROFILE = 'peak'; docker compose --profile sim up -d meter-simulator` (120 de contoare, un lot pe
secundă, 5 minute, 5% anomalii, 5% contoare tăcute, 10% duplicate).
👁 G3 → „Citiri trimise vs. confirmate”, „Loturi retrimise și duplicate respinse”; după rulare, cartea Anomalii.
Oprire: `docker compose --profile sim stop meter-simulator`; `Remove-Item Env:SIM_PROFILE`.
**[de verificat la repetiție: dacă produce 429 cu 3 replici]**. Rulările repetate cu aceleași setări trimit aceleași
chei (răspund 200 duplicate). Nu se caută în UI cât rulează.

---

## Resetare între repetiții

Fără `docker compose down -v`: șterge volumele (date, benchmark, Grafana) și cere totul de la capăt.

1. Scripturile de haos repară singure (în `finally`). Verifică T3: toate `UP/CLOSED`; `docker compose ps`: toate
   `healthy`, 3 × `api`.
2. Rețeaua de la pasul 5: `.\docs\demo\demo.ps1 retea-reia` dacă a rămas tăiată. Toxiproxy poate rămâne pornit între
   repetiții; la final, `.\docs\demo\demo.ps1 retea-stop`.
3. B5: dacă a rămas „Upload video neterminat”, **Renunță la upload**.
4. Alerta 4: așteaptă ~6 minute după ultimul 403, până la `status=resolved` în `alert-webhook`.
5. Datele create rămân (prefixe `RO-MUN-DEMO…`, `chaos-…`, `k6-…`) și nu încurcă: fiecare rulare folosește chei
   noi. Ca să pornești de la zero fără să pierzi imaginile: `docker compose down` (fără `-v`) și `.\scripts\start.ps1`,
   apoi `--scale api=3`; benchmark-ul rămâne în bază.
6. Repornește `watch-health.ps1` dacă a fost oprit și reloghează tab-urile mai vechi de câteva ore.

---

## Ce s-a verificat și ce rămâne de verificat

Verificat pe stack-ul pornit (15.11.2026, 3 replici `api`), fără oprirea vreunui serviciu:

| Element | Rezultat |
|---|---|
| Pasul 1 (`demo.ps1 duplicat`) | 201 → 200 `duplicate:true`, același `fileId`, 96/96/1 în `meter_readings` |
| Pasul 2 (`demo.ps1 criptare`, document încărcat prin API ca `racordari`) | storage DB, `encrypted`, primii octeți diferiți de `%PDF`, nume criptat în `dbo.files`, descărcare cu hash = original |
| Pasul 2, 403 + alerta 4 (`demo.ps1 acces-interzis`) | 403 `FORBIDDEN` pentru `dispecer`; `firing ... business_alerts=4` în `alert-webhook` după 31 s; `resolved` la ~4,5 min după ultimul 403 |
| `/stats/*` | storage-advisor 200 (1 din 4 potriviri), anomalies 200, outage-zones 200, requirements 200 (R3 `AT_RISK` după 403), peak-vs-normal 503 |
| Roluri | `teren` → 403 la `/stats/requirements` și la benchmark |
| M2 | 3 adrese `upstream=` distincte în nginx; 3 ținte `job="api"` în Prometheus |
| M3 | `demo.ps1 trace` găsește login-ul în Loki, cu `replica`; un lot reușit nu are linie |
| Căile, comenzile, câmpurile | citite din scripturi, compose, contract, politici, ADR-uri și UI |

**[de verificat la repetiție]** (nu s-au putut rula acum: stack-ul era folosit de testele de haos F9):

- ecranele UI pe stack-ul real: rezultatul upload-ului de document (pasul 2), avaria în timpul furtunii (pasul 3),
  coloana storage în Căutare (pasul 4), cele 5 cărți din Statistici (pasul 6);
- `demo.ps1 retea-start / retea-taie / retea-reia / retea-stop` și comportamentul UI pe `https://localhost:8443`;
  durata upload-ului de 1,1 GiB; scriptul `h-client-network.ps1`;
- `f-bulkhead.ps1` și `a-ftp-down.ps1` live, cu panourile G1/G2 (scripturile trec la F9, panourile nu au fost văzute);
- M4 (politica recitită), M12 (simulatorul de vârf), `demo.ps1 advisor -RunBenchmark` (benchmark-ul are deja
  693 de măsurători în bază);
- cronometrarea fiecărui pas.
