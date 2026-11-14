package ro.filegrid.policy;

/** Fișierele de politică lipsesc sau sunt invalide; mesajul spune fișierul și câmpul. */
public class PolicyException extends RuntimeException {

    public PolicyException(String message) {
        super(message);
    }

    public PolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
