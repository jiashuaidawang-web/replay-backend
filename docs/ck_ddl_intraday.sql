-- =====================================================================
-- 盘中实时模拟盘 · ClickHouse 建表 DDL（M1）
-- 库：crawler（与现有计算层同库，复用 CkHttpWriter 的 http-base-url）
-- 约定：
--   * 业务/分析表用 ReplacingMergeTree + _ver（纯 INSERT 幂等，读用 FINAL）
--   * 实时归档表（rt_*_archive）用普通 MergeTree，按 trade_date 分区，不可变 append，无需 FINAL
--   * 金额=元 / 幅度=% / 成交量=手
--   * 维度字段（stage / capital_confirm / direction / role / action）一律用枚举/短码，便于 GROUP BY 统计
-- 执行：在 Windows CK (100.97.74.45:8123, database=crawler) 上逐段执行。
-- =====================================================================

-- ---------------------------------------------------------------
-- 1) plan_pool 盘前关注池（CK RMT）
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plan_pool
(
    _ver                DateTime MATERIALIZED now(),
    plan_date           Date,
    ts_code             String,
    stock_name          String,
    direction           String,                              -- 板块/方向描述（如"AI算力"），多口径维度
    board_code          String,
    role                String,                              -- 龙一/龙二/妖/独狼/跟风（Enum 码）
    candidate_strategies Array(String),                     -- 候选战法 id 列表
    planned_action      String,                             -- 分歧低吸/一致持有/板上换手/趋势突破
    trigger_price       Nullable(Float64),
    planned_position_pct Nullable(Float64),
    note                String
)
ENGINE = ReplacingMergeTree(_ver)
ORDER BY (plan_date, ts_code);

-- ---------------------------------------------------------------
-- 1.5) watch_pool 次日可溯源观察池（全链路事件线 · 选股段）
-- ---------------------------------------------------------------
-- 承载「T 日复盘战法选股 → 同步 Redis pool → T+1 盘中资金观察 → 买卖 → 归因」整条流水线。
-- 每只股票带 source_skill 标签（S4/S5/S7…），reason 记录「因何战法、何种条件入选」，全链路可回溯。
-- T+1 盘后把 buy_*/sell_*/pnl_*/outcome 回填进同一行，形成事件线。
CREATE TABLE IF NOT EXISTS watch_pool
(
    _ver                DateTime MATERIALIZED now(),
    sel_date            Date,                               -- T 日：选股（复盘）日期
    ts_code             String,                             -- 个股代码（带后缀）
    stock_name          String,
    source_skill        String,                             -- 入选战法标签：S4(龙头)/S5(龙头买卖)/S7(题材)…多战法命中用逗号拼接
    reason              String,                             -- 入选理由（结构化：如"5连板妖股+主线AI算力+分歧日低吸"）
    board_code          String,                             -- 所属主线板块（妖/独狼=__DW__）
    role                String,                             -- 龙一~龙五/妖/独狼
    selected_action     String,                             -- 战法建议动作（buy/buy_dip/hold/reduce/watch…）
    synced_redis        UInt8 DEFAULT 0,                    -- 是否已 SADD 进 ths:l2:pool（0/1）
    -- ---- T+1 事件线预留列（盘后回填，初始 NULL）----
    buy_signal          Nullable(String),                   -- 实际触发买入信号（T+1 盘中）
    buy_reason          Nullable(String),                   -- 因何买入（资金确认/战法触发…）
    buy_price           Nullable(Float64),                  -- 买入价
    sell_reason         Nullable(String),                   -- 因何卖出（止损/落袋/破位…）
    sell_price          Nullable(Float64),                  -- 卖出价
    pnl_pct             Nullable(Float64),                  -- 持仓盈亏 %（卖出后算）
    outcome             Nullable(String)                    -- 落袋为安 / 止损出局 / 持有未动
)
ENGINE = ReplacingMergeTree(_ver)
ORDER BY (sel_date, ts_code);

-- ---------------------------------------------------------------
-- 2) strategy_catalog 战法目录（CK RMT，低频维护）
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS strategy_catalog
(
    _ver            DateTime MATERIALIZED now(),
    strategy_id     String,
    name            String,
    book_ref        String,                                 -- S2/S4/S5/S6/S3...
    applicable_stages Array(String),                       -- 适用情绪阶段码（见 exp_log 注释）
    board_pos_min   Nullable(Int32),
    board_pos_max   Nullable(Int32),
    capital_role    Enum8('IGNORE'=0, 'FILTER'=1, 'CONFIRM'=2),
    description     String,
    enabled         UInt8
)
ENGINE = ReplacingMergeTree(_ver)
ORDER BY strategy_id;

-- 战法种子（《顿悟股道》买入战法全量注册；capital_role: 0忽略/1过滤/2确认）
INSERT INTO strategy_catalog (strategy_id, name, book_ref, applicable_stages, board_pos_min, board_pos_max, capital_role, description, enabled) VALUES
('S2_ICE_TRY',          '冰点试错',           'S2', ['ICE','CHAOS'],                              NULL, NULL, 0, '冰点期阴极先手区，小仓试错',                      1),
('S4_DIVERGE_ABSORB',   '分歧低吸',           'S4', ['DIVERGE','DIVERGE_CONSENSUS'],             2,    NULL, 2, '分歧日低吸换手龙/龙头',                          1),
('S4_CONSENSUS_HOLD',   '一致持有不追',       'S4', ['CONSENSUS','DIVERGE_CONSENSUS'],           NULL, NULL, 1, '一致期持有不追高，防接盘',                      1),
('S4_BOARD_RESEAL',     '板上换手回封',       'S4', ['CONSENSUS'],                               NULL, NULL, 2, '炸板后回封，游资打板信号',                      1),
('S5_LEADER_RELAY',     '龙头接力',           'S5', ['STARTUP','DIVERGE_CONSENSUS'],             2,    5,    2, '上升期龙头接力，2~4板买入',                     1),
('S5_HIGH_DIVERGE_SELL','高位5板分歧卖',      'S5', ['CLIMAX'],                                  NULL, NULL, 1, '高位分歧日减仓/卖出',                          1),
('S6_TREND_BREAK',      '趋势突破半仓',       'S6', ['STARTUP','REPAIR'],                        NULL, NULL, 2, '站上牛熊线+放量突破半仓',                    1),
('S6_TREND_PULLBACK',   '沿趋势低吸',         'S6', ['REPAIR','DIVERGE'],                        NULL, NULL, 0, '趋势股回踩趋势线低吸',                        1),
('S3_MAIN_FORCE_EARLY', '跟主力建仓早',       'S3', ['STARTUP'],                                 NULL, NULL, 2, '主力净流入+低位堆量早跟进',                  1),
('DEMON_DIVERGE_ABSORB','妖股分歧低吸极限',   'S4', ['DIVERGE'],                                 5,    NULL, 2, '连板>=5高标，分歧日极限低吸',                  1),
('WOLF_TREND_LOW',      '独狼沿趋势低吸',     'S4', ['REPAIR'],                                  2,    NULL, 0, '非龙高连板，沿趋势低吸',                        1);

-- ---------------------------------------------------------------
-- 3) realtime_feature 流式派生特征（CK RMT，连续落库）
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS realtime_feature
(
    _ver            DateTime MATERIALIZED now(),
    trade_date      Date,
    ts_code         String,
    ts              DateTime64(3),                           -- 窗口结束
    win_minutes     UInt8,
    big_net_buy     Float64,                                -- 大单净主动买入额 元
    big_net_buy_ratio Float64,                              -- 占成交额比
    seal_amount     Float64,                                -- 封单额
    is_blast        UInt8,                                  -- 炸板
    is_reseal       UInt8,                                  -- 回封
    vol_breakout    UInt8,                                  -- 放量突破
    stage_snapshot  String,                                  -- 当日情绪阶段码
    -- M3 拆单识别
    stealth_net_buy Float64,                                 -- 拆单净主动买入额 元
    sweep_density   Float64,                                 -- 扫单密度 0~1
    self_trade_ratio Float64,                                -- 对敲占比 0~1
    order_pattern   String                                   -- 主形态 NORMAL/STEALTH/SWEEP/SELF_TRADE/MIXED
)
ENGINE = ReplacingMergeTree(_ver)
ORDER BY (ts_code, ts);

-- ---------------------------------------------------------------
-- 4) rt_tick_archive 实时逐笔归档（★真实源头，plain MergeTree，不可变 append）
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rt_tick_archive
(
    trade_date  Date,
    ts_code     String,
    ts          DateTime64(3),
    price       Float64,
    volume      Float64,                                    -- 手
    amount      Float64,                                    -- 元
    direction   Enum8('BUY'=0, 'SELL'=1, 'NEUTRAL'=2)        -- 主动买/卖/中性
)
ENGINE = MergeTree()
PARTITION BY trade_date
ORDER BY (ts_code, ts)
SETTINGS index_granularity = 8192;

-- ---------------------------------------------------------------
-- 5) rt_quote_archive 盘口快照归档（降采样：每 5~10s 或事件落盘）
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rt_quote_archive
(
    trade_date      Date,
    ts_code         String,
    ts              DateTime64(3),
    last_price      Float64,
    pct_chg         Float64,
    amount_day      Float64,
    turnover        Float64,
    high            Float64,
    low             Float64,
    limit_up_price  Float64,
    seal_amount     Float64
)
ENGINE = MergeTree()
PARTITION BY trade_date
ORDER BY (ts_code, ts)
SETTINGS index_granularity = 8192;

-- ---------------------------------------------------------------
-- 6) sim_account 模拟账户（CK RMT）
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sim_account
(
    _ver            DateTime MATERIALIZED now(),
    account_id      String,
    trade_date      Date,
    init_cash       Float64,
    cash            Float64,
    equity          Float64,
    position_cost   Float64
)
ENGINE = ReplacingMergeTree(_ver)
ORDER BY (account_id, trade_date);

-- ---------------------------------------------------------------
-- 7) sim_order 模拟委托（CK RMT）
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sim_order
(
    _ver            DateTime MATERIALIZED now(),
    order_id        String,
    plan_id         String,
    ts_code         String,
    side            Enum8('BUY'=0, 'SELL'=1),
    price           Float64,
    qty             Float64,
    status          Enum8('PENDING'=0, 'FILLED'=1, 'CANCELLED'=2, 'REJECTED'=3),
    strategy_id     String,
    stage_at_entry  String,
    capital_confirm Enum8('NONE'=0, 'CONFIRM'=1, 'FILTER_PASS'=2, 'CONTRA'=3),
    create_time     DateTime64(3),
    decision_id     String
)
ENGINE = ReplacingMergeTree(_ver)
ORDER BY order_id;

-- ---------------------------------------------------------------
-- 8) sim_trade 模拟成交（CK RMT，★核心统计表）
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sim_trade
(
    _ver            DateTime MATERIALIZED now(),
    trade_id        String,
    order_id        String,
    plan_id         String,
    ts_code         String,
    stock_name      String,
    board_code      String,
    role            String,
    side            Enum8('BUY'=0, 'SELL'=1),
    price           Float64,
    qty             Float64,
    amount          Float64,
    trade_time      DateTime64(3),
    strategy_id     String,                                 -- ★ 战法口径
    stage_at_entry  String,                                 -- ★ 入场情绪阶段码
    capital_confirm Enum8('NONE'=0, 'CONFIRM'=1, 'FILTER_PASS'=2, 'CONTRA'=3), -- ★ 资金层口径
    entry_reason    String,
    exit_reason     Nullable(String),
    planned_action  String,
    pnl              Nullable(Float64),
    d1_ret          Nullable(Float64),
    d5_ret          Nullable(Float64),
    is_trap         Nullable(UInt8)
)
ENGINE = ReplacingMergeTree(_ver)
ORDER BY trade_id;

-- ---------------------------------------------------------------
-- 9) sim_position 模拟持仓（CK RMT）
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sim_position
(
    _ver                DateTime MATERIALIZED now(),
    position_id         String,
    ts_code             String,
    qty                 Float64,
    avg_cost            Float64,
    entry_date          Date,
    entry_strategy_id   String,
    entry_stage         String,
    entry_capital_confirm Enum8('NONE'=0, 'CONFIRM'=1, 'FILTER_PASS'=2, 'CONTRA'=3),
    last_val_date       Date
)
ENGINE = ReplacingMergeTree(_ver)
ORDER BY position_id;

-- ---------------------------------------------------------------
-- 10) decision_log 决策流（CK RMT，每步决策建议）
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS decision_log
(
    _ver            DateTime MATERIALIZED now(),
    decision_id     String,
    ts              DateTime64(3),
    ts_code         String,
    context_json    String,
    action          Enum8('BUY'=0, 'SELL'=1, 'HOLD'=2, 'WATCH'=3),
    score           Float64,
    risk_level      String,
    reference_price Nullable(Float64),
    reason          String,
    strategy_id     String,
    stage           String,
    capital_signal  String,
    executed        UInt8,
    order_id        Nullable(String)
)
ENGINE = ReplacingMergeTree(_ver)
ORDER BY decision_id;

-- ---------------------------------------------------------------
-- 11) exp_log 经验反馈库（CK RMT，★数据底座，供未来量化 agent 消费）
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exp_log
(
    _ver            DateTime MATERIALIZED now(),
    exp_id          String,
    trade_id        String,
    trade_date      Date,
    ts_code         String,
    strategy_id     String,                                 -- ★
    stage           String,                                 -- ★
    capital_confirm Enum8('NONE'=0, 'CONFIRM'=1, 'FILTER_PASS'=2, 'CONTRA'=3), -- ★
    direction       String,
    role            String,
    entry_features_json String,
    plan_match      UInt8,
    outcome_pnl     Float64,
    d1_ret          Float64,
    d5_ret          Float64,
    is_trap         UInt8,
    lesson          String,
    feedback_tag    Array(String),
    consumed_by_agent UInt8
)
ENGINE = ReplacingMergeTree(_ver)
ORDER BY exp_id;

-- ===============================================================
-- 多口径统计范式（后期收窄策略用）：
-- SELECT strategy_id, stage_at_entry, capital_confirm,
--        count() AS n,
--        sum(pnl>0 ? 1 : 0)/count() AS win_rate,
--        avg(pnl) AS avg_pnl
-- FROM sim_trade FINAL
-- WHERE side='SELL' AND pnl IS NOT NULL
-- GROUP BY strategy_id, stage_at_entry, capital_confirm
-- ORDER BY win_rate DESC, n DESC;
--
-- 回测源头归因（重建某笔模拟买入当时的真实逐笔）：
-- SELECT * FROM rt_tick_archive
-- WHERE ts_code = 'xxxxxx' AND ts BETWEEN trade_time - 60 AND trade_time + 5
-- ORDER BY ts;
-- ===============================================================
