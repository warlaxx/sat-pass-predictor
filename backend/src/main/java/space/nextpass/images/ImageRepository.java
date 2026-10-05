package space.nextpass.images;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code object_images} (see {@code V8__object_images.sql}). */
public class ImageRepository {

    private static final String UPSERT = """
            INSERT INTO object_images (norad_id, file, thumb_url, thumb_width, thumb_height, author,
                                       licence, licence_url, description_url, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (norad_id) DO UPDATE SET
                file = EXCLUDED.file, thumb_url = EXCLUDED.thumb_url,
                thumb_width = EXCLUDED.thumb_width, thumb_height = EXCLUDED.thumb_height,
                author = EXCLUDED.author, licence = EXCLUDED.licence,
                licence_url = EXCLUDED.licence_url, description_url = EXCLUDED.description_url,
                updated_at = EXCLUDED.updated_at""";

    private final JdbcTemplate jdbc;

    public ImageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int count() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM object_images", Integer.class);
        return count == null ? 0 : count;
    }

    /** Writes every image and stamps it with {@code runAt}. */
    public void upsert(List<ObjectImage> images, Instant runAt) {
        Timestamp at = Timestamp.from(runAt);
        jdbc.batchUpdate(UPSERT, images, 500, (ps, image) -> {
            ps.setInt(1, image.noradId());
            ps.setString(2, image.file());
            ps.setString(3, image.thumbUrl());
            if (image.thumbWidth() == null) {
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setInt(4, image.thumbWidth());
            }
            if (image.thumbHeight() == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setInt(5, image.thumbHeight());
            }
            ps.setString(6, image.author());
            ps.setString(7, image.licence());
            ps.setString(8, image.licenceUrl());
            ps.setString(9, image.descriptionUrl());
            ps.setTimestamp(10, at);
        });
    }

    /** Removes the images this run did not see again: deleted, relicensed or unlinked. */
    public int removeOlderThan(Instant runAt) {
        return jdbc.update("DELETE FROM object_images WHERE updated_at < ?", Timestamp.from(runAt));
    }
}
