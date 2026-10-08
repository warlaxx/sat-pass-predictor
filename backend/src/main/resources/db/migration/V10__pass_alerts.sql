-- E-mail reminders before a pass worth going out for (ABD-42).
--
-- Only what the reminder needs: the address, one satellite, a place rounded to 0.01°
-- (about a kilometre: a pass's times move by a fraction of a second over that), the
-- thresholds, and the zone and language the e-mail is written in. No name, no IP address,
-- no history of what was sent beyond the last day.
--
-- The token is the only key a reader holds: the confirmation link and every unsubscribe
-- link carry it. It is stored as is, not hashed, because every reminder must write it back
-- into its links; whoever can read this table can read the addresses anyway.
CREATE TABLE pass_alerts (
    id bigserial PRIMARY KEY,
    email varchar(254) NOT NULL,
    norad_id integer NOT NULL CHECK (norad_id BETWEEN 1 AND 339999),
    latitude_deg numeric(5, 2) NOT NULL CHECK (latitude_deg BETWEEN -90 AND 90),
    longitude_deg numeric(5, 2) NOT NULL CHECK (longitude_deg BETWEEN -180 AND 180),
    min_elevation_deg smallint NOT NULL CHECK (min_elevation_deg BETWEEN 10 AND 80),
    max_cloud_percent smallint NOT NULL CHECK (max_cloud_percent BETWEEN 0 AND 100),
    max_magnitude numeric(3, 1),
    time_zone varchar(64) NOT NULL,
    locale varchar(2) NOT NULL CHECK (locale IN ('en', 'fr')),
    token varchar(64) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    confirmation_sent_at timestamptz,
    confirmed_at timestamptz,
    -- Local dates in time_zone: the day the subscription was last looked at, and the last
    -- day a reminder went out. At most one reminder a day hangs on the first.
    last_checked_on date,
    last_sent_on date,
    UNIQUE (email, norad_id, latitude_deg, longitude_deg)
);

CREATE INDEX pass_alerts_email ON pass_alerts (email);

-- Resend's free plan sends 100 e-mails a day. Counted per UTC day and kind (confirmation,
-- reminder) so that a burst of sign-ups cannot spend the reminders' share.
CREATE TABLE alert_email_counts (
    day date NOT NULL,
    kind varchar(16) NOT NULL,
    count integer NOT NULL CHECK (count > 0),
    PRIMARY KEY (day, kind)
);
