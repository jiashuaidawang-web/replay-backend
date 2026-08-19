-- ============================================================
-- S8 个人交易日志表（openGauss / PostgreSQL）
-- 数据源：og（@Primary），由 OgMybatisConfig 扫描 mapper.og 包。
-- 部署时在本库（postgres）执行一次即可。
-- openGauss 兼容 PostgreSQL 语法，BIGSERIAL 自增、TIMESTAMPTZ 均支持。
-- ============================================================

CREATE TABLE IF NOT EXISTS trade_log (
    id           BIGSERIAL    PRIMARY KEY,
    trade_date   DATE         NOT NULL,
    ts_code      VARCHAR(16),
    side         VARCHAR(8)   NOT NULL DEFAULT 'buy',
    price        NUMERIC(18,4),
    qty          NUMERIC(18,4),
    reason       VARCHAR(512),
    emotion_tag  VARCHAR(64),
    reaction     VARCHAR(32),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trade_log_date ON trade_log (trade_date DESC);
CREATE INDEX IF NOT EXISTS idx_trade_log_emotion ON trade_log (emotion_tag);
