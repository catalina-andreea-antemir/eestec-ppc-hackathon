# Fluxurile principale

Trei fluxuri, așa cum sunt fixate în planul v4 (4.1) și în [openapi.yaml](../api/openapi.yaml). Diagramele arată
drumul normal; erorile și reîncercările sunt descrise sub fiecare.

## 1. Ingestia citirilor pe loturi (`POST /meter-readings`)

Un contor trimite un lot de 1–50 KB (implicit 96 de citiri). Fiecare citire e unică prin `(meter_id, reading_ts)`;
corpul brut se salvează și ca fișier `METER_READING`. Deduplicarea se face **înainte** de scrierea fișierului, ca
duplicatele să nu lase fișiere orfane.

```mermaid
sequenceDiagram
    autonumber
    participant S as meter-simulator
    participant A as api (endpoint, Catalina)
    participant R as MeterReadingRepository (Ovidiu)
    participant M as mssql / FileGridMeta
    participant P as StorageAdapter (DB principal, S3 rezervă)

    S->>A: POST /meter-readings<br/>Idempotency-Key = meterId_ts1, X-Scenario
    A->>A: validează cheia = meterId + ts-ul primei citiri (altfel 400)
    A->>A: SHA-256 pe corpul brut
    A->>R: tryBegin(lot)
    R->>M: INSERT toate citirile, file_id NULL, attempt_id nou, într-o tranzacție
    alt lot nou
        M-->>R: OK
        R-->>A: NEW(attemptId)
        A->>P: put(corp brut) prin politică
        P-->>A: confirmat
        A->>M: rând în files (CONFIRMED)
        A->>R: attachFile(attemptId, fileId)
        A-->>S: 201 + X-Trace-Id
    else DUPLICATE_CONFIRMED (toate rândurile au file_id)
        R-->>A: DUPLICATE_CONFIRMED
        A->>A: filegrid_meter_duplicates_total++
        A-->>S: 200 (duplicat ignorat)
    else DUPLICATE_PENDING (fără file_id, sub 60 s)
        R-->>A: DUPLICATE_PENDING
        A-->>S: 503 + Retry-After
    else CONFLICT (suprapunere parțială cu alt lot)
        R-->>A: CONFLICT
        A-->>S: 409 BATCH_OVERLAP
    end
    Note over A,P: la eșec în scriere sau în files: delete(attemptId) (compensare) și 503 + Retry-After
```

Reguli care contează pentru Ovidiu și Flavius:

* **Erori SQL Server** 2627 și 2601 (`DuplicateKeyException`) marchează duplicatul; fără `IF NOT EXISTS` separat
  (curse de timp).
* Un lot „blocat” (rânduri fără `file_id`, mai vechi de 60 s) se preia cu un `UPDATE ... WHERE file_id IS NULL AND
  received_at < DATEADD(second,-60,SYSUTCDATETIME())` care setează noul `attempt_id`; cererea care vede exact numărul
  de rânduri așteptat devine proprietarul (`NEW`), celelalte primesc `PENDING`.
* Rândurile fără `file_id` mai vechi de 60 s înseamnă „citire neconfirmată” și alimentează
  `filegrid_meter_unconfirmed` (alerta 1).
* Clientul (simulatorul) reîncearcă la `429` / `503` cu **aceeași** cheie, până la 10 minute, apoi abandonează și
  numără (`sim_readings_abandoned_total`).
* Dacă `mssql` cade: `503` cu `Retry-After` pe ingestie; nimic confirmat nu se pierde (volum persistent) și
  contoarele retrimit.

## 2. Upload în bucăți, pe cele 3 storage-uri (`/uploads`)

Pentru fișiere mari (`INSPECTION_VIDEO`, 100 MB – 1 GB+). Bucățile se trimit strict secvențial, de la 1; starea este în
`upload_sessions` / `upload_chunks` (nimic pe discul instanței), deci orice instanță `api` poate continua un upload.

```mermaid
sequenceDiagram
    autonumber
    participant C as Interfața (Angular)
    participant N as nginx
    participant A as api (orice instanță)
    participant M as FileGridMeta
    participant W as ChunkedWriter (S3 / FTP / DB)

    C->>N: POST /uploads {category, fileName, totalSize}
    N->>A: (proxy_request_buffering off)
    A->>M: upload_sessions: storage din politică (după health), chunkSize, next_chunk = 1
    A->>W: open(...)
    A-->>C: 201 {uploadId, chunkSize, storage, nextChunk: 1}

    loop pentru n = 1 .. totalChunks (strict secvențial)
        C->>A: PUT /uploads/{id}/chunks/{n} (corp binar, streaming)
        A->>M: lease per upload (UPDATE atomic cu condiție)
        alt lease obținut și n = nextChunk
            A->>W: append(n, stream, length) fără buffer de dimensiunea bucății
            A->>M: upload_chunks (n, size, sha256, etag), next_chunk++
            A-->>C: 200 {n, sha256, nextChunk}
        else bucată deja primită
            A-->>C: 200 (duplicate: true, confirmare idempotentă)
        else n diferit de nextChunk
            A-->>C: 409 CHUNK_OUT_OF_ORDER
        else lease ocupat de altă cerere
            A-->>C: 409 UPLOAD_BUSY + Retry-After
        else storage-ul sesiunii indisponibil
            A-->>C: 503 + Retry-After
        end
    end

    C->>A: POST /uploads/{id}/complete
    A->>W: finish(): hash final citit din storage în streaming
    A->>M: files (CONFIRMED, sha256), upload_sessions COMPLETED
    A-->>C: 201 FileMetadata (sha256)
```

Cum se scrie fiecare storage (4.1):

| Storage | Cum se adaugă o bucată | La `complete` |
|---|---|---|
| S3 (MinIO) | multipart nativ; `UploadId` și ETag-urile în DB (de aceea `chunkSize` ≥ 5 MiB, validat de PolicyEngine) | `CompleteMultipartUpload` |
| FTP | un fișier per bucată, scris sub nume temporar și redenumit atomic (`RNFR` / `RNTO`) abia când e complet | manifest scris; la citire, adaptorul concatenează bucățile și calculează offset-urile pentru `Range` (`REST`) |
| DB | bucățile se adaugă în `blob_chunks` (`VARBINARY(MAX)`) | – |

Reluare și eșecuri:

* **Reîncercare**: aceeași bucată retrimisă e sigură (confirmare idempotentă); interfața folosește backoff
  exponențial până la 2 minute, iar `uploadId` stă în `sessionStorage`.
* **După refresh de pagină**: `GET /uploads/{id}` întoarce `nextChunk`; browserul nu mai are fișierul, deci interfața
  cere re-selectarea lui și verifică numele și mărimea înainte de reluare.
* **Storage-ul sesiunii cade**: bucățile primesc `503` + `Retry-After`; clientul reia după revenire. Dacă storage-ul
  nu revine în 10 minute sau clientul apelează `POST /uploads/{id}/abort`, sesiunea se abandonează, iar un upload nou
  alege storage-ul de rezervă (după health).
* **Sesiuni inactive peste 24 h**: se curăță automat (abort multipart S3, ștergerea bucăților FTP/DB).
* Detalii de decizie: [ADR-003](../decisions/ADR-003-upload-in-bucati.md).

## 3. Descărcarea cu link semnat

Browserul nu descarcă prin `fetch` în memorie. Rolul se verifică și accesul se auditează **la crearea link-ului**;
descărcarea propriu-zisă este nativă, în streaming și cu reluare (`Range`).

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant N as nginx
    participant A as api
    participant M as FileGridMeta
    participant W as StorageAdapter

    B->>N: POST /files/{id}/download-link (Authorization: Bearer)
    N->>A: proxy
    A->>A: rol permis pentru categorie? (matricea 4.4, altfel 403)
    A->>M: audit_log (acces, utilizator, fișier) și filegrid_doc_access_total la documente
    A-->>B: 200 {url: /api/v1/downloads/{token}, expiresAt (+15 min)}

    B->>N: GET /api/v1/downloads/{token} (fără Authorization)
    Note over N: access_log off, proxy_buffering off, timeout 300 s
    N->>A: proxy
    A->>A: verifică semnătura HMAC, expirarea și legătura cu fișierul / utilizatorul (altfel 401)
    A->>M: metadate: storage, sha256, mărime, encrypted
    alt fișier sub pragul din politică
        A->>W: get(key) în streaming, verificare SHA-256 înainte de trimitere
        Note over A: nepotrivire: 409 FILE_CORRUPT (fără corp parțial)
    else fișier mare
        A->>A: X-Content-SHA256 pleacă înaintea corpului
    end
    A->>W: get(key, range) în streaming (S3 range GET / FTP REST / DB offset în blob_chunks)
    W-->>A: octeți
    A-->>B: 200 sau 206 + Accept-Ranges, Content-Range, X-Content-SHA256
    Note over A,B: la fișier mare, nepotrivirea de hash întrerupe conexiunea și se loghează incidentul
    opt descărcare întreruptă
        B->>A: GET /downloads/{token} cu Range: bytes=N- (același link, în 15 minute)
        A-->>B: 206 de la octetul N
    end
```

Particularități:

* **`CONNECTION_DOC`**: criptat AES-GCM pe segmente de 64 KiB, deci **fără `Range`** (răspuns `200` complet, fără
  `Accept-Ranges`). Se descarcă decriptat; `X-Content-SHA256` este `sha256Plain`. `/verify?deep=true` decriptează și
  verifică și `sha256Plain`. Numele original este criptat, iar `storage_key` este un UUID.
* **Link expirat sau invalid**: `401`. Linkul nu conține date personale, iar nginx nu loghează calea
  `/api/v1/downloads/` (token în cale).
* `/verify` rămâne calea principală pentru integritate: `409` „fișier corupt” (`FILE_CORRUPT`) la nepotrivire și
  `filegrid_integrity_failures_total++`.
* Detalii de decizie: [ADR-004](../decisions/ADR-004-descarcare-prin-link-semnat.md).

Vezi și [arhitectura.md](arhitectura.md) pentru servicii și rețele.
