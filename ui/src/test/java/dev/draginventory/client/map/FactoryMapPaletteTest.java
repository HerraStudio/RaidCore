package dev.draginventory.client.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link FactoryMapPalette} 彩色底图 + 预设滤镜管线的纯 JVM 单元测试：
 * VOID 全透明、NONE 直通、语义恒定强调方向（DOOR/EDGE）、DELTA 墙体提亮、
 * 样式切换置脏、styleFromId 回退与解析。
 *
 * <p>v2.5.3 新增断言：滤镜后 DOOR 强调存活（去饱和不再冲淡战术色）、
 * TARKOV 地面纸面化（变亮且偏暖）、灰阶单调（S 曲线无色阶反转）、
 * DELTA 墙体-地面强反差（暗地面 + 亮建筑的二值化观感）。</p>
 */
class FactoryMapPaletteTest {

    private final FactoryMapPalette palette = new FactoryMapPalette();

    private static int channel(int argb, int shift) {
        return argb >> shift & 0xFF;
    }

    /** ITU-R BT.601 亮度（与烘焙管线的灰度公式同口径）。 */
    private static int luminance(int argb) {
        return channel(argb, 16) * 299 + channel(argb, 8) * 587 + channel(argb, 0) * 114;
    }

    @Test
    void voidSemanticBakesFullyTransparent() {
        assertEquals(0x00000000, palette.bake(MapSemantics.VOID, 0xFF123456));
        // 任意样式下 VOID 都必须全透明（配合底幕实现透明度语义）。
        palette.setStyle(FactoryMapPalette.Style.DELTA);
        assertEquals(0x00000000, palette.bake(MapSemantics.VOID, 0xFF123456));
        palette.setStyle(FactoryMapPalette.Style.PUBG);
        assertEquals(0x00000000, palette.bake(MapSemantics.VOID, 0));
    }

    @Test
    void noneStylePassesFloorThrough() {
        palette.setStyle(FactoryMapPalette.Style.NONE);
        assertEquals(0xFF102030, palette.bake(MapSemantics.FLOOR, 0xFF102030));
    }

    @Test
    void doorEmphasisMovesTowardAccentButNotPure() {
        palette.setStyle(FactoryMapPalette.Style.NONE);
        int out = palette.bake(MapSemantics.DOOR, 0xFF102030);
        // 各通道朝 0x35D8A0 移动：介于起点与目标之间，且不等于纯目标色。
        int r = channel(out, 16), g = channel(out, 8), b = channel(out, 0);
        assertTrue(r > 0x10 && r < 0x35, "R 应朝 0x35 移动: " + Integer.toHexString(out));
        assertTrue(g > 0x20 && g < 0xD8, "G 应朝 0xD8 移动: " + Integer.toHexString(out));
        assertTrue(b > 0x30 && b < 0xA0, "B 应朝 0xA0 移动: " + Integer.toHexString(out));
        assertNotEquals(0xFF35D8A0, out);
    }

    @Test
    void edgeEmphasisDarkensChannels() {
        palette.setStyle(FactoryMapPalette.Style.NONE);
        int out = palette.bake(MapSemantics.EDGE, 0xFFFFFFFF);
        for (int shift : new int[] {16, 8, 0}) {
            int c = channel(out, shift);
            assertTrue(c <= 255 * 0.78 + 1, "通道应压暗至 0.78 倍（+1 容差）: " + Integer.toHexString(out));
            assertTrue(c < 255, "压暗后不应仍为满值: " + Integer.toHexString(out));
        }
    }

    @Test
    void deltaStyleWhitensWallsAndNeverReturnsZero() {
        int base = 0xFF7E7E7E;  // 中灰"石头墙"采样色
        palette.setStyle(FactoryMapPalette.Style.NONE);
        int none = palette.bake(MapSemantics.WALL, base);
        palette.setStyle(FactoryMapPalette.Style.DELTA);
        int delta = palette.bake(MapSemantics.WALL, base);
        // wallWhiten 生效：DELTA 输出比 NONE 更接近白色。
        assertTrue(distanceToWhite(delta) < distanceToWhite(none),
                "DELTA 墙体应比 NONE 更白: none=" + Integer.toHexString(none) + " delta=" + Integer.toHexString(delta));
        // 非 VOID 语义永不出 0（恒带不透明 alpha）。
        assertNotEquals(0, delta);
        for (MapSemantics s : MapSemantics.values()) {
            if (s == MapSemantics.VOID) continue;
            assertNotEquals(0, palette.bake(s, base), "语义 " + s + " 不应烘焙为 0");
        }
    }

    @Test
    void setStyleMarksDirtyAndChangesOutput() {
        int base = 0xFF806040;
        int none = palette.bake(MapSemantics.FLOOR, base);
        palette.setStyle(FactoryMapPalette.Style.TARKOV);
        int tarkov = palette.bake(MapSemantics.FLOOR, base);
        assertNotEquals(none, tarkov, "样式切换后同一输入应产出变化");
        // 切回 NONE 恢复原色（LUT 按样式重建，无残留状态）。
        palette.setStyle(FactoryMapPalette.Style.NONE);
        assertEquals(none, palette.bake(MapSemantics.FLOOR, base));
    }

    @Test
    void styleFromIdParsesAndFallsBack() {
        assertEquals(FactoryMapPalette.Style.NONE, FactoryMapPalette.styleFromId(null));
        assertEquals(FactoryMapPalette.Style.NONE, FactoryMapPalette.styleFromId(""));
        assertEquals(FactoryMapPalette.Style.NONE, FactoryMapPalette.styleFromId("bogus"));
        assertEquals(FactoryMapPalette.Style.DELTA, FactoryMapPalette.styleFromId("delta"));
        assertEquals(FactoryMapPalette.Style.TARKOV, FactoryMapPalette.styleFromId("tarkov"));
        assertEquals(FactoryMapPalette.Style.DARKZONE, FactoryMapPalette.styleFromId("darkzone"));
        assertEquals(FactoryMapPalette.Style.APEX, FactoryMapPalette.styleFromId("apex"));
        assertEquals(FactoryMapPalette.Style.PUBG, FactoryMapPalette.styleFromId("pubg"));
        assertEquals(FactoryMapPalette.Style.NONE, FactoryMapPalette.styleFromId("none"));
    }

    /**
     * v2.5.3：DOOR 强调移到滤镜后——即使三角洲把全图压到近单色（saturation 0.16），
     * 房门仍应保持青绿战术色（G 主导），不再被去饱和冲成灰。
     */
    @Test
    void deltaDoorAccentSurvivesDesaturation() {
        palette.setStyle(FactoryMapPalette.Style.DELTA);
        int out = palette.bake(MapSemantics.DOOR, 0xFF102030);
        int r = channel(out, 16), g = channel(out, 8), b = channel(out, 0);
        assertTrue(g >= 100, "去饱和后门仍应为亮青绿（G 通道 ≥ 100）: " + Integer.toHexString(out));
        assertTrue(g > r + 40, "G 应显著主导 R: " + Integer.toHexString(out));
        assertTrue(g > b + 20, "G 应主导 B: " + Integer.toHexString(out));
    }

    /** v2.5.3：TARKOV 地面基调——向纸面米黄收敛（比原色更亮且 R 暖于 B）。 */
    @Test
    void tarkovFloorBecomesPaperCream() {
        int base = 0xFF505050;
        palette.setStyle(FactoryMapPalette.Style.NONE);
        int none = palette.bake(MapSemantics.FLOOR, base);
        palette.setStyle(FactoryMapPalette.Style.TARKOV);
        int tarkov = palette.bake(MapSemantics.FLOOR, base);
        for (int shift : new int[] {16, 8, 0}) {
            assertTrue(channel(tarkov, shift) > channel(none, shift),
                    "TARKOV 地面应比原色更亮（纸面）: " + Integer.toHexString(tarkov));
        }
        assertTrue(channel(tarkov, 16) > channel(tarkov, 0),
                "纸面应偏暖（R > B）: " + Integer.toHexString(tarkov));
    }

    /**
     * v2.5.3：S 曲线 + 对比度不得引入色阶反转——所有样式下灰阶输入的单调性保持
     * （暗灰 ≤ 中灰 ≤ 亮灰，且首尾严格递增）。
     */
    @Test
    void grayRampStaysMonotonicUnderEveryStyle() {
        for (FactoryMapPalette.Style style : FactoryMapPalette.Style.values()) {
            palette.setStyle(style);
            int dark = palette.bake(MapSemantics.WALL, 0xFF202020);
            int mid = palette.bake(MapSemantics.WALL, 0xFF606060);
            int bright = palette.bake(MapSemantics.WALL, 0xFFA0A0A0);
            assertTrue(luminance(dark) <= luminance(mid) && luminance(mid) <= luminance(bright),
                    style + " 灰阶单调性被破坏: " + Integer.toHexString(dark)
                            + " / " + Integer.toHexString(mid) + " / " + Integer.toHexString(bright));
            assertTrue(luminance(dark) < luminance(bright),
                    style + " 首尾灰阶应有区分度");
        }
    }

    /**
     * v2.5.3：DELTA 的标志性观感——暗蓝黑地面 + 冷白建筑的强反差
     * （对标参考图“#0f1419 底 / 浅白建筑”的二值化分层）。
     */
    @Test
    void deltaWallFloorContrastGap() {
        palette.setStyle(FactoryMapPalette.Style.DELTA);
        int wall = palette.bake(MapSemantics.WALL, 0xFF7E7E7E);
        int floor = palette.bake(MapSemantics.FLOOR, 0xFF505050);
        assertTrue(luminance(wall) - luminance(floor) >= 60_000,
                "DELTA 墙体应远亮于地面（亮度差 ≥ 60/255）: wall=" + Integer.toHexString(wall)
                        + " floor=" + Integer.toHexString(floor));
    }

    private static int distanceToWhite(int argb) {
        return (255 - channel(argb, 16)) + (255 - channel(argb, 8)) + (255 - channel(argb, 0));
    }
}
