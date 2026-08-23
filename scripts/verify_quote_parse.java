/**
 * Quote tb10 解析 + Tick 价格回贴 验证脚本（独立 main，不依赖 JUnit，适配离线构建）。
 *
 * 运行方式（在 replay-backend 目录，JDK21）：
 *   export JAVA_HOME=/Users/null/environment/JDK/jdk21/Contents/Home
 *   CP="$(pwd)/target/classes:$(cat /tmp/cp.txt)"
 *   $JAVA_HOME/bin/javac -proc:none -cp "$CP" -d /tmp scripts/verify_quote_parse.java
 *   $JAVA_HOME/bin/java -ea -cp "/tmp:$CP" verify_quote_parse
 *
 * 覆盖：① Hash 原始串 → Quote 映射 + parseTb10 回填语义字段；② JiTu tick 价格回贴。
 */
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dunwugudao.replay.realtime.model.Quote;
import com.dunwugudao.replay.realtime.model.Tick;

public class verify_quote_parse {
    static final ObjectMapper M = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        // 1. Hash 原始串（REDIS_DATA_FORMAT.md 示例）映射 + parseTb10
        //    约定 tb10_prices = [最新价, 买一, 卖一, ...]，tb10_volumes = [买一量, 卖一量, ...]
        String hashJson = "{\"code\":\"002384\",\"tb10_prices\":\"201.19,201.0,201.5\","
                + "\"tb10_volumes\":\"100,23500,2405\",\"tb10_count\":\"41\",\"tb10_data_len\":\"397\","
                + "\"timestamp\":\"2972614\",\"update_time\":\"2026-08-23 14:30:34\"}";
        Quote q = M.readValue(hashJson, Quote.class);
        q.parseTb10();
        System.out.println("[QUOTE] last=" + q.getLastPrice() + " bid1P=" + q.getBid1P()
                + " ask1P=" + q.getAsk1P() + " bid1V=" + q.getBid1V() + " ask1V=" + q.getAsk1V());
        assert q.getLastPrice() == 201.19 : "lastPrice got " + q.getLastPrice();
        assert q.getBid1P() == 201.0 : "bid1P got " + q.getBid1P();
        assert q.getAsk1P() == 201.5 : "ask1P got " + q.getAsk1P();
        assert q.getBid1V() == 23500 : "bid1V got " + q.getBid1V();
        assert q.getAsk1V() == 2405 : "ask1V got " + q.getAsk1V();

        // 2. JiTu tick 价格回贴（tick 无价，从 quote.lastPrice 回贴）
        String tickJson = "{\"tsCode\":\"002384\",\"t\":605000,\"p\":0.0,\"v\":0,\"d\":\"B\",\"a\":0.0}";
        Tick t = M.readValue(tickJson, Tick.class);
        assert t.isPriceMissing() : "tick should be price-missing";
        if (t.isPriceMissing() && q.getLastPrice() > 0) {
            t.setPrice(q.getLastPrice());
        }
        System.out.println("[TICK] price after patch=" + t.getPrice() + " dir=" + t.getDirection());
        assert t.getPrice() == 201.19 : "patched price got " + t.getPrice();
        assert t.directionSign() == 1 : "direction sign got " + t.directionSign();

        // 3. 拆分模式：爬虫直接给语义字段，parseTb10 不覆盖
        String splitJson = "{\"code\":\"600519\",\"last_price\":1680.0,\"bid1_p\":1679.5,\"bid1_v\":1200,\"ask1_p\":1680.5,\"ask1_v\":800}";
        Quote q2 = M.readValue(splitJson, Quote.class);
        q2.parseTb10();
        assert q2.getLastPrice() == 1680.0 : "split lastPrice got " + q2.getLastPrice();
        assert q2.getBid1V() == 1200 : "split bid1V got " + q2.getBid1V();
        System.out.println("[QUOTE_SPLIT] last=" + q2.getLastPrice() + " bid1V=" + q2.getBid1V());

        System.out.println("ALL QUOTE PARSE ASSERTIONS PASSED ✅");
    }
}
