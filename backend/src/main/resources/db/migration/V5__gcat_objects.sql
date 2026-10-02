-- Jonathan McDowell's General Catalog of Artificial Space Objects (GCAT), CC-BY,
-- https://planet4589.org/space/gcat/ - the foundation of phase 3 (see ROADMAP.md).
-- Refreshed nightly by POST /internal/import; GcatParser and GcatDate explain the format.
CREATE TABLE gcat_objects (
    -- GCAT's own key (S00001, S100961): every object has one, not every object has a
    -- NORAD number.
    jcat varchar(16) PRIMARY KEY,
    satcat integer CHECK (satcat > 0),
    launch_tag varchar(32),
    piece varchar(32),
    type varchar(16),
    name varchar(200),
    payload_name varchar(200),
    -- Each date three ways: GCAT's text, quoted as evidence; the start of the interval
    -- it denotes, to sort by; and how much of it is known. The instant is never shown
    -- without its precision: '2026 May?' is not the first of May.
    launch_text varchar(32),
    launch_at timestamptz,
    launch_precision varchar(8),
    -- The parent's identifier, for joins, and GCAT's whole cell ('S03504*',
    -- 'S16273  AL'): what the mark and the location mean is phase 3.1's question.
    parent varchar(16),
    parent_text varchar(32),
    separation_text varchar(32),
    separation_at timestamptz,
    separation_precision varchar(8),
    separation_uncertain boolean,
    primary_body varchar(32),
    decay_text varchar(32),
    decay_at timestamptz,
    status varchar(16),
    owner varchar(64),
    state varchar(16),
    mass_kg double precision,
    perigee_km double precision,
    apogee_km double precision,
    inclination_deg double precision,
    op_orbit varchar(16),
    alt_names text,
    -- GcatObject.isSeparation(), stored so that "latest separations" is an index scan.
    is_separation boolean NOT NULL,
    -- When NextPass first saw the row, never updated afterwards. Phase 3.2 measures
    -- GCAT's delay against it, so it must mean "first import that had it", not "now".
    first_seen_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX gcat_objects_satcat ON gcat_objects (satcat);
CREATE INDEX gcat_objects_parent ON gcat_objects (parent);
CREATE INDEX gcat_objects_separations ON gcat_objects (separation_at DESC) WHERE is_separation;

-- One row per downloaded file: its validators make the next night's download conditional,
-- so an unchanged 19 MB file costs a 304 rather than a parse and 70 000 comparisons.
CREATE TABLE gcat_files (
    name varchar(64) PRIMARY KEY,
    etag varchar(200),
    last_modified varchar(64),
    rows_read integer NOT NULL,
    imported_at timestamptz NOT NULL
);
