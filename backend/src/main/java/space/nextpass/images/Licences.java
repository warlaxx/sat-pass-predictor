package space.nextpass.images;

import java.util.Locale;

/**
 * Which Commons licences NextPass may show a picture under: the public domain, CC0, and
 * Creative Commons Attribution with or without ShareAlike, any version. They allow
 * commercial use and need nothing but the credit the page prints.
 *
 * <p>Read from {@code extmetadata.License}, Commons' machine code ({@code cc-by-sa-4.0},
 * {@code pd}, {@code cc0}), never from the display name. Anything else is refused:
 * GFDL alone (it wants its full text beside the picture), NonCommercial and NoDerivatives
 * (a thumbnail is a derivative), and files with no machine-readable licence at all.
 */
public final class Licences {

    private Licences() {}

    public static boolean accepts(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String c = code.strip().toLowerCase(Locale.ROOT);
        if (c.equals("pd") || c.startsWith("pd-") || c.equals("cc0") || c.startsWith("cc0-")) {
            return true;
        }
        if (!c.startsWith("cc-by")) {
            return false;
        }
        String rest = c.substring("cc-by".length());
        return !rest.contains("-nc") && !rest.contains("-nd");
    }
}
