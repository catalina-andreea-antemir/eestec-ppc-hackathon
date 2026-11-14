package ro.filegrid.storage;

import java.io.InputStream;

import ro.filegrid.domain.StorageType;

/**
 * Contractul comun al celor 3 storage-uri (planul v4 4.1, C4; implementări: {@code storage/s3/S3Adapter},
 * {@code storage/ftp/FtpAdapter}, {@code storage/db/DbStorageAdapter}). Fiecare implementare este un bean Spring și
 * implementează de obicei și {@link ChunkedWriter}; {@link StorageAdapterRegistry} le găsește după {@link #type()}.
 *
 * <h2>Reguli comune pentru toate metodele</h2>
 * <ul>
 *   <li><b>Streaming</b>: niciun fișier și nicio bucată nu se ține integral în memorie. Se folosește un buffer fix
 *       (de exemplu 64 KiB) sau API-ul de streaming al storage-ului ({@code setBinaryStream} / {@code getBinaryStream},
 *       {@code RequestBody.fromInputStream}, {@code storeFile}). Testul de acceptanță: 1,1 GB cu {@code -Xmx256m}.</li>
 *   <li><b>Cheia</b> ({@code key}) o generează apelantul (un UUID, vezi {@link StorageKeys}); adaptorul nu o
 *       schimbă și o poate transforma intern în cale / nume de obiect. Ce scrie adaptorul cu o cheie se citește
 *       înapoi doar cu aceeași cheie.</li>
 *   <li><b>Hash-ul</b> SHA-256 și metadatele (tabela {@code files}) sunt treaba apelantului (fluxul de ingestie);
 *       adaptorul doar mută octeți.</li>
 *   <li><b>Erori</b>: storage-ul indisponibil (conexiune refuzată, timeout, bază OFFLINE) →
 *       {@link StorageUnavailableException}; cheie inexistentă la citire → {@link StorageObjectNotFoundException};
 *       orice altă eroare → {@link StorageException}. Excepțiile JDBC / FTP / AWS se împachetează, nu ies direct.
 *       Mesajele nu conțin parole, chei sau date personale.</li>
 *   <li><b>Fire de execuție</b>: o instanță este folosită concurent de mai multe cereri, deci nu ține stare per
 *       cerere în câmpuri. Conexiunile vin dintr-un pool (JDBC, S3) sau se deschid per operație (FTP).</li>
 *   <li><b>Reziliența</b> (retry, circuit breaker, fallback pe storage-ul de rezervă) se pune deasupra adaptorului
 *       (C11); adaptorul nu reîncearcă singur la nesfârșit și respectă timeout-uri scurte.</li>
 * </ul>
 */
public interface StorageAdapter {

    /** Storage-ul implementat; unic între beanurile {@code StorageAdapter}. */
    StorageType type();

    /**
     * Scrie un obiect întreg, citind din {@code data} exact {@code length} octeți, în streaming.
     *
     * <ul>
     *   <li>Apelantul deține {@code data} și îl închide; adaptorul nu citește mai mult de {@code length} octeți.</li>
     *   <li>Dacă sursa se termină înainte de {@code length} octeți, scrierea eșuează cu {@link StorageException}.</li>
     *   <li>Dacă {@code key} există deja, conținutul se înlocuiește complet (o reîncercare cu aceeași cheie e sigură).
     *       </li>
     *   <li>Atomic din punctul de vedere al cititorului: la eșec, {@link #exists(String)} întoarce {@code false}
     *       pentru o cheie nouă (DB: tranzacție; FTP: nume temporar + {@code RNFR}/{@code RNTO}; S3: nativ).</li>
     * </ul>
     *
     * @param key         cheia obiectului, validă după {@link StorageKeys#requireValid(String)}
     * @param data        sursa, citită o singură dată, secvențial
     * @param length      numărul exact de octeți, cel puțin 1 (fișierele goale le respinge apelantul cu 400:
     *                    {@code files.size_bytes > 0}, iar {@code blob_chunks} nu are rânduri goale)
     * @param contentType tipul MIME (folosit de S3; FTP și DB îl pot ignora, el se păstrează oricum în {@code files})
     * @return numărul de octeți scriși (egal cu {@code length})
     * @throws StorageUnavailableException storage-ul nu răspunde
     * @throws StorageException            orice altă eroare, inclusiv sursă prea scurtă
     */
    long put(String key, InputStream data, long length, String contentType);

    /**
     * Deschide obiectul pentru citire în streaming, întreg sau doar un interval (pentru {@code Range} / {@code 206}).
     *
     * <ul>
     *   <li>Stream-ul întors ține resursele deschise (conexiunea JDBC, sesiunea FTP, răspunsul S3) până la
     *       {@code close()}; apelantul îl închide mereu (try-with-resources), inclusiv când clientul HTTP renunță.</li>
     *   <li>Se întorc octeții {@code [range.offset(), range.offset() + range.length())}, oprindu-se la sfârșitul
     *       obiectului dacă intervalul îl depășește; un {@code offset} mai mare sau egal cu mărimea dă un stream gol.
     *       Validarea {@code Range} față de mărime (416) o face apelantul, cu {@code files.size_bytes}.</li>
     *   <li>Implementarea sare direct la offset (S3 range GET, FTP {@code REST}, DB: bucățile din
     *       {@code blob_chunks} cu {@code chunk_offset} potrivit), nu citește și aruncă octeții de dinainte.</li>
     *   <li>Cheia inexistentă se semnalează la deschidere, nu la prima citire. Erorile din timpul citirii ies ca
     *       {@link java.io.IOException} din stream.</li>
     * </ul>
     *
     * @param key   cheia obiectului
     * @param range intervalul cerut; {@link ByteRange#full()} pentru tot obiectul
     * @throws StorageObjectNotFoundException cheia nu există
     * @throws StorageUnavailableException    storage-ul nu răspunde
     */
    InputStream get(String key, ByteRange range);

    /**
     * Șterge obiectul (și orice bucăți rămase de la un upload în bucăți cu aceeași cheie). Idempotent: o cheie
     * inexistentă nu este eroare. Se folosește la compensare (scriere reușită, dar metadate eșuate) și la curățare.
     *
     * @return {@code true} dacă s-a șters ceva, {@code false} dacă obiectul nu exista
     * @throws StorageUnavailableException storage-ul nu răspunde
     */
    boolean delete(String key);

    /**
     * Spune dacă există un obiect complet cu această cheie (scris cu {@link #put} sau încheiat cu
     * {@link ChunkedWriter#finish}). Bucățile unui upload neterminat nu contează.
     *
     * @throws StorageUnavailableException storage-ul nu răspunde
     */
    boolean exists(String key);

    /**
     * Verificare rapidă (o operație minimă: {@code SELECT 1}, {@code NOOP}, {@code HeadBucket}), cu timeout de cel mult
     * 2 secunde. <b>Nu aruncă excepții</b>: orice eroare devine {@link StorageHealth#down}. Se apelează periodic, pentru
     * {@code filegrid_storage_up}, {@code /admin/health} și alegerea storage-ului de rezervă (C11).
     */
    StorageHealth health();
}
