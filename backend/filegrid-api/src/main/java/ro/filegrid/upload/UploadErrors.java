package ro.filegrid.upload;

import java.util.Map;
import org.springframework.http.HttpStatus;
import ro.filegrid.api.ApiException;

/** {@code /uploads} errors in the contract's {@code Error} format (stable codes). */
final class UploadErrors {

    static final int BUSY_RETRY_AFTER_SECONDS = 1;

    private UploadErrors() {
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Sesiunea de upload nu există.");
    }

    static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Doar utilizatorul care a inițiat upload-ul (sau ADMIN) îl poate folosi.");
    }

    static ApiException busy() {
        return new ApiException(HttpStatus.CONFLICT, "UPLOAD_BUSY",
                "Altă cerere scrie în acest moment în aceeași sesiune; reîncearcă.", BUSY_RETRY_AFTER_SECONDS, null);
    }

    static ApiException notActive(String status) {
        return new ApiException(HttpStatus.CONFLICT, "UPLOAD_NOT_ACTIVE",
                "Sesiunea de upload nu mai este activă (" + status + ").", null, Map.of("status", status));
    }

    static ApiException outOfOrder(int expected, int received) {
        return new ApiException(HttpStatus.CONFLICT, "CHUNK_OUT_OF_ORDER",
                "Se aștepta bucata " + expected + ", s-a primit " + received + ".", null,
                Map.of("expectedChunk", expected, "receivedChunk", received));
    }

    static ApiException incomplete(int nextChunk, int totalChunks) {
        return new ApiException(HttpStatus.CONFLICT, "UPLOAD_INCOMPLETE",
                "Lipsesc bucăți; următoarea așteptată este " + nextChunk + " din " + totalChunks + ".", null,
                Map.of("nextChunk", nextChunk, "totalChunks", totalChunks));
    }
}
