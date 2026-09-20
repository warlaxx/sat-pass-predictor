CREATE TABLE api_keys (
    id uuid PRIMARY KEY,
    key_hash varchar(64) UNIQUE,
    owner varchar(200) NOT NULL,
    plan varchar(16) NOT NULL CHECK (plan IN ('demo', 'standard')),
    active boolean NOT NULL DEFAULT true,
    daily_limit integer NOT NULL CHECK (daily_limit > 0),
    minute_limit integer NOT NULL CHECK (minute_limit > 0),
    minute_start timestamptz,
    minute_used integer NOT NULL DEFAULT 0 CHECK (minute_used >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((plan = 'demo' AND key_hash IS NULL) OR (plan = 'standard' AND key_hash IS NOT NULL))
);
-- Public demo identity, never a secret sent to the browser. One shared quota on all replicas.
INSERT INTO api_keys (id, owner, plan, daily_limit, minute_limit)
VALUES ('00000000-0000-0000-0000-000000000001', 'public-demo', 'demo', 200, 20);

CREATE TABLE api_usage (
    key_id uuid NOT NULL REFERENCES api_keys(id),
    usage_day date NOT NULL,
    endpoint varchar(32) NOT NULL,
    requests bigint NOT NULL CHECK (requests >= 0),
    PRIMARY KEY (key_id, usage_day, endpoint)
);
