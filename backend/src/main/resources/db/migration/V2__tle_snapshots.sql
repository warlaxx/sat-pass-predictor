-- The last known elements per satellite, so that a restart does not put CelesTrak back
-- in the request path. Nothing expires here: tle.max-age decides what is servable, when
-- it is served. A row disappears only when the catalogue no longer has the object.
CREATE TABLE tle_snapshots (
    norad_id integer PRIMARY KEY CHECK (norad_id > 0),
    name varchar(200) NOT NULL,
    -- varchar, not char: a TLE line is exactly 69 columns and char(69) would pad or
    -- silently accept a shorter one, turning a malformed line into a stored one.
    line1 varchar(69) NOT NULL CHECK (length(line1) = 69),
    line2 varchar(69) NOT NULL CHECK (length(line2) = 69),
    epoch timestamptz NOT NULL,
    fetched_at timestamptz NOT NULL,
    source varchar(200) NOT NULL
);
