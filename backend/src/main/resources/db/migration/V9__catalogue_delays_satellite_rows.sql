-- ABD-13 imports GCAT's auxiliary catalogue (auxcat.tsv, rows A…) for the lineage of the
-- separation pages. About 3 700 of its rows carry the NORAD number of the spacecraft they
-- are attached to, and all of them are first seen on the night auxcat is first imported.
-- Left in, the earliest such row could speak for an object (DISTINCT ON picks the smallest
-- jcat on a tie, and 'A' sorts before 'S'), with is_separation false and a first_seen_at
-- that is only the date auxcat arrived. The measure of V7 is about the satellite
-- catalogues, so it reads their rows only; everything else is V7 unchanged.
CREATE OR REPLACE VIEW catalogue_delays AS
WITH first_row AS (
    SELECT DISTINCT ON (satcat) *
    FROM gcat_objects
    WHERE satcat IS NOT NULL AND jcat LIKE 'S%'
    ORDER BY satcat, first_seen_at, jcat
)
SELECT g.jcat,
       d.norad_id,
       coalesce(g.name, d.name) AS name,
       g.is_separation,
       g.separation_text,
       g.separation_at,
       g.separation_precision,
       d.debut_at AS spacetrack_debut_at,
       g.first_seen_at AS gcat_first_seen_at,
       greatest(d.first_fetched_at, g.first_seen_at) AS measured_at,
       extract(epoch FROM g.first_seen_at - d.debut_at) / 3600.0 AS gcat_after_spacetrack_hours,
       CASE WHEN g.separation_precision IN ('DAY', 'MINUTE', 'SECOND')
            THEN extract(epoch FROM d.debut_at - g.separation_at) / 3600.0
       END AS spacetrack_after_separation_hours,
       CASE WHEN g.separation_precision IN ('DAY', 'MINUTE', 'SECOND')
            THEN extract(epoch FROM g.first_seen_at - g.separation_at) / 3600.0
       END AS gcat_after_separation_hours
FROM spacetrack_debuts d
JOIN first_row g ON g.satcat = d.norad_id
WHERE g.first_seen_at > (SELECT min(first_seen_at) FROM gcat_objects WHERE jcat LIKE 'S%');
