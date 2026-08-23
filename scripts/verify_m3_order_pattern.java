/**
 * M3 拆单识别验证脚本（独立 main，不依赖 JUnit，适配离线构建）。
 *
 * 运行方式（在 replay-backend 目录，JDK21）：
 *   export JAVA_HOME=/Users/null/environment/JDK/jdk21/Contents/Home
 *   mvn -o dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -q
 *   CP="$(pwd)/target/classes:$(cat /tmp/cp.txt)"
 *   $JAVA_HOME/bin/javac -proc:none -cp "$CP" -d /tmp scripts/verify_m3_order_pattern.java
 *   $JAVA_HOME/bin/java -ea -cp "/tmp:$CP" verify_m3_order_pattern
 *
 * 覆盖：拆单吸筹(STEALTH) / 扫单(SWEEP) / 对敲(SELF_TRADE) / 正常(NORMAL)。
 */
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dunwugudao.replay.realtime.model.Tick;
import com.dunwugudao.replay.realtime.OrderPatternAnalyzer;
import com.dunwugudao.replay.realtime.OrderPatternAnalyzer.Config;
import com.dunwugudao.replay.realtime.OrderPatternAnalyzer.Result;

import java.util.ArrayList;
import java.util.List;

public class verify_m3_order_pattern {
    static final ObjectMapper M = new ObjectMapper();

    static Tick tick(long ts, String d, int volHands, double price) throws Exception {
        double amt = volHands * 100 * price;
        String json = String.format(
                "{\"tsCode\":\"600519\",\"t\":%d,\"p\":%s,\"v\":%d,\"d\":\"%s\",\"a\":%s}",
                ts, price, volHands, d, amt);
        return M.readValue(json, Tick.class);
    }

    public static void main(String[] args) throws Exception {
        Config cfg = new Config(30, 10000, 10, 200, 0.15, 500000);
        long now = 1_700_000_000_000L;

        // 1. 拆单吸筹：价格平稳 + 密集主买小单累计破万手
        List<Tick> stealth = new ArrayList<>();
        for (int i = 0; i < 60; i++) stealth.add(tick(now - 20_000 + i * 300, "B", 200, 18.5));
        Result r1 = OrderPatternAnalyzer.analyze(stealth, now - 60_000, now, cfg);
        System.out.println("[STEALTH] net=" + r1.stealthNetBuy() + " pattern=" + r1.orderPattern());
        assert r1.stealthNetBuy() > 0;
        assert r1.orderPattern().equals("STEALTH") : "STEALTH got " + r1.orderPattern();

        // 2. 扫单：价格上行 + 密集主买
        List<Tick> sweep = new ArrayList<>();
        for (int i = 0; i < 40; i++) sweep.add(tick(now - 8_000 + i * 150, "B", 500, 18.5 + i * 0.0125));
        sweep.add(tick(now - 1_000, "S", 50, 19.0));
        Result r2 = OrderPatternAnalyzer.analyze(sweep, now - 60_000, now, cfg);
        System.out.println("[SWEEP] density=" + r2.sweepDensity() + " pattern=" + r2.orderPattern());
        assert r2.sweepDensity() > 0.6 : "SWEEP density got " + r2.sweepDensity();
        assert r2.orderPattern().equals("SWEEP") : "SWEEP got " + r2.orderPattern();

        // 3. 对敲：±200ms 反向成对
        List<Tick> self = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long base = now - 5_000 + i * 1000;
            self.add(tick(base, "B", 300, 18.5));
            self.add(tick(base + 50, "S", 305, 18.5));
        }
        Result r3 = OrderPatternAnalyzer.analyze(self, now - 60_000, now, cfg);
        System.out.println("[SELF_TRADE] ratio=" + r3.selfTradeRatio() + " pattern=" + r3.orderPattern());
        assert r3.selfTradeRatio() > 0.3 : "SELF_TRADE ratio got " + r3.selfTradeRatio();
        assert r3.orderPattern().equals("SELF_TRADE") : "SELF_TRADE got " + r3.orderPattern();

        // 4. 正常
        List<Tick> normal = new ArrayList<>();
        normal.add(tick(now - 30_000, "B", 100, 18.5));
        normal.add(tick(now - 20_000, "S", 100, 18.5));
        normal.add(tick(now - 10_000, "B", 100, 18.5));
        Result r4 = OrderPatternAnalyzer.analyze(normal, now - 60_000, now, cfg);
        System.out.println("[NORMAL] pattern=" + r4.orderPattern());
        assert r4.orderPattern().equals("NORMAL") : "NORMAL got " + r4.orderPattern();

        System.out.println("ALL M3 ASSERTIONS PASSED ✅");
    }
}
