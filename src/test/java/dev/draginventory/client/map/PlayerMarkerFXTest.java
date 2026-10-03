package dev.draginventory.client.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/**
 * {@link PlayerMarkerFX} 纯 JVM 单测（v2.5.6 玩家图标动效）：
 * 角度环绕数学 / 指数逼近 / 呼吸 / 速度收敛 / 尾迹采样与衰减 / 传送重置 / 同帧去重。
 * 时钟注入假实现，逐毫秒推演；静态生产单例不在此触碰。
 */
class PlayerMarkerFXTest {

    /** 可手动拨动的假时钟。 */
    private static final class FakeClock implements LongSupplier {
        long t;
        @Override public long getAsLong() { return t; }
    }

    // ==================== 纯数学：angleDeltaDeg（最短弧） ====================

    @Test
    void angleDeltaBasic() {
        assertEquals(90f, PlayerMarkerFX.angleDeltaDeg(0, 90), 1e-4f);
        assertEquals(-90f, PlayerMarkerFX.angleDeltaDeg(0, 270), 1e-4f);  // 270 走最短弧 = -90
        assertEquals(-90f, PlayerMarkerFX.angleDeltaDeg(0, -90), 1e-4f);
    }

    @Test
    void angleDeltaWrapsZero() {
        assertEquals(20f, PlayerMarkerFX.angleDeltaDeg(350, 10), 1e-4f);  // 跨 0° 正向
        assertEquals(-20f, PlayerMarkerFX.angleDeltaDeg(10, 350), 1e-4f); // 跨 0° 反向
        assertEquals(180f, Math.abs(PlayerMarkerFX.angleDeltaDeg(0, 180)), 1e-4f); // 半圈两侧等价
    }

    // ==================== 纯数学：approach / approachAngle ====================

    @Test
    void approachZeroDtReturnsFrom() {
        assertEquals(3.0, PlayerMarkerFX.approach(3.0, 99.0, 0.0, 140.0), 1e-9);
        assertEquals(5f, PlayerMarkerFX.approachAngle(5f, 175f, 0.0, 140.0), 1e-4f);
    }

    @Test
    void approachOneTauReaches63Percent() {
        // dt = tau → 到达 1 - 1/e ≈ 63.21%
        assertEquals(8.0 * (1.0 - Math.exp(-1.0)), PlayerMarkerFX.approach(0.0, 8.0, 140.0, 140.0), 1e-9);
        assertEquals(90f * (1f - (float) Math.exp(-1.0)),
                PlayerMarkerFX.approachAngle(0f, 90f, 140.0, 140.0), 1e-3f);
    }

    @Test
    void approachAngleWrapsShortestArc() {
        // 350° → 10° 应走 +20°（跨 0°），大 dt 后落到 10° 附近
        float r = PlayerMarkerFX.approachAngle(350f, 10f, 2000.0, 140.0);
        assertEquals(10f, ((r % 360f) + 360f) % 360f, 0.5f);
    }

    // ==================== 纯数学：idleBreath ====================

    @Test
    void breathStaysWithinAmplitude() {
        for (long t = 0; t < PlayerMarkerFX.BREATH_PERIOD_MS * 2; t += 37) {
            float b = PlayerMarkerFX.idleBreath(t);
            assertTrue(b >= 1f - PlayerMarkerFX.BREATH_AMPLITUDE - 1e-4f, "t=" + t);
            assertTrue(b <= 1f + PlayerMarkerFX.BREATH_AMPLITUDE + 1e-4f, "t=" + t);
        }
        assertEquals(1f, PlayerMarkerFX.idleBreath(0), 1e-5f);            // 相位 0 = 1.0
        assertEquals(1.018f, PlayerMarkerFX.idleBreath(600), 1e-3f);      // 1/4 周期 = 峰值
    }

    // ==================== update：速度收敛 + 尾迹 ====================

    @Test
    void speedConvergesAndTrailAppears() {
        FakeClock clock = new FakeClock();
        PlayerMarkerFX fx = new PlayerMarkerFX(clock);

        // 首帧（t=0）：无基准，速度 0、尾迹空、朝向直接对齐。
        clock.t = 0;
        fx.update(0, 0, -90);
        assertEquals(0.0, fx.speed(), 1e-9);
        assertEquals(-90f, fx.smoothYaw(), 1e-4f);
        assertTrue(fx.trail().isEmpty());

        // 匀速 +X 5 格/秒（yaw -90 = 朝东/+X），每 100ms 前进 0.5 格，跑 1.2 秒。
        for (int i = 1; i <= 12; i++) {
            clock.t = i * 100L;
            fx.update(i * 0.5, 0, -90);
        }
        // tau=120ms（v2.5.7 从 140 调低）、已跑 1200ms：speedSmooth ≈ 5×(1-e^-10) ≈ 5。
        assertTrue(fx.speed() > 4.5, "speed=" + fx.speed());
        assertEquals(5.0 / PlayerMarkerFX.SPEED_REF, fx.stretch(), 0.05f);

        // 尾迹：两枚样本，远端在前；位置滞后于当前位置（身后拖尾）。
        List<PlayerMarkerFX.TrailSample> trail = fx.trail();
        assertEquals(2, trail.size());
        assertTrue(trail.get(0).alpha() > 0f && trail.get(0).alpha() < 0.31f, "far alpha=" + trail.get(0).alpha());
        assertTrue(trail.get(1).alpha() >= trail.get(0).alpha(), "near alpha 应不小于 far");
        assertTrue(trail.get(0).x() < trail.get(1).x() && trail.get(1).x() < 6.0,
                "样本应滞后: far=" + trail.get(0).x() + " near=" + trail.get(1).x() + " cur=6.0");
        assertTrue(trail.get(0).scale() < trail.get(1).scale() && trail.get(1).scale() < 1f,
                "残影应缩小递减");

        // 精确年龄推演：40ms 限速采样 + 100ms/帧 → 缓冲步长 100ms；
        // t=1200 时 near（age≥120）取 t=1000（x=5.0），far（age≥260）取 t=900（x=4.5）。
        assertEquals(5.0, trail.get(1).x(), 1e-9);
        assertEquals(4.5, trail.get(0).x(), 1e-9);
    }

    @Test
    void slowWalkBelowGateHasNoTrail() {
        FakeClock clock = new FakeClock();
        PlayerMarkerFX fx = new PlayerMarkerFX(clock);
        clock.t = 0;
        fx.update(0, 0, 0);
        // 1.0 格/秒 < SPEED_GATE=1.2：潜行级慢速不触发残影。
        for (int i = 1; i <= 30; i++) {
            clock.t = i * 100L;
            fx.update(i * 0.1, 0, 0);
        }
        assertEquals(1.0, fx.speed(), 0.15);
        assertTrue(fx.trail().isEmpty());
    }

    @Test
    void stoppingDecaysSpeedAndTrail() {
        FakeClock clock = new FakeClock();
        PlayerMarkerFX fx = new PlayerMarkerFX(clock);
        clock.t = 0;
        fx.update(0, 0, 0);
        for (int i = 1; i <= 12; i++) {
            clock.t = i * 100L;
            fx.update(i * 0.5, 0, 0);
        }
        assertTrue(fx.trail().size() == 2);
        // 原地停 5 秒：速度指数衰减过阈值，残影消失。
        for (int i = 13; i <= 62; i++) {
            clock.t = i * 100L;
            fx.update(6.0, 0, 0);
        }
        assertTrue(fx.speed() < PlayerMarkerFX.SPEED_GATE, "speed=" + fx.speed());
        assertTrue(fx.trail().isEmpty());
        assertEquals(0f, fx.stretch(), 1e-4f);
    }

    // ==================== update：传送 / 换世界 ====================

    @Test
    void teleportResetsYawSpeedAndTrail() {
        FakeClock clock = new FakeClock();
        PlayerMarkerFX fx = new PlayerMarkerFX(clock);
        clock.t = 0;
        fx.update(0, 0, 0);
        for (int i = 1; i <= 12; i++) {
            clock.t = i * 100L;
            fx.update(i * 0.5, 0, 0);
        }
        assertTrue(fx.trail().size() == 2);
        // 跳 1000 格（>64）：视为传送——速度清零、朝向跳变、尾迹断流。
        clock.t = 1300;
        fx.update(1000, 0, 45);
        assertEquals(0.0, fx.speed(), 1e-9);
        assertEquals(45f, fx.smoothYaw(), 1e-4f);
        assertTrue(fx.trail().isEmpty());
    }

    @Test
    void resetClearsEverything() {
        FakeClock clock = new FakeClock();
        PlayerMarkerFX fx = new PlayerMarkerFX(clock);
        clock.t = 0;
        fx.update(0, 0, 0);
        clock.t = 100;
        fx.update(0.5, 0, 90);
        fx.reset();
        // 复位后：速度 0、朝向 0（NaN 哨兵回退）、尾迹空、再 update 走首帧路径。
        assertEquals(0.0, fx.speed(), 1e-9);
        assertEquals(0f, fx.smoothYaw(), 1e-4f);
        assertTrue(fx.trail().isEmpty());
        clock.t = 200;
        fx.update(100, 100, 0); // 复位后 primed=false → 首帧路径（不会判成传送）
        assertEquals(0.0, fx.speed(), 1e-9);
    }

    // ==================== update：同帧去重 ====================

    @Test
    void sameMillisecondUpdateIsIgnored() {
        FakeClock clock = new FakeClock();
        PlayerMarkerFX fx = new PlayerMarkerFX(clock);
        clock.t = 0;
        fx.update(0, 0, 0);
        clock.t = 100;
        fx.update(0.5, 0, 0);
        // 同一毫秒内第二次 update 传入完全异常的位置（500 格外）：必须被去重忽略，
        // 否则会污染基准并在下一帧触发“传送重置”（speed 归 0）。
        fx.update(500, 0, 0);
        clock.t = 200;
        fx.update(1.0, 0, 0); // 若去重生效：本帧瞬时 5 格/秒；tau=120 平滑值 2.83 → 4.06。
        assertEquals(4.06, fx.speed(), 0.3, "speed=" + fx.speed());
    }

    // ==================== drawScale：静止呼吸 / 移动退出 ====================

    @Test
    void drawScaleBreathesIdleAndFreezesMoving() {
        FakeClock clock = new FakeClock();
        PlayerMarkerFX fx = new PlayerMarkerFX(clock);
        clock.t = 0;
        fx.update(0, 0, 0);
        // 静止：呼吸生效（幅度 ≤1.8%，随相位变化）。
        float a = fx.drawScale(0);
        float b = fx.drawScale(600); // 1/4 周期 = 峰值
        assertEquals(1f, a, 1e-4f);
        assertEquals(1.018f, b, 1e-3f);

        // 移动（stretch 5/8=0.625 > 0.10）：呼吸完全退出，缩放恒 1。
        for (int i = 1; i <= 12; i++) {
            clock.t = i * 100L;
            fx.update(i * 0.5, 0, 0);
        }
        assertTrue(fx.stretch() > 0.10f);
        assertEquals(1f, fx.drawScale(600), 1e-6f);
    }

    // ==================== 尾迹容量：限速采样不溢出丢档 ====================

    @Test
    void trailSamplingSurvivesHighFrameRate() {
        // 240fps（dt≈4ms）：40ms 限速采样保证缓冲覆盖 480ms 窗口。
        FakeClock clock = new FakeClock();
        PlayerMarkerFX fx = new PlayerMarkerFX(clock);
        clock.t = 0;
        fx.update(0, 0, 0);
        int frame = 0;
        for (long t = 4; t <= 4800; t += 4) { // 4ms/帧，跑 4.8 秒
            clock.t = t;
            fx.update(frame * 0.02, 0, 0); // 5 格/秒
            frame++;
        }
        assertEquals(2, fx.trail().size());
        // far 样本年龄 ≥260ms：位置至少滞后 5格/秒 × 0.26秒 = 1.3 格。
        assertTrue(fx.trail().get(0).x() < frame * 0.02 - 1.3,
                "far=" + fx.trail().get(0).x() + " cur=" + frame * 0.02);
    }

    @Test
    void trailBreaksAfterLongGap() {
        // 切后台/长 GC：1.2 秒无帧后高速恢复，旧样本（年龄 >800ms）不得被采纳为残影。
        FakeClock clock = new FakeClock();
        PlayerMarkerFX fx = new PlayerMarkerFX(clock);
        clock.t = 0;
        fx.update(0, 0, 0);
        for (int i = 1; i <= 12; i++) {
            clock.t = i * 100L;
            fx.update(i * 0.5, 0, 0);
        }
        assertEquals(2, fx.trail().size());
        // 断流 1.2 秒后高速恢复（12.6 格 < 64 不触发传送重置，速度仍高于 gate）。
        clock.t = 2400;
        fx.update(18.6, 0, 0);
        assertTrue(fx.trail().isEmpty(), "断流后旧样本（>800ms）不得被采纳");
        // 继续跑 500ms：新样本逐渐老化，尾迹恢复且全部来自断流后的轨迹。
        for (int i = 1; i <= 5; i++) {
            clock.t = 2400 + i * 100L;
            fx.update(18.6 + i * 1.0, 0, 0); // 10 格/秒
        }
        List<PlayerMarkerFX.TrailSample> trail = fx.trail();
        assertTrue(trail.size() >= 1, "恢复后尾迹应重现");
        for (var s : trail) {
            assertTrue(s.x() >= 18.0, "残影必须来自断流后的轨迹: x=" + s.x());
        }
    }
}
