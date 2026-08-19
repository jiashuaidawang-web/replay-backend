-- S3 主力博弈 · 个股级主力合力日级产出（ClickHouse ReplacingMergeTree，重算幂等）
DROP TABLE IF EXISTS main_force_daily;

CREATE TABLE main_force_daily
(
    trade_date      Date,
    ts_code         String,
    stock_name      Nullable(String),
    reason          Nullable(String),
    abnormal_type   Nullable(String),
    net_buy         Nullable(Decimal(18, 2)),
    total_buy       Nullable(Decimal(18, 2)),
    total_sell      Nullable(Decimal(18, 2)),
    buy_seat_cnt    Nullable(Int32),
    sell_seat_cnt   Nullable(Int32),
    org_net_buy     Nullable(Decimal(18, 2)),
    youzi_net_buy   Nullable(Decimal(18, 2)),
    north_net_buy   Nullable(Decimal(18, 2)),
    consensus_score Nullable(Float64),
    divergence_flag Nullable(Int8),
    d1_return       Nullable(Decimal(18, 4)),
    d5_return       Nullable(Decimal(18, 4)),
    credibility_flag Nullable(Int8),
    change_rate     Nullable(Decimal(18, 4)),
    close_price     Nullable(Decimal(18, 4)),
    turnoverrate    Nullable(Decimal(18, 4)),
    note            Nullable(String),
    _ver            DateTime MATERIALIZED now()
)
ENGINE = ReplacingMergeTree(_ver)
ORDER BY (trade_date, ts_code);
