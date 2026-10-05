package space.nextpass.images;

import java.util.List;

/** Where the photographs come from: Wikidata and Commons in production, a fixture in tests. */
public interface ImageSource {

    /**
     * Every object that has a photograph under a licence {@link Licences} accepts, one per
     * NORAD number.
     *
     * @throws ImageSourceException if a source cannot be reached, refuses the request or
     *                              answers something unreadable: the whole list or nothing
     */
    List<ObjectImage> fetch();
}
