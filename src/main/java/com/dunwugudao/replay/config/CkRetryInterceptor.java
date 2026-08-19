package com.dunwugudao.replay.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Properties;

/**
 * ClickHouse 读路径瞬态重试（2026-08-19 新增，根治 API 偶发 500）。
 *
 * <p>背景：Windows CK 网络抖动时，JDBC 查询偶发 {@code BatchUpdateException / Connection is closed /
 * The target server failed to respond}，此前 {@code safeStep} 只保护计算层（ReplayCalcJob）读取，
 * API 读路径（Controller → Service → Mapper）无重试 → 前端偶发 500（实测 leaders 3 轮 1 次）。
 *
 * <p>本插件只挂在 ck SqlSessionFactory（见 {@link CkMybatisConfig}），只拦 {@code Executor.query}：
 * <ul>
 *   <li>捕获 SQLException 系瞬态失败 → 重试（最多 4 次，共 5 次尝试），退避 0.5s/1s/2s/4s；
 *       实测 Windows CK 抖动可持续数秒，3 次尝试窗口不够，5 次覆盖绝大多数场景；</li>
 *   <li>重试前 softEvict 连接池（剔除被服务端断开的死连接，配合 Hikari test-query 自愈）；</li>
 *   <li><b>不拦 update</b>：写入已全部走 {@code CkHttpWriter} 裸 HTTP 自愈路径，保守不重复执行。</li>
 * </ul>
 */
@Slf4j
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class})
})
public class CkRetryInterceptor implements Interceptor {

    private static final int MAX_RETRY = 4;          // 额外重试次数（共 5 次尝试）
    private static final long[] BACKOFF_MS = {500L, 1000L, 2000L, 4000L};

    /** 瞬态失败关键词（命中即重试；业务 SQL 错误不命中，直接上抛）。 */
    private static final String[] TRANSIENT_KEYWORDS = {
            "failed to respond", "connection is closed", "socketexception",
            "connection reset", "read timed out", "timed out", "broken pipe",
            "communicationslinkfailure", "eofexception", "connection refused"
    };

    private final HikariDataSource ckDataSource;

    public CkRetryInterceptor(@Qualifier("ckDataSource") DataSource ckDataSource) {
        this.ckDataSource = (ckDataSource instanceof HikariDataSource) ? (HikariDataSource) ckDataSource : null;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Throwable last = null;
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            try {
                return invocation.proceed();
            } catch (Throwable t) {
                // 仅对"cause 链含 SQLException 且消息命中瞬态关键词"的失败重试；
                // 其余（业务/语法错误等）直接上抛。注意：MyBatis 抛 PersistenceException、
                // MyBatis-Spring 再翻成 DataAccessException，故必须沿 cause 链判断。
                if (!isTransientSql(t)) {
                    throw t;
                }
                last = t;
                if (attempt == MAX_RETRY) {
                    break;
                }
                evictConnections();
                log.warn("CK 读路径瞬态失败(重试 {}/{}): {} | {}", attempt + 1, MAX_RETRY,
                        t.getClass().getSimpleName(), firstMsg(t));
                try {
                    Thread.sleep(BACKOFF_MS[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    /** cause 链中是否存在"命中瞬态关键词的 SQLException"。 */
    private boolean isTransientSql(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof java.sql.SQLException) {
                String m = cur.getMessage();
                if (m != null) {
                    String lm = m.toLowerCase(java.util.Locale.ROOT);
                    for (String k : TRANSIENT_KEYWORDS) {
                        if (lm.contains(k)) {
                            return true;
                        }
                    }
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 剔除连接池中的死连接（Hikari 会重建），消除"Connection is closed"类持续失败。 */
    private void evictConnections() {
        try {
            if (ckDataSource != null && ckDataSource.getHikariPoolMXBean() != null) {
                ckDataSource.getHikariPoolMXBean().softEvictConnections();
            }
        } catch (Exception ex) {
            log.debug("softEvict 连接池失败（忽略）: {}", ex.getMessage());
        }
    }

    private String firstMsg(Throwable e) {
        // 取 cause 链最深层消息（InvocationTargetException 自身消息固定为类名，无诊断价值）
        Throwable cur = e;
        String lastMsg = null;
        while (cur != null) {
            String m = cur.getMessage();
            if (m != null && !m.equals(cur.getClass().getSimpleName())) {
                lastMsg = m;
            }
            cur = cur.getCause();
        }
        String m = lastMsg != null ? lastMsg : e.getMessage();
        return m != null ? (m.length() > 160 ? m.substring(0, 160) : m) : e.getClass().getSimpleName();
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // no-op
    }
}
