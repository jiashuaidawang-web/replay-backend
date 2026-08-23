package com.dunwugudao.replay.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * ClickHouse 裸 HTTP 直写（绕过 JDBC 静默丢 + 连接池中毒）。
 *
 * <p>Windows CK 对 JDBC 连接（keep-alive 关闭 / 网络级间歇不可达）极不可靠：批量 INSERT 常出现
 * 「驱动层返回成功、服务端未提交」的静默丢，且无异常可捕获；Mac 直连更会第 2 条语句报 Connection is closed。
 * 实测裸 HTTP（每请求新建连接、urllib/curl 直写）100% 可靠。故所有计算层写入与写后校验改走本服务。
 *
 * <p>写入：VALUES 分块（每批 100 行）+ 6 次指数退避重试；校验：服务端真实行数（FINAL count）。
 */
@Slf4j
@Service
public class CkHttpWriter {

    @Value("${replay.ck.http-base-url:http://100.97.74.45:8123/?user=default&password=pamirs@123&database=crawler}")
    private String httpBaseUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** 裸 HTTP 直写（VALUES 分块 + 重试）。rows 中每个 Object[] 必须与 columns 顺序/数量一致。 */
    public void insert(String table, List<String> columns, List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        StringBuilder colSql = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                colSql.append(", ");
            }
            colSql.append(columns.get(i));
        }
        final int chunk = 100;
        for (int start = 0; start < rows.size(); start += chunk) {
            int end = Math.min(rows.size(), start + chunk);
            List<Object[]> part = rows.subList(start, end);
            StringBuilder valSql = new StringBuilder();
            for (int r = 0; r < part.size(); r++) {
                if (r > 0) {
                    valSql.append(", ");
                }
                Object[] row = part.get(r);
                valSql.append("(");
                for (int c = 0; c < row.length; c++) {
                    if (c > 0) {
                        valSql.append(", ");
                    }
                    valSql.append(formatVal(row[c]));
                }
                valSql.append(")");
            }
            String sql = "INSERT INTO " + table + " (" + colSql + ") VALUES " + valSql;
            execWithRetry(sql, table);
        }
    }

    private void execWithRetry(String sql, String table) {
        final int max = 6;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(httpBaseUrl))
                        .header("Content-Type", "text/plain; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(60)).build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return;
                }
                throw new RuntimeException("CK HTTP status " + resp.statusCode() + " body=" + resp.body());
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                log.warn("CK HTTP 写入失败(重试 {}/{}): {} -> {}", attempt, max, table, msg);
                if (attempt == max) {
                    log.error("CK HTTP 写入最终失败: {}（数据未落库，需人工排查 Windows CK）", table);
                    throw new RuntimeException("CK HTTP insert failed after retry: " + table, e);
                }
                try {
                    Thread.sleep(3000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 服务端真实行数（FINAL），用于写后校验——以服务端为准，杜绝静默丢漏判。 */
    public long count(String table, LocalDate date) {
        String sql = "SELECT count() FROM " + table + " FINAL WHERE trade_date = '" + date + "' FORMAT JSONEachRow";
        final int max = 5;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(httpBaseUrl))
                        .header("Content-Type", "text/plain; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(30)).build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    String body = resp.body();
                    int c1 = body.indexOf(':');
                    int c2 = body.indexOf('}', c1);
                    if (c1 > 0 && c2 > c1) {
                        String num = body.substring(c1 + 1, c2).replace("\"", "").trim();
                        return Long.parseLong(num);
                    }
                    return 0L;
                }
                throw new RuntimeException("CK HTTP status " + resp.statusCode());
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                if (attempt == max) {
                    log.warn("CK HTTP count 失败(按 0 处理，将触发重跑): {} -> {}", table, msg);
                    return 0L;
                }
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return 0L;
                }
            }
        }
        return 0L;
    }

    private String formatVal(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof LocalDate) {
            return "'" + v.toString() + "'";
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).toPlainString();
        }
        if (v instanceof Number) {
            return v.toString();
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? "1" : "0";
        }
        if (v instanceof List<?> list) {
            // CK Array(String) 字面量：['a','b']（元素按字符串处理，转义单引号）
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                Object el = list.get(i);
                String s = el == null ? "" : el.toString();
                sb.append("'").append(s.replace("\\", "\\\\").replace("'", "''")).append("'");
            }
            return sb.append("]").toString();
        }
        String s = v.toString();
        // 单引号/反斜杠转义，避免破坏 SQL
        return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'";
    }
}
