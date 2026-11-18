-- External video sources: platform embeds and direct HLS/MP4 URLs.
--
-- bunny (default) keeps the existing signed-HLS pipeline. Everything else is
-- playable without Bunny: platform embeds are rendered client-side with the
-- official player, direct HLS/MP4 URLs are handed to the player as-is.
ALTER TABLE episodes
    ADD COLUMN video_provider VARCHAR(16) NOT NULL DEFAULT 'bunny',
    ADD COLUMN external_video_id VARCHAR(128) NULL,
    ADD COLUMN external_url VARCHAR(1000) NULL,
    ADD COLUMN aspect_ratio VARCHAR(8) NOT NULL DEFAULT '';

CREATE INDEX ix_episodes_provider ON episodes (video_provider);
