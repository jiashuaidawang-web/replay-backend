package com.dunwugudao.replay.realtime.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * tick 时间字段反序列化：兼容同花顺契约两种格式。
 * <ul>
 *   <li>unix epoch millis（long，如 1724396100000）→ 直接用；</li>
 *   <li>HHMMSSmmm（如 103000500 = 10:30:00.500）→ 结合"今天"换算成今日绝对毫秒。</li>
 * </ul>
 * 判据：数值 >= 1e12 视为 epoch millis；否则按 HHMMSSmmm 解析（不足 9 位左补零到毫秒精度）。
 */
public class TickTimeDeserializer extends JsonDeserializer<Long> {

    private static final long EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L; // 2001-09 之后

    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String raw = p.getValueAsString();
        if (raw == null) {
            return 0L;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return 0L;
        }
        try {
            long v = Long.parseLong(s);
            if (v >= EPOCH_MILLIS_THRESHOLD) {
                return v; // epoch millis
            }
            // HHMMSSmmm：补齐到 9 位（时2分2秒2毫秒3）
            String padded = String.format("%09d", v);
            int hh = Integer.parseInt(padded.substring(0, 2));
            int mm = Integer.parseInt(padded.substring(2, 4));
            int ss = Integer.parseInt(padded.substring(4, 6));
            int millis = Integer.parseInt(padded.substring(6, 9));
            LocalTime lt = LocalTime.of(hh, mm, ss, millis * 1_000_000);
            long todayMs = lt.atDate(java.time.LocalDate.now())
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            return todayMs;
        } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
            // 无法解析则回退 0（由窗口按 cutoff 自然淘汰）
            return 0L;
        }
    }
}
