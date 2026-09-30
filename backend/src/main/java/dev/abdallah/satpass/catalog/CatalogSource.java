package dev.abdallah.satpass.catalog;

import java.util.List;

/** Somewhere the whole list of names can be downloaded from, in one request. */
public interface CatalogSource {

    /**
     * @return every usable entry, never empty.
     * @throws CatalogUnavailableException if the source could not be reached or answered
     *                                     anything other than a catalogue.
     */
    List<SatelliteEntry> fetchAll();

    /** Base URL, for logs: with several sources, a failure must say which one. */
    String endpoint();
}
