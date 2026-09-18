package dev.abdallah.satpass.tle;

import dev.abdallah.satpass.domain.TleSnapshot;

/**
 * A place orbital elements can be obtained from.
 *
 * <p>The interface exists so that "one endpoint" and "several endpoints tried in order"
 * are two implementations of the same contract rather than a flag inside one class:
 * {@link TleStore} decides <em>when</em> to fetch and has no business knowing how many
 * hosts are behind the call.
 *
 * <p>The contract is the three outcomes, and the difference between them is not
 * cosmetic — the store drops a satellite on the first, keeps its cached snapshot on the
 * second:
 * <ul>
 *   <li>a {@link TleSnapshot}, validated, for the satellite that was asked for;</li>
 *   <li>{@link TleNotFoundException}, meaning the catalogue answered and does not hold
 *       this object;</li>
 *   <li>{@link TleUnavailableException}, meaning no usable answer was obtained — which
 *       covers every transport failure and every response that is not a TLE.</li>
 * </ul>
 */
public interface TleClient {

    /**
     * @throws TleNotFoundException    if the catalogue does not contain this number.
     * @throws TleUnavailableException if no source produced a usable TLE.
     */
    TleSnapshot fetch(int noradId);
}
