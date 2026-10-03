package dev.draginventory.client.compass;

/**
 * 朝向状态机：把玩家 yaw 转换为罗盘航向（0 = 北, 90 = 东, 180 = 南, 270 = 西），
 * 并用临界阻尼弹簧做帧率无关的平滑，附带角速度估计与基数方位接近度。
 *
 * <p>弹簧采用与 {@code WeaponHudMotion.Spring} 相同的精确闭式解：
 * x'' = -omega^2 * x - 2 * omega * x'，soft start / soft stop，无过冲震荡，
 * 停止转向后自然收尾。航向是圆周量，步进时始终取 [-180, 180) 的最短路径差，
 * 避免在 359 -> 0 边界发生 354 度的反向大回转。</p>
 */
public final class CompassHeading {
    /** 弹簧刚度下限/上限（约等于响应速度，omega 越大跟手越快）。 */
    public static final float OMEGA_MIN = 4f;
    public static final float OMEGA_MAX = 34f;

    private double value = Double.NaN;
    private double velocity;
    private double omega = 16;
    private double cardinalGlow;
    private int nearestCardinal;

    // 基数方位“进入区域”事件检测（基于原始目标值，非平滑值）：
    // 快速扫过正北也必须触发；离开后再次进入同一方位要能重发；
    // 用 ENTER/EXIT 双阈值滞回避免在边界上抖动双发。
    private static final float ZONE_ENTER = 0.5f;
    private static final float ZONE_EXIT = 0.45f;
    private boolean inCardinalZone;
    private int zoneCardinal = -1;
    private int pendingCardinalEvent = -1;

    private CompassHeading() {}

    /** Math.floorMod 没有浮点重载，这里补一个语义一致的实现。 */
    private static double floorMod(double x, double m) {
        double r = x % m;
        return r < 0 ? r + m : r;
    }

    public static CompassHeading create() {
        return new CompassHeading();
    }

    /** MC yaw -> 罗盘航向（保留亚度精度，供弹簧目标与刻度定位子像素平滑）。yaw=0 面向 +Z（南）-> 180；yaw=90 面向 -X（西）-> 270。 */
    public static float toHeading(float yaw) {
        float h = (yaw + 180f) % 360f;
        return h < 0 ? h + 360f : h;
    }

    /** 航向最近的基数方位（0/90/180/270）。注意必须先做浮点除再取整：
     * {@code Math.round(heading) / 90 * 90} 的整数除法会把 315~359° 全部归到 270°（西）。 */
    public static int nearestCardinal(float heading) {
        return Math.floorMod(Math.round(heading / 90f) * 90, 360);
    }

    /** 把角度差折叠到 [-180, 180)。 */
    public static float wrapDegrees(double diff) {
        double wrapped = (diff + 180.0) % 360.0;
        if (wrapped < 0) wrapped += 360.0;
        return (float) (wrapped - 180.0);
    }

    /**
     * @param targetHeading 目标航向（0..360，来自玩家实时 yaw）
     * @param seconds       距上一渲染帧的真实秒数（建议 clamp 到 [0, 0.1]）
     * @param omega         弹簧刚度
     * @param snapRange     基数方位吸附判定半径（度），&lt;=0 关闭
     */
    public void step(float targetHeading, float seconds, float omega, float snapRange) {
        if (!Float.isFinite(targetHeading) || seconds <= 0 || seconds > 0.25f) {
            return;
        }
        if (Double.isNaN(value)) {
            value = targetHeading;
            velocity = 0;
        }
        this.omega = Math.max(OMEGA_MIN, Math.min(OMEGA_MAX, omega));

        // 圆周最短路径：把目标折叠到当前值附近，等价于解环绕追踪。
        double target = value + wrapDegrees(targetHeading - value);
        double displacement = value - target;
        double coefficient = velocity + this.omega * displacement;
        double decay = Math.exp(-this.omega * seconds);
        value = target + (displacement + coefficient * seconds) * decay;
        velocity = (velocity - this.omega * coefficient * seconds) * decay;
        value = floorMod(value, 360.0);

        // 基数方位接近度：越靠近正北/正东/正南/正西，glow 越接近 1（用于放大与提亮）。
        float heading = display();
        int cardinal = nearestCardinal(heading);
        float distance = Math.abs(wrapDegrees(heading - cardinal));
        float proximity = snapRange > 0
                ? Math.max(0, 1 - distance / snapRange)
                : 0;
        // 平滑系数 12/s：进入与离开吸附区都有柔和过渡。
        cardinalGlow += (proximity - cardinalGlow) * (1 - Math.exp(-12 * seconds));
        nearestCardinal = cardinal;

        // “进入区域”事件用原始目标值判定（玩家真实视角）：平滑值在快转时滞后，
        // 若用平滑值判定会漏掉快速扫过的方位。
        int rawCardinal = nearestCardinal(targetHeading);
        float rawDistance = Math.abs(wrapDegrees(targetHeading - rawCardinal));
        float rawProximity = snapRange > 0
                ? Math.max(0, 1 - rawDistance / snapRange)
                : 0;
        boolean nowInZone = rawProximity >= ZONE_ENTER;
        if (nowInZone && (!inCardinalZone || rawCardinal != zoneCardinal)) {
            // 首次进入区域，或快转直接跳进另一个方位的区域。
            inCardinalZone = true;
            zoneCardinal = rawCardinal;
            pendingCardinalEvent = rawCardinal;
        } else if (!nowInZone && rawProximity < ZONE_EXIT) {
            inCardinalZone = false;
        }
    }

    /** 直接跳变到目标（世界切换/重置时使用，不播放动画）。 */
    public void snapTo(float targetHeading) {
        value = floorMod(targetHeading, 360.0);
        velocity = 0;
        cardinalGlow = 0;
        clearCardinalZone();
    }

    public void reset() {
        value = Double.NaN;
        velocity = 0;
        cardinalGlow = 0;
        clearCardinalZone();
    }

    /** 取出并清除一次“进入基数方位区域”事件；-1 表示本帧无事件。 */
    public int pollCardinalEvent() {
        int event = pendingCardinalEvent;
        pendingCardinalEvent = -1;
        return event;
    }

    /** 清除区域跟踪状态（HUD 从隐藏恢复可见时调用，使“已在区域内”也能重新触发进入事件）。 */
    public void clearCardinalZone() {
        inCardinalZone = false;
        zoneCardinal = -1;
        pendingCardinalEvent = -1;
    }

    /** 平滑后的显示航向，[0, 360)。 */
    public float display() {
        return (float) Math.floorMod(Math.round(value), 360);
    }

    /** 平滑航向的角度值（未取整），用于连续滚动的刻度定位。 */
    public float smooth() {
        return (float) value;
    }

    /** 估计角速度（度/秒），正值代表航向增大（顺时针，向右转）。 */
    public float velocityDegPerSec() {
        return (float) velocity;
    }

    /** 基数方位接近度 [0,1]。 */
    public float cardinalGlow() {
        return (float) cardinalGlow;
    }

    /** 最近的基数方位（0/90/180/270）。 */
    public int nearestCardinal() {
        return nearestCardinal;
    }

    public boolean isInitialized() {
        return !Double.isNaN(value);
    }
}
