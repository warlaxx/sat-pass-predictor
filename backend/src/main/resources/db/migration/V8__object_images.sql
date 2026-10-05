-- ABD-45: a photograph for the objects that have a free one. Wikidata links an item to
-- its NORAD number (P377) and to a Wikimedia Commons file (P18); Commons says who made
-- the file and under which licence. Refreshed nightly by POST /internal/import, after
-- GCAT; ImageImport explains what is kept.
--
-- About 1 900 objects of the 27 000 GCAT lists have one (October 2026): stages,
-- adapters and debris almost never do, and the pages draw an illustration for them.
CREATE TABLE object_images (
    norad_id integer PRIMARY KEY CHECK (norad_id > 0),
    -- The Commons file, without the "File:" prefix: 'Hubble 2009 close-up.jpg'.
    file varchar(255) NOT NULL,
    -- A 500-pixel thumbnail on upload.wikimedia.org, never anything else (ImageImport).
    thumb_url varchar(1024) NOT NULL,
    thumb_width integer,
    thumb_height integer,
    -- What the licence makes us show beside the picture: author, licence and a link to
    -- the file's description page. Author is plain text, stripped of Commons' markup.
    author varchar(500),
    licence varchar(64) NOT NULL,
    licence_url varchar(1024),
    description_url varchar(1024) NOT NULL,
    updated_at timestamptz NOT NULL
);
