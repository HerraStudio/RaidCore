package dev.draginventory.client.map;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 玩家图标动效状态机（v2.5.6 引入，v2.5.7 输入插值化 + 参数调优）：
 * 平滑转向 / 速度感知弹性拉伸 / 渐隐尾迹。
 *
 * <p><b>输入</b>：每帧玩家世界坐标（建议传 partialTick 插值坐标，v2.5.7 起——
 * 消除 20Hz tick 台阶，速度估计不再锯齿）+ 朝向 yaw + 时钟毫秒；
 * <b>输出</b>：平滑朝向 {@link #smoothYaw()}、拉伸系数 {@link #stretch()}（0..1）、
 * 尾迹样本 {@link #trail()}（静止时为空）。
 *
 * <p><b>设计约束</b>：
 * <ul>
 *   <li>纯 JVM 可测——不 import 任何 Minecraft 类；时钟走 {@link LongSupplier}
 *       （生产 {@code System::currentTimeMillis}，与 Util.getMillis 同源），测试注入假时钟；</li>
 *   <li>同一毫秒内多次 {@link #update} 只计算一次（同帧去重，小地图/大地图/设置预览
 *       三处调用共享同一份状态且互斥，防御万一）；</li>
 *   <li>传送（单次采样位移 &gt; 64 格）自动重置平滑与尾迹，尾迹绝不横跨地图；</li>
 *   <li>速度/朝向指数平滑时间常数 120ms（v2.5.7 从 140 调低——输入插值化后噪声
 *       大幅降低，不再需要那么重的滤波，转向与拉伸更跟手）；尾迹缓冲按时间限速
 *       采样（≥40ms 一个样本），任意帧率下 12 个样本覆盖 ≥480ms。</li>
 * </ul>
 *
 * <p>可见行为（与 {@code FactoryMapUI.player} 的 stretch/scale 参数配合）：
 * 静止 → 1.8% 呼吸（{@link #idleBreath}）；移动 → 沿前进方向最多 +30% 拉伸 /
 * 横向 -12% 收窄（空气动力学感）+ 身后两枚渐隐缩小残影（120ms 前 α≈0.30、
 * 260ms 前 α≈0.14，速度越快越明显）。
 */
final class PlayerMarkerFX {
    // ==================== 可调参数（包内可见，测试可断言边界） ====================

    /** 满速参考速度（格/秒）：达到即拉伸饱和。疾跑 ~5.6，马 ~8-10，取 8。 */
    static final float SPEED_REF = 8.0f;
    /** 尾迹显形速度阈值（格/秒）：低于该速度不画残影（走格子抖动不触发）。 */
    static final float SPEED_GATE = 1.2f;
    /** 速度/朝向指数平滑时间常数（毫秒）；v2.5.7 从 140 调至 120（输入插值化后更跟手）。 */
    static final double SMOOTH_TAU_MS = 120.0;
    /** 传送判定：单次采样位移超过该距离（格）视为传送/换维度。 */
    static final double TELEPORT_DIST = 64.0;
    /** 尾迹采样最小间隔（毫秒）与容量（容量 × 间隔 = 覆盖窗口）。 */
    static final long TRAIL_SAMPLE_MS = 40L;
    static final int TRAIL_CAP = 12;
    /** 两档尾迹的目标年龄（毫秒）与其基础透明度/缩放。 */
    static final long TRAIL_NEAR_MS = 120L, TRAIL_FAR_MS = 260L;
    /** 样本最大年龄：超过即尾迹断流（切后台/GC 停顿后残影不跳到远古位置）。 */
    static final long TRAIL_MAX_AGE_MS = 800L;
    static final float TRAIL_NEAR_ALPHA = 0.30f, TRAIL_FAR_ALPHA = 0.14f;
    static final float TRAIL_NEAR_SCALE = 0.85f, TRAIL_FAR_SCALE = 0.70f;
    /** 静止呼吸：周期 2400ms、幅度 1.8%。 */
    static final long BREATH_PERIOD_MS = 2400L;
    static final float BREATH_AMPLITUDE = 0.018f;

    // ==================== 纯数学（静态纯函数，单测直接断言） ====================

    /** 角度差 to-from 归一到 [-180, 180)（最短弧）。 */
    static float angleDeltaDeg(float from, float to) {
        float d = (to - from) % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /** 朝向最短弧指数逼近：t = 1 - e^(-dt/tau)，跨 ±360° 环绕安全。 */
    static float approachAngle(float from, float to, double dtMs, double tauMs) {
        if (dtMs <= 0) return from;
        float t = (float) (1.0 - Math.exp(-dtMs / tauMs));
        return from + angleDeltaDeg(from, to) * t;
    }

    /** 标量指数逼近（同上时间常数语义）。 */
    static double approach(double from, double to, double dtMs, double tauMs) {
        if (dtMs <= 0) return from;
        return from + (to - from) * (1.0 - Math.exp(-dtMs / tauMs));
    }

    /** 静止呼吸缩放因子：正弦 0..2π 相位，幅度 1.8%（stretch 淡入淡出由调用方处理）。 */
    static float idleBreath(long nowMs) {
        double phase = (nowMs % BREATH_PERIOD_MS) / (double) BREATH_PERIOD_MS;
        return 1f + BREATH_AMPLITUDE * (float) Math.sin(phase * Math.PI * 2);
    }

    // ==================== 实例状态 ====================

    /** 生产单例（客户端渲染线程独占访问，无需同步）。 */
    static final PlayerMarkerFX INSTANCE = new PlayerMarkerFX(System::currentTimeMillis);

    private final LongSupplier clock;
    private long lastUpdateMs = Long.MIN_VALUE;
    private boolean primed;
    private double lastX, lastZ;
    private float smoothYaw = Float.NaN;
    private double speedSmooth;

    // 尾迹环形缓冲（按时间限速采样）
    private final double[] trailX = new double[TRAIL_CAP];
    private final double[] trailZ = new double[TRAIL_CAP];
    private final float[] trailYaw = new float[TRAIL_CAP];
    private final long[] trailAt = new long[TRAIL_CAP];
    private int trailHead, trailSize;

    PlayerMarkerFX(LongSupplier clock) {
        this.clock = clock;
    }

    /** 全量重置（换世界/传送后调用；测试 @BeforeEach 复位）。 */
    void reset() {
        lastUpdateMs = Long.MIN_VALUE;
        primed = false;
        lastX = lastZ = 0.0;
        smoothYaw = Float.NaN;
        speedSmooth = 0.0;
        trailHead = trailSize = 0;
    }

    /**
     * 每帧喂入玩家状态（tick 粒度坐标即可）。同一毫秒重复调用为无操作。
     * 传送（位移 &gt; {@link #TELEPORT_DIST}）后：速度清零、朝向直接跳变、尾迹清空。
     */
    void update(double x, double z, float yaw) {
        long now = clock.getAsLong();
        if (now == lastUpdateMs) return; // 同帧去重
        double dt = lastUpdateMs == Long.MIN_VALUE ? 0.0 : now - lastUpdateMs;
        double dx = x - lastX, dz = z - lastZ;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (!primed || dist > TELEPORT_DIST) {
            // 首帧 / 传送：无速度可言，朝向直接采用目标值，尾迹断流。
            speedSmooth = 0.0;
            smoothYaw = yaw;
            trailHead = trailSize = 0;
        } else if (dt > 0) {
            double speed = dist / (dt / 1000.0); // 格/秒
            speedSmooth = approach(speedSmooth, Math.min(speed, SPEED_REF * 1.5), dt, SMOOTH_TAU_MS);
            smoothYaw = approachAngle(smoothYaw, yaw, dt, SMOOTH_TAU_MS);
        }

        // 尾迹按时间限速采样（首帧也记一个，作为后续的年龄基准点）。
        if (trailSize == 0 || now - trailAt[index(trailHead - 1)] >= TRAIL_SAMPLE_MS) {
            trailX[trailHead] = x;
            trailZ[trailHead] = z;
            trailYaw[trailHead] = yaw;
            trailAt[trailHead] = now;
            trailHead = (trailHead + 1) % TRAIL_CAP;
            if (trailSize < TRAIL_CAP) trailSize++;
        }

        lastX = x;
        lastZ = z;
        lastUpdateMs = now;
        primed = true;
    }

    /** 平滑后的朝向（未 primed 时返回 0，绘制侧仍在 translate/rotate 下安全）。 */
    float smoothYaw() {
        return Float.isNaN(smoothYaw) ? 0f : smoothYaw;
    }

    /** 速度因子 0..1（平滑速度 / SPEED_REF，饱和于 1）：驱动拉伸与尾迹强度。 */
    float stretch() {
        return (float) Math.min(Math.max(speedSmooth / SPEED_REF, 0.0), 1.0);
    }

    /** 当前平滑速度（格/秒），测试/调试用。 */
    double speed() {
        return speedSmooth;
    }

    /**
     * 主图标绘制缩放：静止（stretch ≤ 0.10 以下线性淡出）时叠加 1.8% 呼吸，
     * 移动中完全退出（位置精确优先，不给定位引入视觉摆动）。
     */
    float drawScale(long nowMs) {
        float stretch = stretch();
        float blend = Math.max(0f, (0.10f - stretch) / 0.10f);
        return 1f + (idleBreath(nowMs) - 1f) * blend;
    }

    /**
     * 尾迹样本（最多两枚，远端在前近端在后便于按序叠画）：
     * 平滑速度 ≥ {@link #SPEED_GATE} 时，从环形缓冲取"年龄 ≥ 目标年龄的最旧样本"。
     * 样本透明度 = 基础值 × 当前速度因子（快跑更明显），缩放固定递减。
     */
    List<TrailSample> trail() {
        if (speedSmooth < SPEED_GATE) return List.of();
        float k = stretch();
        List<TrailSample> out = new ArrayList<>(2);
        sampleAtLeast(TRAIL_FAR_MS, TRAIL_FAR_ALPHA * k, TRAIL_FAR_SCALE).ifPresent(out::add);
        sampleAtLeast(TRAIL_NEAR_MS, TRAIL_NEAR_ALPHA * k, TRAIL_NEAR_SCALE).ifPresent(out::add);
        return out;
    }

    /** 尾迹样本：世界坐标 + 朝向 + 透明度 + 缩放。 */
    record TrailSample(double x, double z, float yaw, float alpha, float scale) {}

    /**
     * 从环形缓冲找“年龄 ≥ minAge 的最新样本”（年龄从最近一次 update 时刻起算）；
     * 样本年龄超过 {@link #TRAIL_MAX_AGE_MS} 视为断流（切后台/长 GC），不再采纳。
     */
    private java.util.Optional<TrailSample> sampleAtLeast(long minAge, float alpha, float scale) {
        for (int i = 0; i < trailSize; i++) {
            int idx = index(trailHead - 1 - i); // 从最新往旧走
            long age = lastUpdateMs - trailAt[idx];
            if (age > TRAIL_MAX_AGE_MS) break; // 断流：更旧的样本只会更远，不采纳
            if (age >= minAge) {
                return java.util.Optional.of(new TrailSample(trailX[idx], trailZ[idx], trailYaw[idx], alpha, scale));
            }
        }
        return java.util.Optional.empty();
    }

    /** 环形缓冲下标归一（支持负数）。 */
    private int index(int i) {
        return ((i % TRAIL_CAP) + TRAIL_CAP) % TRAIL_CAP;
    }
}
