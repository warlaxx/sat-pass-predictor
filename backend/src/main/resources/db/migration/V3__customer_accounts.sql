-- GitHub's immutable numeric ID is the identity, never a mutable login or email.
CREATE TABLE customer_accounts (
    github_id varchar(32) PRIMARY KEY,
    key_id uuid UNIQUE REFERENCES api_keys(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
