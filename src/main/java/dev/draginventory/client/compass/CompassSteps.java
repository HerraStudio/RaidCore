package dev.draginventory.client.compass;

/**
 * 刻度密度自适应（v1.5.2）：条带拉宽（宽度滑块 / 屏宽占比预设 / 缩放）后，
 * 相邻刻度的视觉间距变大、显得稀疏。本类在保持“用户所选视觉密度”的前提下
 * 自动选择更小的步长加密刻度/数字——字体、刻度长度、元素尺寸全部不变，
 * 只增加数量，拉宽后的视觉密度与默认宽度时一致。
 *
 * <p><b>参照密度语义</b>：用户配置的步长在“默认条带（240px / 视野 120°）”下
 * 的视觉间距视为用户选定的密度目标。条带更宽（每度像素更多）时按比例加密；
 * 条带更窄或与默认相同时不做任何改动——用户特意设置的大步长（稀疏刻度）
 * 是其审美选择，绝不被强制加密。</p>
 *
 * <p>纯逻辑无 MC 依赖，可独立编译单测（scripts/test_compass_logic.sh）。</p>
 */
public final class CompassSteps {
    private CompassSteps() {}

    /** 参照视觉密度：默认 240px 宽 / 120° 视野下每度像素数。 */
    public static final double REF_PX_PER_DEG = (240.0 / 2 - 6) / (120.0 / 2); // = 1.9

    /** 次级刻度候选步长（从小到大）。最小 1°：再小就挤成一团，失去意义。 */
    public static final int[] MINOR_CANDIDATES = {1, 2, 3, 5, 10, 15, 20, 25, 30};

    /** 数字候选步长（从小到大）。最小 5°：数字比刻度线宽，太密会重叠。 */
    public static final int[] NUMBER_CANDIDATES = {5, 10, 15, 20, 30, 45, 60, 90};

    /**
     * 自适应有效步长。
     *
     * @param userStep 用户配置的步长（语义为“上限”，同时决定目标视觉密度）
     * @param pxPerDeg 视觉上每度的像素数（含整体缩放）
     * @param candidates 候选步长表（升序）
     * @return 候选中不超过 userStep 的最大步长 c，满足 pxPerDeg * c ≤ userStep * REF_PX_PER_DEG
     *         （即视觉间距不超过用户在默认宽度下选定的密度）；条带不宽于参照时恒等于
     *         不超过 userStep 的最大候选（与旧行为一致，绝不稀释）。
     */
    public static int effectiveStep(int userStep, double pxPerDeg, int[] candidates) {
        double maxSpacing = userStep * REF_PX_PER_DEG + 1e-9;
        int best = candidates[0];
        for (int c : candidates) {
            if (c > userStep) break;
            if (pxPerDeg * c <= maxSpacing) {
                best = c;
            }
        }
        return best;
    }

    /** 次级刻度有效步长。 */
    public static int effectiveMinorStep(int userStep, double pxPerDeg) {
        return effectiveStep(userStep, pxPerDeg, MINOR_CANDIDATES);
    }

    /** 数字有效步长。 */
    public static int effectiveNumberStep(int userStep, double pxPerDeg) {
        return effectiveStep(userStep, pxPerDeg, NUMBER_CANDIDATES);
    }
}
