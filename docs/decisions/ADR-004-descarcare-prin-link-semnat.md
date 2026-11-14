# ADR-004: Descărcarea prin link semnat

* Stare: acceptat (planul v4, secțiunile 4 și 4.1)
* Taskuri: C8, F5
* Contract: [openapi.yaml](../api/openapi.yaml), `POST /files/{id}/download-link`, `GET /downloads/{token}`

## Context

Interfața descarcă fișiere de până la 1 GB+ (video) și documente cu date personale. O descărcare prin `fetch` ar încărca
fișierul în memoria browserului și nu s-ar putea relua. Trebuie să păstrăm: autentificare și roluri verificate,
acces auditat la documente, streaming, reluare cu `Range` și un URL care nu conține date personale.

## Decizie

Descărcarea are doi pași:

1. `POST /files/{id}/download-link` (cu `Authorization: Bearer`) → `{url, expiresAt}`. **Rolul se verifică și accesul se
   auditează la crearea link-ului** (la documente, inclusiv refuzurile, cu `filegrid_doc_access_total`).
2. Browserul deschide `url` (`/api/v1/downloads/{token}`, **fără** `Authorization`) și descarcă nativ: în streaming și
   cu reluare (`Range` / `206`), fără să încarce fișierul în memorie.

Token-ul:

* semnat cu HMAC, valabil **15 minute** (suficient ca o descărcare să se poată relua după o întrerupere);
* legat de fișier și de utilizator; fără date personale în URL;
* expirat, invalid sau modificat: `401`.

nginx: `access_log off` pe `/api/v1/downloads/` (token în cale), `proxy_buffering off` pe descărcare, timeout-uri de 300 s.

Endpoint-ul `GET /files/{id}/download` (cu `Authorization`) rămâne disponibil pentru clienți care pot trimite antetul
(scripturi, teste), cu aceleași header-e: `Accept-Ranges: bytes`, `Range` → `206`, `X-Content-SHA256`.

### Integritate

* Fișierele sub pragul din politică se verifică înainte de trimitere (`409` `FILE_CORRUPT`, răspuns corect).
* La fișierele mari, `X-Content-SHA256` pleacă înaintea corpului; la nepotrivire conexiunea se întrerupe și incidentul se
  loghează (`filegrid_integrity_failures_total`).
* `/verify` rămâne calea principală; la `CONNECTION_DOC`, `/verify?deep=true` decriptează și verifică și `sha256Plain`.
* `CONNECTION_DOC` este criptat în segmente (AES-GCM, 64 KiB), deci **fără `Range`**.

## Alternative respinse

* **`fetch` cu `Authorization` și blob în browser**: încarcă tot fișierul în memorie și nu se reia; incompatibil cu
  video de 1,1 GB.
* **Antet `Authorization` pe navigarea nativă a browserului**: un browser nu adaugă antete la o descărcare nativă
  (link sau redirect), de aceea autorizarea trebuie să stea în URL.
* **Descărcare directă din storage**: storage-urile nu sunt accesibile din exterior; singura cale este FileGrid.

## Consecințe

* Oricine deține URL-ul îl poate folosi până la expirare (15 minute); riscul e limitat prin durata scurtă, lipsa datelor
  personale în URL și lipsa lui din `access_log`. Revocarea unui link nu este suportată (la fel ca la
  `refreshToken`).
* Auditul de acces la documente se face la crearea link-ului, nu la fiecare cerere `Range` de reluare.
* `F5` (interfața) apelează `download-link` și deschide URL-ul primit; până la ora ~8, Flavius poate folosi un link direct
  de test.
