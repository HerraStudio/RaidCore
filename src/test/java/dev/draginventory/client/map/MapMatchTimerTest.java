package dev.draginventory.client.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MapMatchTimer} 纯 JVM 单测（v2.5.5 真实倒计时重做）：
 * 时长解析三写法 / 格式化 / 倒计时数学 / Provider 优先级 / 关停语义。
 * 静态状态在 @BeforeEach 显式复位（register(null) + stop + 默认时长还原）。
 */
class MapMatchTimerTest {

    @BeforeEach
    void resetStaticState() {
        MapMatchTimer.register(null);
        MapMatchTimer.stop();
        MapMatchTimer.setDefaultDuration(MapMatchTimer.DEFAULT_DURATION_SECONDS);
    }

    // ==================== parseSeconds：冒号式 ====================

    @Test
    void parseColonStyle() {
        assertEquals(1500, MapMatchTimer.parseSeconds("25:00"));   // 25 分
        assertEquals(5400, MapMatchTimer.parseSeconds("1:30:00")); // 1 时 30 分
        assertEquals(30, MapMatchTimer.parseSeconds("0:30"));      // 30 秒
        assertEquals(60, MapMatchTimer.parseSeconds("1:00"));
    }

    // ==================== parseSeconds：后缀式 ====================

    @Test
    void parseSuffixStyle() {
        assertEquals(1800, MapMatchTimer.parseSeconds("30m"));
        assertEquals(90, MapMatchTimer.parseSeconds("90s"));
        assertEquals(7200, MapMatchTimer.parseSeconds("2h"));
        assertEquals(5400, MapMatchTimer.parseSeconds("1h30m"));
        assertEquals(5415, MapMatchTimer.parseSeconds("1h30m15s"));
        assertEquals(1800, MapMatchTimer.parseSeconds("30M")); // 大小写不敏感
    }

    // ==================== parseSeconds：纯数字 = 分钟 ====================

    @Test
    void parsePlainNumberIsMinutes() {
        assertEquals(1500, MapMatchTimer.parseSeconds("25"));  // 25 分钟
        assertEquals(5400, MapMatchTimer.parseSeconds("90"));
        assertEquals(60, MapMatchTimer.parseSeconds("1"));
    }

    // ==================== parseSeconds：非法输入 ====================

    @Test
    void parseRejectsGarbage() {
        for (String bad : new String[] {"", "   ", "abc", "-5", "1x", "m30", "25:60:60:60", "0", "0:00", "0h0m0s"}) {
            assertThrows(IllegalArgumentException.class, () -> MapMatchTimer.parseSeconds(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> MapMatchTimer.parseSeconds(null));
    }

    @Test
    void parseRejectsOver24h() {
        assertThrows(IllegalArgumentException.class, () -> MapMatchTimer.parseSeconds("1441"));  // 1441 分钟
        assertThrows(IllegalArgumentException.class, () -> MapMatchTimer.parseSeconds("25h"));
        assertEquals(86_400, MapMatchTimer.parseSeconds("1440")); // 24h 整恰为上限
        assertEquals(86_400, MapMatchTimer.parseSeconds("24h"));
    }

    // ==================== formatSeconds ====================

    @Test
    void formatsMinuteSecond() {
        assertEquals("0:00", MapMatchTimer.formatSeconds(0));
        assertEquals("0:30", MapMatchTimer.formatSeconds(30));
        assertEquals("25:00", MapMatchTimer.formatSeconds(1500));
        assertEquals("61:05", MapMatchTimer.formatSeconds(3665)); // 分钟溢出不进位 h（旧口径）
        assertEquals("0:00", MapMatchTimer.formatSeconds(-5));    // 负数夹 0
    }

    // ==================== 倒计时数学 ====================

    @Test
    void countdownTicksDownAndClampsAtZero() throws InterruptedException {
        MapMatchTimer.start(2);
        assertTrue(MapMatchTimer.isRunning());
        long r1 = MapMatchTimer.remainingSeconds();
        assertTrue(r1 == 2 || r1 == 1, "fresh start should read 2 (or 1 after a tick), got " + r1);
        Thread.sleep(2100);
        assertEquals(0, MapMatchTimer.remainingSeconds()); // 归零后稳定 0，不出现负数
        MapMatchTimer.start(-7); // 非法时长夹为 1 秒（不抛异常：手动对表宽容）
        Thread.sleep(1100);
        assertEquals(0, MapMatchTimer.remainingSeconds());
    }

    @Test
    void stoppedReturnsMinusOneAndHides() {
        assertEquals(-1, MapMatchTimer.remainingSeconds()); // @BeforeEach 已 stop
        MapMatchTimer.start(60);
        assertTrue(MapMatchTimer.remainingSeconds() >= 59);
        MapMatchTimer.stop();
        assertFalse(MapMatchTimer.isRunning());
        assertEquals(-1, MapMatchTimer.remainingSeconds());
    }

    @Test
    void restartDefaultUsesConfiguredDefault() {
        MapMatchTimer.setDefaultDuration(120);
        MapMatchTimer.restartDefault();
        long r = MapMatchTimer.remainingSeconds();
        assertTrue(r == 120 || r == 119, "restartDefault should read new default, got " + r);
        // 默认时长本身被夹在 [1, 24h]。
        MapMatchTimer.setDefaultDuration(0);
        assertEquals(1, MapMatchTimer.defaultDuration());
        MapMatchTimer.setDefaultDuration(1_000_000);
        assertEquals(86_400, MapMatchTimer.defaultDuration());
    }

    // ==================== Provider 优先级 ====================

    @Test
    void providerOverridesEverything() {
        MapMatchTimer.start(600);                                // 手动倒计时进行中
        MapMatchTimer.register(() -> 42);                        // Provider 注册后接管
        assertEquals(42, MapMatchTimer.remainingSeconds());
        MapMatchTimer.stop();                                    // 甚至 stop 也压不过 Provider
        assertEquals(42, MapMatchTimer.remainingSeconds());
        MapMatchTimer.register(null);                            // 注销回到手动模式
        assertEquals(-1, MapMatchTimer.remainingSeconds());
    }

    @Test
    void providerValueClampedToZero() {
        MapMatchTimer.register(() -> -5); // 对局系统提前结束/溢出：不显示负数
        assertEquals(0, MapMatchTimer.remainingSeconds());
    }

    // ==================== v2.5.7 联动扩展：加时 / 截止时刻 / Provider 自省 / 结束沿 ====================

    @Test
    void addSecondsShiftsDeadlineAndClampsAtZero() {
        MapMatchTimer.start(600);
        MapMatchTimer.addSeconds(30);
        long remaining = MapMatchTimer.remainingSeconds();
        assertTrue(remaining >= 628 && remaining <= 630, "600s + 30s ≈ 630，实际 " + remaining);
        MapMatchTimer.addSeconds(-100_000); // 减时越过剩余 → 立即归零（不出现负数）
        assertEquals(0, MapMatchTimer.remainingSeconds());
    }

    @Test
    void addSecondsNoOpWhenStoppedOrProviderOwned() {
        MapMatchTimer.addSeconds(30); // @BeforeEach 已 stop：无操作，仍 -1
        assertEquals(-1, MapMatchTimer.remainingSeconds());
        MapMatchTimer.start(600);
        MapMatchTimer.register(() -> 42);
        MapMatchTimer.addSeconds(30); // Provider 模式：时间归对局系统所有，无操作
        assertEquals(42, MapMatchTimer.remainingSeconds());
    }

    @Test
    void deadlineMillisReportsWhileRunningOnly() {
        assertEquals(0, MapMatchTimer.deadlineMillis()); // 未运行 → 0
        MapMatchTimer.start(600);
        long skew = MapMatchTimer.deadlineMillis() - System.currentTimeMillis();
        assertTrue(skew >= 599_000 && skew <= 601_000, "deadline ≈ now + 600s，实际偏移 " + skew + "ms");
        MapMatchTimer.register(() -> 42);
        assertEquals(0, MapMatchTimer.deadlineMillis()); // Provider 模式下手动截止无意义 → 0
    }

    @Test
    void hasProviderTracksRegistration() {
        assertFalse(MapMatchTimer.hasProvider());
        MapMatchTimer.register(() -> 1);
        assertTrue(MapMatchTimer.hasProvider());
        MapMatchTimer.register(null);
        assertFalse(MapMatchTimer.hasProvider());
    }

    @Test
    void endedBetweenDetectsFallToExactlyZero() {
        assertTrue(MapMatchTimer.endedBetween(5, 0));     // 正常走完 → 触发
        assertTrue(MapMatchTimer.endedBetween(1, 0));     // 最后一秒走完 → 触发
        assertFalse(MapMatchTimer.endedBetween(0, 0));    // 已在零位 → 不重复触发
        assertFalse(MapMatchTimer.endedBetween(5, -1));   // 停止（off）不是结束
        assertFalse(MapMatchTimer.endedBetween(-1, 0));   // 从未开始 → 不触发
        assertFalse(MapMatchTimer.endedBetween(0, 600));  // 重新开始（反向沿）不触发
        assertFalse(MapMatchTimer.endedBetween(600, 300)); // 正常倒数中不触发
    }

    // ==================== v2.6.1：归零时刻（endedAtMillis，结束字样停留后消失） ====================

    @Test
    void endedAtMillisLifecycle() throws InterruptedException {
        assertEquals(0L, MapMatchTimer.endedAtMillis());  // 未开始 → 0
        MapMatchTimer.start(1);
        MapMatchTimer.tickEndDetection();                 // 计时态：仍 0
        assertEquals(0L, MapMatchTimer.endedAtMillis());
        Thread.sleep(1100);                               // 1 秒走完
        MapMatchTimer.tickEndDetection();                 // 结束沿：记录归零时刻
        long endedAt = MapMatchTimer.endedAtMillis();
        assertTrue(endedAt > 0L, "fall-to-zero edge should record endedAtMillis");
        assertTrue(System.currentTimeMillis() - endedAt < 5_000L, "recorded just now");
        MapMatchTimer.tickEndDetection();                 // 已在零位：不重复记录（停留起点不变）
        assertEquals(endedAt, MapMatchTimer.endedAtMillis());
        MapMatchTimer.start(60);                          // 重新开始：复位（下次归零重新走停留）
        MapMatchTimer.tickEndDetection();
        assertEquals(0L, MapMatchTimer.endedAtMillis());
        MapMatchTimer.stop();                             // 停止（off）：一并复位
        assertEquals(0L, MapMatchTimer.endedAtMillis());
    }
}
