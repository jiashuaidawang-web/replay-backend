package com.dunwugudao.replay.realtime.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * 方向字段反序列化：兼容同花顺契约 {@code B/S} 字符与 M1 旧口径 {@code 0/1/2} 数字串。
 * <pre>
 *   B / 0       → BUY
 *   S / 1       → SELL
 *   2 / 其它/null → NEUTRAL
 * </pre>
 */
public class DirectionDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String raw = p.getValueAsString();
        if (raw == null) {
            return "NEUTRAL";
        }
        String s = raw.trim().toUpperCase();
        return switch (s) {
            case "B", "0" -> "BUY";
            case "S", "1" -> "SELL";
            default -> "NEUTRAL";
        };
    }
}
