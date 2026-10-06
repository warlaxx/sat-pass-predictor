package space.nextpass.images;

/** Wikidata or Commons did not give a usable answer; the previous night's images stand. */
public class ImageSourceException extends RuntimeException {

    public ImageSourceException(String message) {
        super(message);
    }

    public ImageSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
