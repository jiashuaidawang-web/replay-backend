-- ============================================================================
-- replay-backend 分布式任务行记录表（仅 openGauss）
-- 设计：计算任务按「交易日 × 计算类型」拆成可独立认领的单元，多 worker 抢任务时
--       通过 replay_calc_task 的乐观锁（UPDATE ... WHERE status=0）互斥认领，
--       实现幂等重算与宕机重投。业务数据 / 分析数据不在此库。
-- ============================================================================

CREATE TABLE IF NOT EXISTS replay_calc_task (
    id          BIGSERIAL PRIMARY KEY,
    trade_date  DATE        NOT NULL,
    calc_type   VARCHAR(32) NOT NULL,
    status      SMALLINT    NOT NULL DEFAULT 0,   -- 0 待计算 / 1 计算中 / 2 完成 / 3 失败
    node        VARCHAR(64),                      -- 认领节点 host:pid
    attempt     INTEGER     NOT NULL DEFAULT 0,   -- 已尝试次数
    last_error  TEXT,                             -- 失败原因
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT now(),
    UNIQUE (trade_date, calc_type)
);

CREATE INDEX IF NOT EXISTS idx_replay_calc_task_status
    ON replay_calc_task (status, trade_date);
