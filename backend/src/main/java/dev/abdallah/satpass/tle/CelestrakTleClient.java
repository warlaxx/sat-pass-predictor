package dev.abdallah.satpass.tle;

import dev.abdallah.satpass.domain.TleSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.orekit.data.DataContext;
import org.orekit.errors.OrekitException;
import org.orekit.propagation.analytical.tle.TLE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Recupere un TLE aupres de l'API GP de CelesTrak.
 *
 * <h2>Ce que renvoie reellement CelesTrak</h2>
 * {@code gp.php?CATNR=25544&FORMAT=TLE} renvoie trois lignes en {@code text/plain} : le
 * nom du satellite, puis les lignes 1 et 2. Deux pieges :
 * <ul>
 *   <li>Un numero NORAD inconnu ne donne pas un 404 mais un <strong>200 avec le corps
 *       {@code No GP data found}</strong>. Se fier au code HTTP seul ferait passer cette
 *       chaine pour un TLE.</li>
 *   <li>En cas d'abus, CelesTrak repond une page HTML, toujours en 200. Tout corps qui
 *       ne ressemble pas a un TLE est donc traite comme une panne, pas comme une
 *       reponse.</li>
 * </ul>
 *
 * <h2>Validation a la recuperation</h2>
 * Une reponse corrompue doit echouer a l'entree du systeme, la ou l'on sait encore
 * pourquoi, plutot qu'au fond d'un calcul de passages. Trois controles, dans cet ordre :
 * <ol>
 *   <li>Largeur de 69 caracteres et numero de ligne en tete — le format TLE est a
 *       colonnes fixes, une ligne d'une autre longueur n'est pas un TLE.</li>
 *   <li>Somme de controle de fin de ligne. Orekit expose {@code TLE.isFormatOK} mais ne
 *       la verifie <em>pas</em> a la construction : un chiffre altere en transit produit
 *       un TLE parfaitement accepte, et des passages faux. On la recalcule donc ici.</li>
 *   <li>Parsing par Orekit, qui valide les champs eux-memes, puis comparaison du numero
 *       NORAD renvoye avec celui demande.</li>
 * </ol>
 */
@Component
public class CelestrakTleClient {

    private static final Logger log = LoggerFactory.getLogger(CelestrakTleClient.class);

    /** Ce que CelesTrak repond, en 200, pour un numero NORAD absent du catalogue. */
    private static final String NO_DATA_MARKER = "No GP data found";

    private static final String SOURCE = "celestrak";

    /** Largeur d'une ligne de TLE, fixee par le format a colonnes de la NORAD. */
    private static final int TLE_LINE_LENGTH = 69;

    private final RestClient restClient;
    private final DataContext dataContext;
    private final Clock clock;

    public CelestrakTleClient(RestClient celestrakRestClient, DataContext dataContext, Clock clock) {
        this.restClient = celestrakRestClient;
        this.dataContext = dataContext;
        this.clock = clock;
    }

    /**
     * @throws TleNotFoundException     si le catalogue ne contient pas ce numero.
     * @throws TleUnavailableException si CelesTrak est injoignable ou repond autre chose
     *                                 qu'un TLE exploitable.
     */
    public TleSnapshot fetch(int noradId) {
        String body = get(noradId);
        Instant fetchedAt = clock.instant();

        String trimmed = body == null ? "" : body.strip();
        if (trimmed.isEmpty() || trimmed.startsWith(NO_DATA_MARKER)) {
            throw new TleNotFoundException(noradId);
        }

        List<String> lines = significantLines(body);
        if (lines.size() < 3) {
            throw new TleUnavailableException(
                    "reponse de CelesTrak inexploitable pour le satellite " + noradId
                            + " : " + lines.size() + " ligne(s) utiles, 3 attendues");
        }

        String name = lines.get(0).strip();
        String line1 = lines.get(1);
        String line2 = lines.get(2);
        requireWellFormed(noradId, line1, 1);
        requireWellFormed(noradId, line2, 2);

        TLE parsed = parse(noradId, line1, line2);
        if (parsed.getSatelliteNumber() != noradId) {
            throw new TleUnavailableException(
                    "CelesTrak a renvoye le satellite " + parsed.getSatelliteNumber()
                            + " alors que " + noradId + " etait demande");
        }

        TleSnapshot snapshot = new TleSnapshot(
                noradId,
                name,
                line1,
                line2,
                parsed.getDate().toInstant(dataContext.getTimeScales()),
                fetchedAt,
                SOURCE);
        log.info("TLE recupere pour {} ({}), epoque {}", noradId, name, snapshot.epoch());
        return snapshot;
    }

    private String get(int noradId) {
        try {
            return restClient.get()
                    .uri(uri -> uri.path("/NORAD/elements/gp.php")
                            .queryParam("CATNR", noradId)
                            .queryParam("FORMAT", "TLE")
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                            throw new TleNotFoundException(noradId);
                        }
                        throw new TleUnavailableException(
                                "CelesTrak a repondu " + response.getStatusCode()
                                        + " pour le satellite " + noradId);
                    })
                    .body(String.class);
        } catch (ResourceAccessException e) {
            // Timeout, DNS, connexion refusee : la seule information utile est qu'on n'a
            // pas pu demander. On la remonte telle quelle pour que le magasin degrade.
            throw new TleUnavailableException(
                    "CelesTrak injoignable pour le satellite " + noradId, e);
        }
    }

    /**
     * Largeur, numero de ligne et somme de controle.
     *
     * <p>La somme de controle du format TLE est la somme des chiffres des 68 premieres
     * colonnes, les signes moins comptant pour 1 et tout le reste pour 0, modulo 10. Elle
     * occupe la 69e colonne. C'est une protection contre l'alteration en transit, et elle
     * ne sert a rien si personne ne la verifie — ce qu'Orekit ne fait pas a la
     * construction.
     */
    private static void requireWellFormed(int noradId, String line, int lineNumber) {
        if (line.length() != TLE_LINE_LENGTH) {
            throw new TleUnavailableException(
                    "ligne " + lineNumber + " du satellite " + noradId + " : "
                            + TLE_LINE_LENGTH + " caracteres attendus, recu " + line.length());
        }
        if (line.charAt(0) != (char) ('0' + lineNumber)) {
            throw new TleUnavailableException(
                    "ligne " + lineNumber + " du satellite " + noradId
                            + " : ne commence pas par '" + lineNumber + "'");
        }
        int sum = 0;
        for (int i = 0; i < TLE_LINE_LENGTH - 1; i++) {
            char c = line.charAt(i);
            if (c >= '0' && c <= '9') {
                sum += c - '0';
            } else if (c == '-') {
                sum += 1;
            }
        }
        int expected = sum % 10;
        int actual = line.charAt(TLE_LINE_LENGTH - 1) - '0';
        if (actual != expected) {
            throw new TleUnavailableException(
                    "somme de controle invalide sur la ligne " + lineNumber + " du satellite "
                            + noradId + " : " + expected + " attendue, " + actual + " lue");
        }
    }

    /**
     * Le TLE d'Orekit valide les champs eux-memes. Sa {@link OrekitException}
     * est traduite en panne : une ligne corrompue en transit n'est pas distinguable, de
     * l'exterieur, d'un service qui renvoie n'importe quoi.
     */
    private TLE parse(int noradId, String line1, String line2) {
        try {
            return new TLE(line1, line2);
        } catch (OrekitException | IllegalArgumentException e) {
            throw new TleUnavailableException(
                    "TLE illisible pour le satellite " + noradId + " : " + e.getMessage(), e);
        }
    }

    /**
     * Decoupe la reponse en lignes non vides. CelesTrak complete le nom par des espaces
     * jusqu'a 24 caracteres et termine par CRLF ; seules les lignes 1 et 2 gardent leur
     * largeur exacte de 69 caracteres, dont depend le format a colonnes.
     */
    private static List<String> significantLines(String body) {
        List<String> lines = new ArrayList<>(3);
        for (String raw : body.split("\\R")) {
            String line = raw.stripTrailing();
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }
}
