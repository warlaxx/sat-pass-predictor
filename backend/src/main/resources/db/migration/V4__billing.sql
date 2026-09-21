ALTER TABLE api_keys DROP CONSTRAINT api_keys_plan_check;
ALTER TABLE api_keys DROP CONSTRAINT api_keys_check;
ALTER TABLE api_keys ADD CONSTRAINT api_keys_plan_check CHECK (plan IN ('demo', 'standard', 'free', 'hobby', 'pro'));
ALTER TABLE api_keys ADD CHECK ((plan = 'demo' AND key_hash IS NULL) OR (plan <> 'demo' AND key_hash IS NOT NULL));
ALTER TABLE api_keys ADD COLUMN monthly_limit integer CHECK (monthly_limit > 0);
ALTER TABLE customer_accounts ADD COLUMN stripe_customer_id varchar(255) UNIQUE;
ALTER TABLE customer_accounts ADD COLUMN checkout_session_id varchar(255);
ALTER TABLE customer_accounts ADD COLUMN checkout_generation integer NOT NULL DEFAULT 0;
ALTER TABLE customer_accounts ADD COLUMN plan varchar(16) NOT NULL DEFAULT 'free' CHECK (plan IN ('free', 'hobby', 'pro'));
UPDATE api_keys SET plan = 'free', monthly_limit = 1000, daily_limit = 2147483647
WHERE id IN (SELECT key_id FROM customer_accounts);
CREATE TABLE billing_events (
    event_id varchar(255) PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT now()
);
