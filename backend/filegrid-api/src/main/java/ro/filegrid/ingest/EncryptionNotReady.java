package ro.filegrid.ingest;

import java.io.InputStream;

import org.springframework.stereotype.Component;

/** Implementarea până la C10: criptarea lipsește, deci {@link #ready()} e {@code false}. Se șterge la C10. */
@Component
class EncryptionNotReady implements DocumentEncryption {

    @Override
    public boolean ready() {
        return false;
    }

    @Override
    public long encryptedLength(long plainLength) {
        throw new IllegalStateException("Criptarea documentelor nu este încă implementată (C10)");
    }

    @Override
    public InputStream encrypt(InputStream plain) {
        throw new IllegalStateException("Criptarea documentelor nu este încă implementată (C10)");
    }
}
