package space.nextpass.delays;

import java.time.Instant;

/**
 * One row of Space-Track's {@code satcat_debut} class: when an object entered the public
 * catalogue.
 *
 * @param noradId the catalogue number, six digits for objects catalogued since July 2026
 * @param debutAt Space-Track's {@code DEBUT}, UTC
 * @param intldes the international designator ({@code 2026-051A}), when given
 * @param name    the catalogue name, when given
 */
public record SpaceTrackDebut(int noradId, Instant debutAt, String intldes, String name) {}
