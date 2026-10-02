-- Phase 3.2 (ROADMAP.md, ABD-9): how late does GCAT list an object compared with
-- Space-Track's public catalogue? A home-made detector (phase 3.3) is only worth building
-- if it would be faster than both.

-- When each object entered Space-Track's public catalogue: the DEBUT field of its
-- satcat_debut class, fetched every night over a sliding window by POST /internal/import.
-- Kept for every object, not only separations, so that the sample is not a dozen rows.
CREATE TABLE spacetrack_debuts (
    norad_id integer PRIMARY KEY CHECK (norad_id > 0),
    debut_at timestamptz NOT NULL,
    intldes varchar(16),
    name varchar(200),
    -- The first night that saw it, never updated: with GCAT's first_seen_at, it says when
    -- an object's delay became measurable.
    first_fetched_at timestamptz NOT NULL
);

-- One row per object both catalogues hold, with the delays in hours. A view rather than
-- stored numbers: GCAT may refine a separation date later, and the delay must follow.
--
-- Only objects GCAT listed after the first import count. That import, on 2 October 2026,
-- marked 70 000 rows "first seen" at once, which says nothing about when GCAT had them;
-- min(first_seen_at) is that import, so "after it" needs no date written down here.
--
-- GCAT's key is jcat, not the NORAD number: should two rows share a satcat, the one GCAT
-- listed first (then the smallest jcat) speaks for the object, so a duplicate can neither
-- count twice nor make an object look newer than GCAT's first sighting of it.
--
-- Read the numbers as bounds, not points:
--   * gcat_first_seen_at is the nightly import that first had the row, so GCAT may have
--     listed it up to a day earlier: gcat_after_spacetrack_hours overstates GCAT's delay
--     by up to 24 h, and can be negative when GCAT is the faster one;
--   * a separation known to the day starts at midnight UTC, so the delays measured from
--     it overstate by up to 24 h. Coarser dates (month, quarter) are not measured at all.
CREATE VIEW catalogue_delays AS
WITH first_row AS (
    SELECT DISTINCT ON (satcat) *
    FROM gcat_objects
    WHERE satcat IS NOT NULL
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
WHERE g.first_seen_at > (SELECT min(first_seen_at) FROM gcat_objects);
