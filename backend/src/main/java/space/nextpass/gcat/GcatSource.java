package space.nextpass.gcat;

import java.io.BufferedReader;
import java.util.Optional;
import java.util.function.Consumer;

/** Where GCAT files come from: planet4589.org in production, a fixture in tests. */
public interface GcatSource {

    /** The HTTP validators of a downloaded file, either of which may be absent. */
    record Validators(String etag, String lastModified) {
        public static final Validators NONE = new Validators(null, null);
    }

    /**
     * Downloads {@code file} unless it has not changed since {@code previous}. When it has,
     * hands its body to {@code body} — streamed, never held whole — and returns the new
     * validators; when it has not, returns empty without calling {@code body}.
     *
     * @throws GcatImportException if the source cannot be reached or refuses the request
     */
    Optional<Validators> fetch(String file, Validators previous, Consumer<BufferedReader> body);
}
