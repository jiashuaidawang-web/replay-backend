package com.dunwugudao.replay.realtime;

import com.dunwugudao.replay.realtime.model.Tick;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 逐笔归档执行器：把采集线程攒下的 tick 批量转行、异步写入 {@code rt_tick_archive}（回测源头）。
 *
 * <p>与采集线程解耦：{@link RedisStreamConsumer} 只投递 List，本类在独立单线程执行器里
 * 做 JSON→行转换 + 缓冲落库（{@link CkBufferedWriter}），HTTP/CK 抖动不阻塞采集。
 */
@Slf4j
@Component
public class TickArchiver {

    private static final List<String> ARCHIVE_COLUMNS = List.of(
            "trade_date", "ts_code", "ts", "price", "volume", "amount", "direction");

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final CkBufferedWriter bufferedWriter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tick-archiver");
        t.setDaemon(true);
        return t;
    });

    public TickArchiver(CkBufferedWriter bufferedWriter) {
        this.bufferedWriter = bufferedWriter;
    }

    /** 异步归档一批逐笔（非阻塞）。 */
    public void archive(List<Tick> ticks) {
        if (ticks == null || ticks.isEmpty()) {
            return;
        }
        final List<Tick> copy = new ArrayList<>(ticks);
        executor.execute(() -> {
            try {
                bufferedWriter.add("rt_tick_archive", ARCHIVE_COLUMNS, toRows(copy));
            } catch (Exception e) {
                log.warn("[realtime] tick 归档转换失败（本批 {} 条不入库）: {}", copy.size(), e.getMessage());
            }
        });
    }

    private static List<Object[]> toRows(List<Tick> ticks) {
        List<Object[]> rows = new ArrayList<>(ticks.size());
        for (Tick t : ticks) {
            if (t == null || t.getTsCode() == null) {
                continue;
            }
            Instant inst = Instant.ofEpochMilli(t.getTs());
            LocalDate d = inst.atZone(ZoneId.systemDefault()).toLocalDate();
            String tsStr = inst.atZone(ZoneId.systemDefault()).toLocalDateTime().format(TS_FMT);
            rows.add(new Object[]{d, t.getTsCode(), tsStr, t.getPrice(), t.getVolume(), t.getAmount(), t.getDirection()});
        }
        return rows;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
