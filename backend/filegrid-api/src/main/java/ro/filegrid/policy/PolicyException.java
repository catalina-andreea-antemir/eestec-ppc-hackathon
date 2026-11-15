package ro.filegrid.policy;

/** Policy files are missing or invalid; the message names the file and the field. */
public class PolicyException extends RuntimeException {

    public PolicyException(String message) {
        super(message);
    }

    public PolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
