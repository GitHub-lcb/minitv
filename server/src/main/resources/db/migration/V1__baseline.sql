-- MySQL 8 baseline generated from the SQLAlchemy models.
-- Regenerate with: .venv/Scripts/python.exe server/tools/generate_baseline.py
SET NAMES utf8mb4;

CREATE TABLE audit_logs (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	actor_type VARCHAR(16) NOT NULL, 
	actor_id BIGINT, 
	action VARCHAR(64) NOT NULL, 
	target_type VARCHAR(32) NOT NULL, 
	target_id BIGINT, 
	payload JSON NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_audit_logs_created_at ON audit_logs (created_at);

CREATE TABLE billing_settings (
	id INTEGER NOT NULL, 
	billing_mode VARCHAR(16) NOT NULL, 
	updated_by BIGINT, 
	updated_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE copy_entries (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	`key` VARCHAR(120) NOT NULL, 
	en TEXT NOT NULL, 
	zh TEXT NOT NULL, 
	PRIMARY KEY (id), 
	UNIQUE (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE daily_metrics (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	business_day DATE NOT NULL, 
	timezone VARCHAR(64) NOT NULL, 
	active_users INTEGER NOT NULL, 
	new_users INTEGER NOT NULL, 
	plays INTEGER NOT NULL, 
	completes INTEGER NOT NULL, 
	paid_orders INTEGER NOT NULL, 
	revenue_cents INTEGER NOT NULL, 
	coin_spend INTEGER NOT NULL, 
	unlock_count INTEGER NOT NULL, 
	completion_rate INTEGER NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	updated_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	CONSTRAINT uq_daily_metrics_day UNIQUE (business_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE home_groups (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	name VARCHAR(64) NOT NULL, 
	position INTEGER NOT NULL, 
	status VARCHAR(16) NOT NULL, 
	style VARCHAR(16) NOT NULL, 
	badge VARCHAR(16) NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	updated_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hot_searches (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	term VARCHAR(80) NOT NULL, 
	position INTEGER NOT NULL, 
	enabled BOOL NOT NULL, 
	PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE idempotency_records (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	idempotency_key VARCHAR(128) NOT NULL, 
	endpoint VARCHAR(64) NOT NULL, 
	response_payload JSON NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	UNIQUE (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_idempotency_endpoint_key ON idempotency_records (endpoint, idempotency_key);

CREATE TABLE legal_pages (
	slug VARCHAR(32) NOT NULL, 
	title VARCHAR(120) NOT NULL, 
	body TEXT NOT NULL, 
	PRIMARY KEY (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE marketing_stats (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	label VARCHAR(64) NOT NULL, 
	value VARCHAR(64) NOT NULL, 
	position INTEGER NOT NULL, 
	PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE moderation_settings (
	id INTEGER NOT NULL, 
	enabled BOOL NOT NULL, 
	base_url VARCHAR(200) NOT NULL, 
	model VARCHAR(64) NOT NULL, 
	categories JSON NOT NULL, 
	system_prompt TEXT NOT NULL, 
	approve_threshold FLOAT NOT NULL, 
	block_threshold FLOAT NOT NULL, 
	fallback_action VARCHAR(16) NOT NULL, 
	timeout_seconds INTEGER NOT NULL, 
	api_key_secret TEXT NOT NULL, 
	updated_by BIGINT, 
	updated_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plans (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	code VARCHAR(32) NOT NULL, 
	name_en VARCHAR(64) NOT NULL, 
	name_zh VARCHAR(64) NOT NULL, 
	status VARCHAR(16) NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	updated_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reward_configs (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	code VARCHAR(32) NOT NULL, 
	amounts JSON NOT NULL, 
	effective_on DATE NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	CONSTRAINT uq_reward_code_day UNIQUE (code, effective_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE store_links (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	platform VARCHAR(16) NOT NULL, 
	url VARCHAR(500) NOT NULL, 
	PRIMARY KEY (id), 
	UNIQUE (platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE suppliers (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	name VARCHAR(100) NOT NULL, 
	contact_name VARCHAR(64) NOT NULL, 
	contact_email VARCHAR(320) NOT NULL, 
	status VARCHAR(16) NOT NULL, 
	revenue_share_bps INTEGER NOT NULL, 
	note VARCHAR(500) NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	updated_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	CONSTRAINT ck_suppliers_share CHECK (revenue_share_bps BETWEEN 0 AND 10000), 
	CONSTRAINT ck_suppliers_status CHECK (status IN ('active', 'disabled')), 
	UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tags (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	name_en VARCHAR(64) NOT NULL, 
	name_zh VARCHAR(64) NOT NULL, 
	category VARCHAR(32) NOT NULL, 
	status VARCHAR(16) NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	CONSTRAINT uq_tags_name_en UNIQUE (name_en)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE testimonials (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	author VARCHAR(64) NOT NULL, 
	body TEXT NOT NULL, 
	position INTEGER NOT NULL, 
	PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE users (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	email VARCHAR(320), 
	display_name VARCHAR(64) NOT NULL, 
	avatar_url VARCHAR(500) NOT NULL, 
	gender VARCHAR(16) NOT NULL, 
	bio VARCHAR(500) NOT NULL, 
	birthday DATE, 
	region VARCHAR(8) NOT NULL, 
	locale VARCHAR(8) NOT NULL, 
	public_id VARCHAR(16), 
	following_count INTEGER NOT NULL, 
	follower_count INTEGER NOT NULL, 
	received_likes INTEGER NOT NULL, 
	reminder_opt_in BOOL NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	updated_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	UNIQUE (email), 
	UNIQUE (public_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE verification_codes (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	email VARCHAR(320) NOT NULL, 
	purpose VARCHAR(32) NOT NULL, 
	code_hash VARCHAR(128) NOT NULL, 
	attempts SMALLINT NOT NULL, 
	expires_at DATETIME NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_verification_codes_email_purpose ON verification_codes (email, purpose);

CREATE TABLE ad_claims (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	user_id BIGINT NOT NULL, 
	day DATE NOT NULL, 
	amount INTEGER NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	CONSTRAINT uq_ad_claim_user_day UNIQUE (user_id, day), 
	FOREIGN KEY(user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_users (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	username VARCHAR(64) NOT NULL, 
	password_hash VARCHAR(256) NOT NULL, 
	display_name VARCHAR(64) NOT NULL, 
	`role` VARCHAR(16) NOT NULL, 
	status VARCHAR(16) NOT NULL, 
	supplier_id BIGINT, 
	disable_reason VARCHAR(200), 
	last_login_at DATETIME, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	updated_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	CONSTRAINT ck_admin_users_role CHECK (role IN ('ops', 'finance', 'admin', 'partner')), 
	CONSTRAINT ck_admin_users_status CHECK (status IN ('active', 'disabled')), 
	CONSTRAINT ck_admin_users_partner_supplier CHECK (role <> 'partner' OR supplier_id IS NOT NULL), 
	UNIQUE (username), 
	FOREIGN KEY(supplier_id) REFERENCES suppliers (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE channel_tabs (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	code VARCHAR(32) NOT NULL, 
	title_en VARCHAR(64) NOT NULL, 
	title_zh VARCHAR(64) NOT NULL, 
	position INTEGER NOT NULL, 
	enabled BOOL NOT NULL, 
	locked BOOL NOT NULL, 
	group_id BIGINT, 
	PRIMARY KEY (id), 
	UNIQUE (code), 
	FOREIGN KEY(group_id) REFERENCES home_groups (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE checkins (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	user_id BIGINT NOT NULL, 
	day DATE NOT NULL, 
	streak INTEGER NOT NULL, 
	amount INTEGER NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	CONSTRAINT uq_checkin_user_day UNIQUE (user_id, day), 
	FOREIGN KEY(user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inbox_messages (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	user_id BIGINT NOT NULL, 
	kind VARCHAR(16) NOT NULL, 
	title VARCHAR(200) NOT NULL, 
	body TEXT NOT NULL, 
	read_at DATETIME, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	FOREIGN KEY(user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plan_versions (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	plan_id BIGINT NOT NULL, 
	version INTEGER NOT NULL, 
	price_cents INTEGER NOT NULL, 
	currency VARCHAR(8) NOT NULL, 
	benefits JSON NOT NULL, 
	note TEXT NOT NULL, 
	stripe_price_id VARCHAR(64), 
	stripe_one_time_price_id VARCHAR(64), 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	CONSTRAINT uq_plan_versions_plan_version UNIQUE (plan_id, version), 
	FOREIGN KEY(plan_id) REFERENCES plans (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reactions (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	user_id BIGINT NOT NULL, 
	target_type VARCHAR(16) NOT NULL, 
	target_id BIGINT NOT NULL, 
	kind VARCHAR(16) NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	CONSTRAINT uq_reaction_once UNIQUE (user_id, target_type, target_id, kind), 
	FOREIGN KEY(user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE supplier_settlements (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	supplier_id BIGINT NOT NULL, 
	period_start DATE NOT NULL, 
	period_end DATE NOT NULL, 
	currency VARCHAR(8) NOT NULL, 
	gross_cents BIGINT NOT NULL, 
	revenue_cents BIGINT NOT NULL, 
	effective_share_bps INTEGER NOT NULL, 
	order_count INTEGER NOT NULL, 
	status VARCHAR(16) NOT NULL, 
	note VARCHAR(500) NOT NULL, 
	generated_at DATETIME NOT NULL DEFAULT (now()), 
	confirmed_at DATETIME, 
	paid_at DATETIME, 
	PRIMARY KEY (id), 
	CONSTRAINT uq_settlement_period UNIQUE (supplier_id, period_start), 
	CONSTRAINT ck_settlements_status CHECK (status IN ('draft', 'confirmed', 'paid')), 
	FOREIGN KEY(supplier_id) REFERENCES suppliers (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_settlements_period ON supplier_settlements (period_start, status);

CREATE TABLE task_claims (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	user_id BIGINT NOT NULL, 
	code VARCHAR(32) NOT NULL, 
	period_key VARCHAR(16) NOT NULL, 
	amount INTEGER NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	CONSTRAINT uq_task_claim_user_code_day UNIQUE (user_id, code, period_key), 
	FOREIGN KEY(user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE third_party_identities (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	user_id BIGINT NOT NULL, 
	provider VARCHAR(32) NOT NULL, 
	provider_user_id VARCHAR(255) NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	CONSTRAINT uq_tpi_provider_uid UNIQUE (provider, provider_user_id), 
	CONSTRAINT uq_tpi_user_provider UNIQUE (user_id, provider), 
	FOREIGN KEY(user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE wallet_accounts (
	user_id BIGINT NOT NULL, 
	balance INTEGER NOT NULL CONSTRAINT ck_wallet_balance_nonnegative CHECK (balance >= 0), 
	updated_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (user_id), 
	FOREIGN KEY(user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_api_keys (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	admin_user_id BIGINT NOT NULL, 
	name VARCHAR(64) NOT NULL, 
	key_prefix VARCHAR(24) NOT NULL, 
	key_hash VARCHAR(64) NOT NULL, 
	scopes JSON NOT NULL, 
	status VARCHAR(16) NOT NULL, 
	expires_at DATETIME, 
	last_used_at DATETIME, 
	created_by BIGINT, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	revoked_at DATETIME, 
	revoked_by BIGINT, 
	revoke_reason VARCHAR(200), 
	PRIMARY KEY (id), 
	CONSTRAINT ck_admin_api_keys_status CHECK (status IN ('active', 'revoked')), 
	FOREIGN KEY(admin_user_id) REFERENCES admin_users (id), 
	FOREIGN KEY(created_by) REFERENCES admin_users (id), 
	FOREIGN KEY(revoked_by) REFERENCES admin_users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_admin_api_keys_user_status ON admin_api_keys (admin_user_id, status);
CREATE UNIQUE INDEX uq_admin_api_keys_prefix ON admin_api_keys (key_prefix);

CREATE TABLE dramas (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	title VARCHAR(200) NOT NULL, 
	synopsis TEXT NOT NULL, 
	cover_url VARCHAR(500) NOT NULL, 
	free_episode_count INTEGER NOT NULL, 
	coin_price_per_episode INTEGER NOT NULL, 
	status VARCHAR(16) NOT NULL, 
	country_allowlist VARCHAR(200) NOT NULL, 
	age_rating SMALLINT, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	updated_at DATETIME NOT NULL DEFAULT (now()), 
	published_at DATETIME, 
	scheduled_publish_at DATETIME, 
	play_count BIGINT NOT NULL, 
	supplier_id BIGINT, 
	review_state VARCHAR(16) NOT NULL, 
	review_note VARCHAR(500), 
	submitted_at DATETIME, 
	reviewed_at DATETIME, 
	reviewed_by BIGINT, 
	PRIMARY KEY (id), 
	FOREIGN KEY(supplier_id) REFERENCES suppliers (id), 
	FOREIGN KEY(reviewed_by) REFERENCES admin_users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_dramas_status ON dramas (status);
CREATE INDEX ix_dramas_supplier_status ON dramas (supplier_id, status);

CREATE TABLE supplier_share_history (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	supplier_id BIGINT NOT NULL, 
	share_bps INTEGER NOT NULL, 
	effective_from DATETIME NOT NULL DEFAULT (now()), 
	effective_to DATETIME, 
	changed_by BIGINT, 
	note VARCHAR(200) NOT NULL, 
	PRIMARY KEY (id), 
	CONSTRAINT ck_share_history_bps CHECK (share_bps BETWEEN 0 AND 10000), 
	FOREIGN KEY(supplier_id) REFERENCES suppliers (id), 
	FOREIGN KEY(changed_by) REFERENCES admin_users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_supplier_share_lookup ON supplier_share_history (supplier_id, effective_from);

CREATE TABLE api_key_events (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	key_id BIGINT NOT NULL, 
	staff_id BIGINT NOT NULL, 
	method VARCHAR(8) NOT NULL, 
	path VARCHAR(200) NOT NULL, 
	status_code SMALLINT NOT NULL, 
	duration_ms INTEGER, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	FOREIGN KEY(key_id) REFERENCES admin_api_keys (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_api_key_events_key_created ON api_key_events (key_id, created_at);

CREATE TABLE banners (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	slot VARCHAR(32) NOT NULL, 
	drama_id BIGINT, 
	starts_on DATE, 
	ends_on DATE, 
	status VARCHAR(16) NOT NULL, 
	position INTEGER NOT NULL, 
	kicker VARCHAR(80) NOT NULL, 
	headline VARCHAR(80) NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(drama_id) REFERENCES dramas (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE drama_home_groups (
	group_id BIGINT NOT NULL, 
	drama_id BIGINT NOT NULL, 
	position_in_group INTEGER NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (group_id, drama_id), 
	FOREIGN KEY(group_id) REFERENCES home_groups (id), 
	FOREIGN KEY(drama_id) REFERENCES dramas (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE drama_tags (
	drama_id BIGINT NOT NULL, 
	tag_id BIGINT NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (drama_id, tag_id), 
	FOREIGN KEY(drama_id) REFERENCES dramas (id), 
	FOREIGN KEY(tag_id) REFERENCES tags (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE episodes (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	drama_id BIGINT NOT NULL, 
	episode_number INTEGER NOT NULL, 
	title VARCHAR(200) NOT NULL, 
	duration_seconds INTEGER NOT NULL, 
	bunny_video_id VARCHAR(64) NOT NULL, 
	subtitle_url VARCHAR(500) NOT NULL, 
	width INTEGER NOT NULL, 
	height INTEGER NOT NULL, 
	size_bytes BIGINT NOT NULL, 
	mime VARCHAR(64) NOT NULL, 
	status VARCHAR(20) NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	updated_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	CONSTRAINT uq_episodes_drama_number UNIQUE (drama_id, episode_number), 
	FOREIGN KEY(drama_id) REFERENCES dramas (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_episodes_drama_status ON episodes (drama_id, status);

CREATE TABLE orders (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	order_no VARCHAR(64) NOT NULL, 
	user_id BIGINT NOT NULL, 
	plan_version_id BIGINT NOT NULL, 
	amount_cents INTEGER NOT NULL, 
	currency VARCHAR(8) NOT NULL, 
	billing_mode VARCHAR(16) NOT NULL, 
	status VARCHAR(16) NOT NULL, 
	external_txn_id VARCHAR(128), 
	paid_at DATETIME, 
	source_drama_id BIGINT, 
	refunded_at DATETIME, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	updated_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	UNIQUE (order_no), 
	FOREIGN KEY(user_id) REFERENCES users (id), 
	FOREIGN KEY(plan_version_id) REFERENCES plan_versions (id), 
	UNIQUE (external_txn_id), 
	FOREIGN KEY(source_drama_id) REFERENCES dramas (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_orders_user_created ON orders (user_id, created_at);

CREATE TABLE push_records (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	drama_id BIGINT, 
	title VARCHAR(200) NOT NULL, 
	body TEXT NOT NULL, 
	delivered_count BIGINT NOT NULL, 
	sent_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	FOREIGN KEY(drama_id) REFERENCES dramas (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE subscriptions (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	user_id BIGINT NOT NULL, 
	plan_id BIGINT NOT NULL, 
	plan_version_id BIGINT NOT NULL, 
	status VARCHAR(16) NOT NULL, 
	current_period_start DATETIME NOT NULL, 
	current_period_end DATETIME NOT NULL, 
	canceled_at DATETIME, 
	stripe_customer_id VARCHAR(64), 
	stripe_subscription_id VARCHAR(64), 
	stripe_price_id VARCHAR(64), 
	billing_mode VARCHAR(16) NOT NULL, 
	pass_end DATETIME, 
	source_drama_id BIGINT, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	updated_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	FOREIGN KEY(user_id) REFERENCES users (id), 
	FOREIGN KEY(plan_id) REFERENCES plans (id), 
	FOREIGN KEY(plan_version_id) REFERENCES plan_versions (id), 
	FOREIGN KEY(source_drama_id) REFERENCES dramas (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE cdn_purges (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	episode_id BIGINT, 
	drama_id BIGINT, 
	reason VARCHAR(64) NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	FOREIGN KEY(episode_id) REFERENCES episodes (id), 
	FOREIGN KEY(drama_id) REFERENCES dramas (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE coin_ledger (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	user_id BIGINT NOT NULL, 
	amount INTEGER NOT NULL CONSTRAINT ck_coin_ledger_amount_nonzero CHECK (amount <> 0), 
	balance_after INTEGER NOT NULL, 
	biz_type VARCHAR(32) NOT NULL, 
	biz_key VARCHAR(128) NOT NULL, 
	episode_id BIGINT, 
	note VARCHAR(500) NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	FOREIGN KEY(user_id) REFERENCES users (id), 
	UNIQUE (biz_key), 
	FOREIGN KEY(episode_id) REFERENCES episodes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_coin_ledger_user_created ON coin_ledger (user_id, created_at);

CREATE TABLE comments (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	user_id BIGINT NOT NULL, 
	drama_id BIGINT NOT NULL, 
	episode_id BIGINT NOT NULL, 
	body TEXT NOT NULL, 
	status VARCHAR(16) NOT NULL, 
	moderation_verdict VARCHAR(16), 
	moderation_score FLOAT, 
	moderation_reason TEXT, 
	moderation_model VARCHAR(64), 
	moderated_at DATETIME, 
	moderation_state VARCHAR(16), 
	moderation_attempts SMALLINT NOT NULL DEFAULT '0', 
	moderation_claimed_at DATETIME, 
	moderation_error VARCHAR(200), 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	FOREIGN KEY(user_id) REFERENCES users (id), 
	FOREIGN KEY(drama_id) REFERENCES dramas (id), 
	FOREIGN KEY(episode_id) REFERENCES episodes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE entitlements (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	user_id BIGINT NOT NULL, 
	episode_id BIGINT NOT NULL, 
	entitlement_type VARCHAR(16) NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	CONSTRAINT uq_entitlements_user_episode_type UNIQUE (user_id, episode_id, entitlement_type), 
	FOREIGN KEY(user_id) REFERENCES users (id), 
	FOREIGN KEY(episode_id) REFERENCES episodes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE media_uploads (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	episode_id BIGINT NOT NULL, 
	video_id VARCHAR(64) NOT NULL, 
	filename VARCHAR(255) NOT NULL, 
	mime VARCHAR(64) NOT NULL, 
	size_bytes BIGINT NOT NULL, 
	vertical BOOL NOT NULL, 
	status VARCHAR(16) NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	FOREIGN KEY(episode_id) REFERENCES episodes (id), 
	UNIQUE (video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_media_uploads_episode ON media_uploads (episode_id);

CREATE TABLE playback_events (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	user_id BIGINT, 
	episode_id BIGINT NOT NULL, 
	kind VARCHAR(16) NOT NULL, 
	position_seconds INTEGER NOT NULL, 
	created_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (id), 
	FOREIGN KEY(user_id) REFERENCES users (id), 
	FOREIGN KEY(episode_id) REFERENCES episodes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE playback_progress (
	user_id BIGINT NOT NULL, 
	episode_id BIGINT NOT NULL, 
	position_seconds INTEGER NOT NULL, 
	updated_at DATETIME NOT NULL DEFAULT (now()), 
	PRIMARY KEY (user_id, episode_id), 
	CONSTRAINT uq_progress_user_episode UNIQUE (user_id, episode_id), 
	FOREIGN KEY(user_id) REFERENCES users (id), 
	FOREIGN KEY(episode_id) REFERENCES episodes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

