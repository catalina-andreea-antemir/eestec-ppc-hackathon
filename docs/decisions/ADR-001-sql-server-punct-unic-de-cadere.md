# ADR-001: SQL Server rămâne un singur container (punct unic de cădere cunoscut)

* Stare: acceptat (planul v4, secțiunea 4.1)
* Taskuri: C2, C3, C11, O2, F9, F11

## Context

FileGrid folosește Microsoft SQL Server 2022 pentru două lucruri: metadatele, auditul și citirile (baza `FileGridMeta`) și
conținutul fișierelor pentru storage-ul DB (baza `FileGridStorage`). Fiecare instanță SQL Server cere ~2 GB de RAM, iar
Docker are 8 GB în total (buget țintă: `mssql` 2,5 GB, `api` 3 × 512 MB, plus restul serviciilor, în total ~7,1 GB).

## Decizie

Rămâne **un singur container `mssql`**, cu cele două baze. `mssql-init` îl pregătește o singură dată (bazele, cei doi
utilizatori cu drepturi minime, `blob_chunks`). Decizia și limita ei se recunosc deschis, inclusiv la demo.

Scindarea e pregătită din configurație, fără modificări de cod: variabila `STORAGE_DB_HOST` (implicit `mssql`) și
serviciul opțional `mssql-storage` din profilul `split`, care găzduiește doar `FileGridStorage`. La ora 8 se măsoară
memoria cu `docker stats` și se decide dacă se activează.

## Ce se întâmplă când cade

| Situația | Comportament |
|---|---|
| Cade tot containerul `mssql` | ingestia primește `503` cu `Retry-After`; `GET /health` răspunde `DOWN`; nimic confirmat nu se pierde (date pe volumul persistent `mssql`); contoarele retrimit cu aceeași `Idempotency-Key`. Fișierele de pe FTP și S3 rămân intacte, dar căutarea și descărcarea citesc metadate din `FileGridMeta`, deci revin odată cu baza. |
| Cade doar storage-ul DB (se simulează cu `ALTER DATABASE FileGridStorage SET OFFLINE WITH ROLLBACK IMMEDIATE`, apoi `SET ONLINE`) | metadatele rămân disponibile; categoriile cu DB principal (METER_READING, CONNECTION_DOC) trec pe rezerva S3, fără pierderi; `filegrid_storage_up{storage="DB"}` devine 0 |
| Cade FTP | VIDEO trece pe S3, celelalte categorii nu sunt afectate |

Scenariul `OFFLINE` demonstrează **căderea storage-ului DB, nu a serviciului SQL Server**. Pentru demo, în scenariul de
cădere a unui storage se oprește FTP sau se pune `FileGridStorage` offline; containerul `mssql` nu se oprește (altfel cad
și metadatele).

## Răspuns scurt pentru juriu: „ce se întâmplă dacă cade tot SQL Server?”

> Este o limită pe care o recunoaștem: avem un singur container SQL Server, ales pentru că fiecare instanță cere
> ~2 GB, iar Docker are 8 GB în total. Dacă cade, ingestia răspunde 503 cu Retry-After, health arată DOWN și nimic
> confirmat nu se pierde, pentru că datele sunt pe un volum persistent; contoarele retrimit cu aceeași cheie, deci
> nu apar duplicate. Fișierele de pe FTP și S3 rămân intacte. Ce demonstrăm live este căderea storage-ului DB
> (`FileGridStorage` offline), cu metadatele în picioare. Scindarea în două instanțe e deja pregătită din configurație
> (`STORAGE_DB_HOST`, profilul `split`); ce rămâne pentru producție este redundanța instanței (de exemplu
> replicare sau cluster), care depășește bugetul de memorie al acestui mediu.

## Alternative respinse

* **Două containere SQL Server implicit**: ~4 GB pentru baze, nu încape în bugetul de 8 GB împreună cu cele 3 instanțe
  `api` și stack-ul de observabilitate. Rămâne opțiune prin profilul `split`, dacă măsurătorile de la ora 8 o permit.
* **Tăierea numărului de instanțe `api`** pentru a face loc: respinsă, scalarea cu 3 instanțe e obligatorie. Dacă nu
  încape, se reduc retenția Prometheus/Loki și limita MinIO.

## Consecințe

* `F9` (haos) și `F11` (demo) folosesc `kill-ftp` și `kill-db-storage`, nu oprirea lui `mssql`.
* Pool-uri JDBC separate pentru `FileGridMeta` și `FileGridStorage`, ca scindarea să nu necesite modificări de cod.
* Fără PostgreSQL nicăieri.
