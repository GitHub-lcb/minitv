-- MySQL has no partial indexes. Each PostgreSQL partial unique index is
-- replaced by a STORED generated column that is NULL outside the predicate
-- plus a plain unique key; MySQL treats NULLs as distinct, which reproduces
-- the partial semantics exactly.
--
-- Partial non-unique indexes are just optimisation hints, so their predicate
-- is dropped and the plain column index is recreated.

-- subscriptions: at most one active subscription per user
ALTER TABLE subscriptions
    ADD COLUMN active_user_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN status = 'active' THEN user_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uq_subscriptions_one_active_per_user (active_user_id);

-- admin_api_keys: a revoked key releases its name, so rotating can reuse it
ALTER TABLE admin_api_keys
    ADD COLUMN active_admin_user_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN status = 'active' THEN admin_user_id ELSE NULL END) STORED,
    ADD COLUMN active_name VARCHAR(64)
        GENERATED ALWAYS AS (CASE WHEN status = 'active' THEN name ELSE NULL END) STORED,
    ADD UNIQUE KEY uq_admin_api_keys_user_name_active (active_admin_user_id, active_name);

-- admin_users: one supplier login per supplier (NULLs are not constrained)
ALTER TABLE admin_users
    ADD UNIQUE KEY uq_admin_users_supplier (supplier_id);

-- dramas: title unique per tenant; self-operated rows stay globally unique.
-- (supplier_id, title) already allows any number of rows with a NULL supplier,
-- so the plain unique key covers "unique per tenant" without a predicate.
ALTER TABLE dramas
    ADD COLUMN self_title VARCHAR(200)
        GENERATED ALWAYS AS (CASE WHEN supplier_id IS NULL THEN title ELSE NULL END) STORED,
    ADD UNIQUE KEY uq_dramas_title_self (self_title),
    ADD UNIQUE KEY uq_dramas_title_supplier (supplier_id, title);

-- plain replacements for the partial non-unique indexes
CREATE INDEX ix_dramas_review_state ON dramas (review_state);
CREATE INDEX ix_orders_source_drama ON orders (source_drama_id, paid_at);
CREATE INDEX ix_orders_refunded_at ON orders (refunded_at);
