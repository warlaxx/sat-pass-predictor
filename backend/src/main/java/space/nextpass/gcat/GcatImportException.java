package space.nextpass.gcat;

/** The GCAT import could not complete: unreachable source, refused answer, unreadable file. */
public class GcatImportException extends RuntimeException {
    public GcatImportException(String message) {
        super(message);
    }

    public GcatImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
