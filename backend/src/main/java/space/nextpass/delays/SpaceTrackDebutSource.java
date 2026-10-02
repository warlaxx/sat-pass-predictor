package space.nextpass.delays;

import java.util.List;

/** The objects Space-Track added to its public catalogue recently. */
public interface SpaceTrackDebutSource {

    /**
     * @throws RuntimeException when Space-Track cannot answer; the caller reports it and
     *                          the GCAT import is unaffected.
     */
    List<SpaceTrackDebut> recent();
}
