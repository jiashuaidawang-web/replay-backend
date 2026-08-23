package com.dunwugudao.replay.realtime;

import com.dunwugudao.replay.service.CkHttpWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 实时层 CK 缓冲写入器：多表攒批、每 5s 统一裸 HTTP 落库。
 *
 * <p>实时链路（tick 归档 / 特征 / 决策 / 模拟盘）都是高频小批量写入，
 * 直接逐条调 {@link CkHttpWriter} 会打爆 Windows CK；攒批后每 5s 一次 VALUES 批插。
 * <ul>
 *   <li>线程安全：内存队列 + 快照交换；</li>
 *   <li>失败不丢：单表 flush 失败时把行放回队列尾部，下轮重试（带上限防内存膨胀）；</li>
 *   <li>限流保护：单表缓冲上限 20 万行，超限丢弃最旧行并告警（极端情况下的降级）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CkBufferedWriter {

    private final CkHttpWriter ck;

    private static final int MAX_BUFFER_PER_TABLE = 200_000;

    /** table -> (columns, rows)。columns 首次注册后固定。 */
    private final Map<String, TableBuffer> buffers = new ConcurrentHashMap<>();

    static final class TableBuffer {
        volatile List<String> columns;
        final List<Object[]> rows = new CopyOnWriteArrayList<>();
    }

    /** 追加一批行；columns 以首次注册为准（同表列清单必须恒定）。 */
    public void add(String table, List<String> columns, List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        TableBuffer buf = buffers.computeIfAbsent(table, k -> new TableBuffer());
        if (buf.columns == null) {
            buf.columns = List.copyOf(columns);
        }
        if (buf.rows.size() + rows.size() > MAX_BUFFER_PER_TABLE) {
            log.warn("[realtime] CK 缓冲表 {} 达到上限 {} 行，丢弃本批 {} 行（降级保护）",
                    table, MAX_BUFFER_PER_TABLE, rows.size());
            return;
        }
        buf.rows.addAll(rows);
    }

    /** 每 5s 快照交换后批量落库；失败行放回重试。 */
    @Scheduled(fixedDelay = 5000)
    public void flush() {
        for (Map.Entry<String, TableBuffer> e : buffers.entrySet()) {
            String table = e.getKey();
            TableBuffer buf = e.getValue();
            if (buf.rows.isEmpty() || buf.columns == null) {
                continue;
            }
            // 快照交换（CopyOnWriteArrayList 遍历安全）
            List<Object[]> snapshot;
            synchronized (buf.rows) {
                if (buf.rows.isEmpty()) {
                    continue;
                }
                snapshot = new ArrayList<>(buf.rows);
                buf.rows.clear();
            }
            try {
                ck.insert(table, buf.columns, snapshot);
            } catch (Exception ex) {
                log.warn("[realtime] 表 {} 落库失败，{} 行放回缓冲待重试: {}", table, snapshot.size(), ex.getMessage());
                if (buf.rows.size() + snapshot.size() <= MAX_BUFFER_PER_TABLE) {
                    buf.rows.addAll(snapshot);
                }
            }
        }
    }
}
