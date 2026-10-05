package space.nextpass.images;

/**
 * A free photograph of one catalogued object, with what its licence requires us to show
 * beside it.
 *
 * @param noradId        the object, by NORAD catalogue number
 * @param file           the Commons file name, without {@code File:}
 * @param thumbUrl       a thumbnail on {@code upload.wikimedia.org}
 * @param author         plain text, null when Commons names nobody
 * @param licence        Commons' short name for it ({@code CC BY-SA 4.0}, {@code Public domain})
 * @param licenceUrl     the licence's text, null for the public domain
 * @param descriptionUrl the file's page on Commons, where the full credit lives
 */
public record ObjectImage(int noradId, String file, String thumbUrl, Integer thumbWidth, Integer thumbHeight,
                          String author, String licence, String licenceUrl, String descriptionUrl) {}
