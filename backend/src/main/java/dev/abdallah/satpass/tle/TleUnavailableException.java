package dev.abdallah.satpass.tle;

/**
 * CelesTrak n'a pas pu etre interroge, ou a repondu quelque chose d'inexploitable :
 * timeout, coupure reseau, 5xx, HTML d'une page d'erreur a la place du TLE.
 *
 * <p>Erreur <em>transitoire</em> : le magasin la rattrape et sert le dernier TLE connu
 * s'il en a un. Elle ne remonte a l'appelant que lorsqu'il n'y a rien a degrader.
 */
public class TleUnavailableException extends RuntimeException {

    public TleUnavailableException(String message) {
        super(message);
    }

    public TleUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
