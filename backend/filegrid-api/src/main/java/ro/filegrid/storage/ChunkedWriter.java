package ro.filegrid.storage;

import java.io.InputStream;
import java.util.List;

import ro.filegrid.domain.StorageType;

/**
 * Upload în bucăți pe un storage (planul v4 4.1, ADR-003; S3: multipart nativ, FTP: un fișier per bucată + manifest,
 * DB: bucăți adăugate în {@code blob_chunks}). Obiectul rezultat se citește apoi cu
 * {@link StorageAdapter#get(String, ByteRange)} pe aceeași cheie.
 *
 * <h2>Cine ține starea</h2>
 * Starea sesiunii este în FileGridMeta ({@code upload_sessions}, {@code upload_chunks}) și o gestionează apelantul
 * (fluxul {@code /uploads}, C7): {@code next_chunk}, lease-ul, hash-ul per bucată, ETag-urile. Writer-ul <b>nu</b>
 * ține stare în memorie între apeluri: orice instanță {@code api} poate continua un upload început de alta, folosind
 * doar {@link ChunkedUpload} (reconstruit din rândul {@code upload_sessions}). Nimic nu se scrie pe discul instanței.
 *
 * <h2>Ce garantează apelantul</h2>
 * <ul>
 *   <li>Apelurile pentru același upload sunt <b>serializate</b> (lease per upload): niciodată două {@code append}
 *       simultane pe aceeași sesiune.</li>
 *   <li>Bucățile vin <b>în ordine</b>, de la 1; singura repetare posibilă este ultima bucată trimisă (retrimitere după o
 *       eroare între scrierea în storage și confirmarea în DB, sau după o bucată întreruptă la jumătate).</li>
 *   <li>{@code length} este exact {@link ChunkedUpload#expectedLength(int)}: {@code chunkSize}, iar ultima bucată poate
 *       fi mai mică. {@code chunkSize} este între 5 MiB și 64 MiB (validat de PolicyEngine).</li>
 * </ul>
 *
 * <p>Erorile urmează regulile din {@link StorageAdapter} ({@link StorageUnavailableException} → 503 cu
 * {@code Retry-After} și reluare; restul {@link StorageException}).
 */
public interface ChunkedWriter {

    /** Storage-ul implementat; unic între beanurile {@code ChunkedWriter}. */
    StorageType type();

    /**
     * Începe un upload. Nu scrie încă date (sau doar ce cere storage-ul: S3 {@code CreateMultipartUpload}).
     *
     * @param key       cheia obiectului final (UUID generat de apelant)
     * @param totalSize mărimea totală anunțată la {@code POST /uploads}
     * @param chunkSize mărimea nominală a bucății, din politică
     * @return referința de upload a storage-ului, salvată de apelant în {@code upload_sessions.storage_upload_ref}
     *         (maxim 1024 de caractere): S3 = {@code UploadId}; FTP = directorul / prefixul bucăților; DB = de obicei
     *         chiar cheia. Fără parole sau date personale.
     */
    String open(String key, long totalSize, int chunkSize);

    /**
     * Scrie bucata {@code n} în streaming, fără buffer de dimensiunea bucății (o bucată are până la 64 MiB). Dacă
     * bucata {@code n} a mai fost scrisă (complet sau parțial), conținutul ei se <b>înlocuiește</b>: retrimiterea este
     * sigură. La eșec, bucata {@code n} poate rămâne parțială; următorul {@code append(n)} o rescrie.
     *
     * <ul>
     *   <li>S3: {@code UploadPart} cu {@code partNumber = n}; ETag-ul întors merge în {@link ChunkReceipt#etag()}.</li>
     *   <li>FTP: fișier temporar per bucată, redenumit atomic ({@code RNFR}/{@code RNTO}) doar când e complet.</li>
     *   <li>DB: rândurile {@code blob_chunks} ale bucății, cu {@code chunk_offset} începând de la
     *       {@link ChunkedUpload#offsetOf(int)}; la retrimitere se șterg întâi rândurile din intervalul bucății, în
     *       aceeași tranzacție.</li>
     * </ul>
     *
     * @param upload sesiunea (cheie, referință, mărimi)
     * @param n      numărul bucății, de la 1 la {@link ChunkedUpload#totalChunks()}
     * @param data   corpul cererii; apelantul îl deține și îl închide
     * @param length numărul exact de octeți de citit din {@code data}
     * @return confirmarea bucății (mărime, ETag la S3), salvată de apelant în {@code upload_chunks}
     */
    ChunkReceipt append(ChunkedUpload upload, int n, InputStream data, long length);

    /**
     * Încheie upload-ul: după succes, obiectul cu cheia {@link ChunkedUpload#key()} este complet, iar
     * {@link StorageAdapter#exists(String)} și {@link StorageAdapter#get(String, ByteRange)} îl văd. Hash-ul final îl
     * calculează apelantul citind obiectul în streaming.
     *
     * <p><b>Idempotent</b>: un al doilea {@code finish} după unul reușit (de exemplu, DB-ul a căzut între finish și
     * confirmarea sesiunii) nu strică obiectul și nu aruncă excepție.
     *
     * @param upload sesiunea
     * @param chunks confirmările tuturor bucăților, în ordine, de la 1 la {@code totalChunks} (din {@code upload_chunks})
     * @return mărimea obiectului final, în octeți (egală cu {@link ChunkedUpload#totalSize()})
     */
    long finish(ChunkedUpload upload, List<ChunkReceipt> chunks);

    /**
     * Abandonează upload-ul și șterge tot ce s-a scris (S3 {@code AbortMultipartUpload}, ștergerea bucăților FTP / a
     * rândurilor {@code blob_chunks}). Idempotent; se folosește la {@code POST /uploads/{id}/abort}, la abandonul după
     * 10 minute de storage căzut și la curățarea sesiunilor inactive peste 24 h.
     */
    void abort(ChunkedUpload upload);
}
