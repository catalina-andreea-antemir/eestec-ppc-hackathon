# ADR-003: Protocolul de upload în bucăți

* Stare: acceptat (planul v4, secțiunile 4 și 4.1)
* Taskuri: C3, C4, C5, C6, C7, O3, C14, F4
* Contract: [openapi.yaml](../api/openapi.yaml), `/uploads`

## Context

Video-urile de dronă au 100 MB – 1 GB+. Cerințe: fișierele de peste 1 GB se procesează fără ca resursele serviciului să
crească proporțional cu dimensiunea (testat cu `-Xmx256m`), transferul rezistă pe conexiuni lente sau instabile, se
reia fără intervenție manuală (inclusiv după refresh de pagină) și funcționează cu mai multe instanțe `api`, pe
toate cele 3 storage-uri.

## Decizie

### Protocol

1. `POST /uploads` `{category, fileName, totalSize}` → `{uploadId, chunkSize, ...}`; `chunkSize` vine din politică:
   minim **5 MiB** (limita MinIO), maxim 64 MiB (implicit 16 MiB la video). Ultima bucată poate fi mai mică.
2. `PUT /uploads/{id}/chunks/{n}` (corp binar). `n` pornește de la **1** și se trimite **strict secvențial**: `n` =
   următorul așteptat, altfel `409` `CHUNK_OUT_OF_ORDER`.
3. `GET /uploads/{id}` întoarce starea (`nextChunk`, `receivedBytes`), pentru reluare, inclusiv după refresh.
4. `POST /uploads/{id}/complete` calculează hash-ul final citind din storage în streaming și confirmă fișierul.
5. `POST /uploads/{id}/abort` abandonează sesiunea.

### Idempotență și sincronizare

* Retrimiterea aceleiași bucăți este **sigură**: o bucată deja primită se confirmă idempotent (`200`, `duplicate: true`).
* O bucată se scrie doar sub un **lease per upload** (`lease_owner`, `lease_until` în `upload_sessions`), obținut cu un
  `UPDATE` atomic cu condiție; altfel `409` `UPLOAD_BUSY` cu `Retry-After`. Asta exclude scrierea simultană a
  aceleiași bucăți de către două instanțe.
* Starea este în `upload_sessions` / `upload_chunks` (nimic pe discul instanței), deci bucățile pot ajunge la instanțe
  diferite. Hash SHA-256 per bucată la primire, hash final la `complete`.

### Interfața comună `ChunkedWriter`

`open`, `append(n, stream, length)`, `finish`, `abort`. Fiecare storage o implementează:

| Storage | Implementare |
|---|---|
| S3 | multipart nativ; `UploadId` și ETag-urile în DB (`upload_chunks.etag`), de aceea `chunkSize` ≥ 5 MiB e validat de PolicyEngine |
| FTP | câte un fișier per bucată, scris sub nume temporar și redenumit atomic (`RNFR` / `RNTO`) abia când e complet, plus un manifest scris la `complete`; o bucată parțială rămâne doar sub nume temporar și se rescrie la retrimitere; la citire, adaptorul concatenează bucățile în ordine și calculează offset-urile pentru `Range` (`REST`) |
| DB | bucățile se adaugă în `blob_chunks` (`VARBINARY(MAX)`, bucăți de ~4 MB) |

### Cădere de storage în timpul upload-ului

Sesiunea rămâne legată de storage-ul ales la `init`. Dacă acel storage cade: `503` + `Retry-After` pe bucăți, iar
clientul reia după revenire. Dacă nu revine în **10 minute**, sau clientul apelează `abort`, sesiunea se abandonează, iar
un upload nou alege storage-ul de rezervă (după health). Sesiunile inactive peste **24 h** se curăță automat (abort
multipart S3, ștergerea bucăților FTP/DB).

### Client și proxy

* Interfața reîncearcă cu backoff exponențial până la un timeout de 2 minute și păstrează `uploadId` în
  `sessionStorage`. După refresh browserul nu mai are acces la fișier: cere re-selectarea lui și verifică numele și mărimea.
* La `401` interfața reînnoiește token-ul (`/auth/refresh`) și repetă cererea, ca un upload de 1,1 GB să nu pice la
  expirarea token-ului (15 minute).
* nginx: `client_max_body_size 100m` (o bucată de până la 64 MiB încape), `proxy_request_buffering off` pe
  `/api/v1/uploads` și `/api/v1/files`, timeout-uri de 300 s.

## Alternative respinse

* **Upload dintr-o singură cerere**: nu se poate relua și ar pune presiune pe memorie și timeout.
* **Bucăți paralele / în orice ordine**: complică FTP (concatenare, offset-uri) și lease-ul; secvențial e suficient
  pentru cerință și mai ușor de demonstrat.
* **Bucăți pe discul instanței**: nu merge cu mai multe instanțe și rupe cerința „nimic din upload rămâne pe disc”.

## Consecințe

* Orice instanță poate continua un upload; se testează 1,1 GB cu 3 instanțe, cu bucăți care ajung la instanțe diferite.
* Memoria instanței nu depinde de mărimea fișierului: bucata se scrie în streaming, fără buffer de dimensiunea ei.
* `filegrid_transfers_failed_total{category,storage}` numără transferurile eșuate (alerta 3, video).
