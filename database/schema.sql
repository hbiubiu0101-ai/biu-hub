-- BIU HUB / 消费记账
-- Worker 首次访问 API 时也会自动执行 CREATE TABLE IF NOT EXISTS，
-- 因此无需在 Cloudflare Console 手工建表。

CREATE TABLE IF NOT EXISTS bookkeeping_state (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  data_json TEXT NOT NULL DEFAULT '{}',
  budget_json TEXT NOT NULL DEFAULT '{}',
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
