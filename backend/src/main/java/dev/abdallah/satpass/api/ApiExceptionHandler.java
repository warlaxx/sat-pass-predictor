package dev.abdallah.satpass.api;

import dev.abdallah.satpass.tle.TleNotFoundException;
import dev.abdallah.satpass.tle.TleTooOldException;
import dev.abdallah.satpass.tle.TleUnavailableException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduction des pannes du domaine en reponses HTTP, au format Problem Details
 * (RFC 9457).
 *
 * <p>Chaque cas a son {@code type}, une URI stable : c'est ce qui permet a un client de
 * distinguer « ce satellite n'existe pas » de « CelesTrak est en panne » sans lire un
 * message en francais. Le code HTTP seul ne suffirait pas — deux causes tres differentes
 * partagent ici le 503.
 *
 * <p>Les exceptions du cadre (parametre manquant, hors bornes, type invalide) ne sont pas
 * traitees ici : {@code spring.mvc.problemdetails.enabled} les fait deja sortir au meme
 * format. Les reprendre a la main dupliquerait un comportement correct.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String TYPE_PREFIX = "https://github.com/warlaxx/sat-pass-predictor/errors/";

    /** Delai suggere avant nouvelle tentative, en secondes, quand CelesTrak flanche. */
    private static final String RETRY_AFTER_SECONDS = "300";

    @ExceptionHandler(TleNotFoundException.class)
    public ProblemDetail handleNotFound(TleNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setType(URI.create(TYPE_PREFIX + "satellite-inconnu"));
        problem.setTitle("Satellite inconnu");
        problem.setProperty("noradId", e.noradId());
        return problem;
    }

    /**
     * 503 et non 502 : le service ne peut pas repondre <em>pour l'instant</em>, et
     * reessayer a un sens. C'est aussi le seul cas ou le magasin n'avait rien a degrader —
     * un echec de CelesTrak avec un TLE en memoire ne remonte jamais jusqu'ici.
     */
    @ExceptionHandler(TleUnavailableException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> handleUnavailable(
            TleUnavailableException e) {
        log.warn("CelesTrak indisponible et aucun TLE en memoire : {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Aucun TLE disponible pour ce satellite : CelesTrak est injoignable et rien"
                        + " n'a encore ete recupere.");
        problem.setType(URI.create(TYPE_PREFIX + "tle-indisponible"));
        problem.setTitle("Elements orbitaux indisponibles");
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem);
    }

    /**
     * Un TLE existe, mais son epoque est trop ancienne pour que la prediction ait un sens.
     * Repondre 200 avec une courbe au degre pres serait de la fausse precision ; c'est
     * pour cela que ce cas a son propre {@code type} malgre un code identique au
     * precedent.
     */
    @ExceptionHandler(TleTooOldException.class)
    public ProblemDetail handleTooOld(TleTooOldException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setType(URI.create(TYPE_PREFIX + "tle-perime"));
        problem.setTitle("Elements orbitaux trop anciens");
        return problem;
    }

    /**
     * Les invariants du domaine — seuil d'elevation inatteignable, observateur
     * impossible — sont des erreurs de la requete, pas du serveur.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setType(URI.create(TYPE_PREFIX + "requete-invalide"));
        problem.setTitle("Requete invalide");
        return problem;
    }
}
