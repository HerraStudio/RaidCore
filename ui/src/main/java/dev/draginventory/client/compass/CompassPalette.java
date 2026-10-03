package dev.draginventory.client.compass;

import java.awt.Color;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配色方案：每套皮肤都可以选配色。颜色为 0xRRGGBB（透明度在渲染时合成），
 * 中心朝向色（accent）与标点色刻意区分，满足“标点提示色必须区别于中心朝向色”的要求。
 */
public record CompassPalette(String id, int accent, int text, int dim, int tick, int background,
                             int markerEnemy, int markerLocation, int markerItem) {

    private static final Map<String, CompassPalette> ALL = register(
            // 极光（默认）：薄荷绿点缀 + 冷白文字，与现有 HUD 的白色系一致但更有辨识度。
            new CompassPalette("aurora", 0x7FE8C3, 0xF4F7F6, 0xB8C4C0, 0xD9E2DF, 0x141A19,
                    0xFF5D5D, 0x6FD3FF, 0xFFC46B),
            // 霜白：纯白点缀，最接近原版 HUD 风格。
            new CompassPalette("frost", 0xFFFFFF, 0xF4F7F6, 0xB8C4C0, 0xE6EBE9, 0x101615,
                    0xFF5D5D, 0x6FD3FF, 0xFFC46B),
            // 琥珀：暖橙点缀。
            new CompassPalette("amber", 0xFFB454, 0xF7F3EA, 0xC9BFAB, 0xE8E0D0, 0x191511,
                    0xFF5D5D, 0x6FD3FF, 0x8BE28B),
            // 绯红：战术红点缀。
            new CompassPalette("crimson", 0xFF6B5D, 0xF7F2F2, 0xC9B8B8, 0xE8DCDC, 0x1A1212,
                    0xFFD35D, 0x6FD3FF, 0x8BE28B),
            // 紫罗兰。
            new CompassPalette("violet", 0xB39DFF, 0xF3F1F8, 0xBDB6D1, 0xE2DEED, 0x151219,
                    0xFF5D5D, 0x6FD3FF, 0xFFC46B),
            // 石板：低饱和灰蓝，低调耐看。
            new CompassPalette("slate", 0xA8C5E2, 0xECF1F5, 0xAEBDC9, 0xDCE4EA, 0x12171C,
                    0xFF5D5D, 0x7FE8C3, 0xFFC46B));

    private static Map<String, CompassPalette> register(CompassPalette... palettes) {
        Map<String, CompassPalette> map = new LinkedHashMap<>();
        for (CompassPalette palette : palettes) {
            map.put(palette.id(), palette);
        }
        return map;
    }

    public static CompassPalette byId(String id) {
        CompassPalette palette = ALL.get(id);
        return palette != null ? palette : ALL.get("aurora");
    }

    /** 精确查找（不回退），指令校验未知 id 用。 */
    public static CompassPalette byIdOrNull(String id) {
        return ALL.get(id);
    }

    /** 逗号分隔的合法 id 列表（指令错误提示用）。 */
    public static String idList() {
        return String.join(", ", ALL.keySet());
    }

    public static Collection<CompassPalette> all() {
        return ALL.values();
    }

    /** 应用配置覆盖：override = -1 表示使用配色原值。 */
    public static int resolve(int override, int paletteColor) {
        return override >= 0 ? override : paletteColor;
    }

    /**
     * 运行时保障“标点色与中心朝向色（accent）明显不同”：
     * 用户可能把 accent 覆盖成与某类标点相近的颜色，此时自动旋转标点色相
     * （或提高饱和度/亮度）拉开距离，保证任何覆盖组合下都可区分。
     */
    static int distinctFrom(int accent, int color) {
        float[] a = hsb(accent);
        float[] c = hsb(color);
        if (distinctEnough(a, c)) {
            return color;
        }
        // 色相过近（或双方都接近灰且亮度接近）：旋转标点色相避开 accent。
        float hue = c[1] < 0.12f ? 0.55f : (c[0] + 0.42f) % 1f;
        float sat = Math.min(1f, Math.max(c[1], 0.55f) + 0.15f);
        float bri = Math.max(c[2], 0.82f);
        return Color.HSBtoRGB(hue, sat, bri) & 0xFFFFFF;
    }

    private static boolean distinctEnough(float[] a, float[] c) {
        // 双方都接近灰：亮度差够大即视为可区分。
        if (a[1] <= 0.15f && c[1] <= 0.15f) {
            return Math.abs(a[2] - c[2]) > 0.3f;
        }
        // 只有一方接近灰：另一方足够饱和即可区分。
        if (a[1] <= 0.15f || c[1] <= 0.15f) {
            return Math.max(a[1], c[1]) > 0.45f;
        }
        // 双方都有色彩：色相距离足够即可。
        float dh = Math.abs(a[0] - c[0]);
        dh = Math.min(dh, 1f - dh);
        return dh >= 0.14f;
    }

    private static float[] hsb(int rgb) {
        return Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
    }
}
