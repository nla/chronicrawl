ALTER TABLE location ADD COLUMN IF NOT EXISTS depth SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE location ALTER COLUMN via SET NULL;
CREATE TABLE IF NOT EXISTS screenshot_cache (
                                  visit_id UUID NOT NULL PRIMARY KEY,
                                  screenshot BLOB NOT NULL,
                                  FOREIGN KEY (visit_id) REFERENCES visit (id) ON DELETE CASCADE
);