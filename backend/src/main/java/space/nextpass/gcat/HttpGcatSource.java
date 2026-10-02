package space.nextpass.gcat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * GCAT over HTTP, conditionally: the request carries the previous {@code ETag} and
 * {@code Last-Modified}, and a {@code 304} ends the work for that file. planet4589.org is
 * one person's server; asking for 19 MB that have not changed is not a courtesy.
 */
public class HttpGcatSource implements GcatSource {

    private final RestClient restClient;

    /** {@code restClient} carries the base URL, the timeouts and the User-Agent. */
    public HttpGcatSource(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Optional<Validators> fetch(String file, Validators previous, Consumer<BufferedReader> body) {
        try {
            return restClient.get()
                    .uri("/{file}", file)
                    .headers(headers -> {
                        if (previous.etag() != null) {
                            headers.setIfNoneMatch(previous.etag());
                        }
                        if (previous.lastModified() != null) {
                            headers.set(HttpHeaders.IF_MODIFIED_SINCE, previous.lastModified());
                        }
                    })
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_MODIFIED)) {
                            return Optional.<Validators>empty();
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new GcatImportException(
                                    "GCAT answered " + response.getStatusCode() + " for " + file);
                        }
                        try (var reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            body.accept(reader);
                        }
                        HttpHeaders headers = response.getHeaders();
                        return Optional.of(new Validators(
                                headers.getETag(), headers.getFirst(HttpHeaders.LAST_MODIFIED)));
                    });
        } catch (ResourceAccessException e) {
            throw new GcatImportException("GCAT unreachable for " + file, e);
        }
    }
}
