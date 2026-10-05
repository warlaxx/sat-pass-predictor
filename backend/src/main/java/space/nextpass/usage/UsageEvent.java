package space.nextpass.usage;

import java.util.Arrays;
import java.util.Optional;

/**
 * The actions counted on the separation pages, and on the home page's separations: a closed list, so the endpoint cannot be
 * used to store arbitrary text. Each says which audience it measures.
 */
public enum UsageEvent {
    /** From the list to one event's page. */
    LIST_OPEN_EVENT("list-open-event"),
    /** From the home page's newest events to one event's page: does the home page lead there? */
    HOME_OPEN_EVENT("home-open-event"),
    /** The month chart opened as a table: analyst side. */
    LIST_SHOW_TABLE("list-show-table"),
    /** Passes asked for from the reader's own position: observer side. */
    EVENT_USE_POSITION("event-use-position"),
    /** A pass opened in the predictor: observer side. */
    EVENT_OPEN_PASS("event-open-pass"),
    /** A fragment's own page opened from a breakup: analyst side. */
    EVENT_OPEN_FRAGMENT("event-open-fragment");

    private final String key;

    UsageEvent(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<UsageEvent> of(String key) {
        return Arrays.stream(values()).filter(event -> event.key.equals(key)).findFirst();
    }
}
