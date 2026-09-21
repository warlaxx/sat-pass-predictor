ALTER TABLE customer_accounts ADD COLUMN stripe_customer_id varchar(255) UNIQUE;
ALTER TABLE customer_accounts ADD COLUMN checkout_plan varchar(16);
ALTER TABLE customer_accounts ADD COLUMN checkout_id varchar(255);
ALTER TABLE customer_accounts ADD COLUMN checkout_attempt integer NOT NULL DEFAULT 0;
ALTER TABLE api_keys ADD COLUMN billing_plan varchar(16) CHECK (billing_plan IN ('hobby', 'pro'));
ALTER TABLE api_keys ADD COLUMN monthly_limit integer CHECK (monthly_limit > 0);
ALTER TABLE api_keys ADD COLUMN paid_until timestamptz;
CREATE TABLE billing_events (
    event_id varchar(255) PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT now()
);
