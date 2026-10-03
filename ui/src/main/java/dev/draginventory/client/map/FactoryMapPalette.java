package dev.draginventory.client.map;

import java.util.Locale;

/**
 * 彩色底图 + 预设滤镜管线（v2.5.0 起；v2.5.3 全面重做预设参数）。
 *
 * <p>底图不使用固定语义色板，而是采样方块的 {@code MapColor} 真实颜色
 * （见 {@link FactoryMapSampler.Sample#color}），保留方块像素结构（1 方块 = 1 纹素）。
 * 滤镜为<b>六选一预设样式</b>（{@link Style}），默认 {@link Style#NONE}
 * 即彩色原色不滤镜；其余样式对标各战术游戏的地图观感。</p>
 *
 * <p><b>v2.5.3 重做要点</b>（反馈：旧预设“都不太像原游戏的地图风格”）：
 * <ol>
 *   <li><b>S 曲线 LUT</b>：线性对比度之外，与 smoothstep 按 {@code sCurve} 强度插值——
 *       暗部更沉、亮部更亮，接近原游戏地图的“分层打光”观感（单调性保持，无色阶反转）；</li>
 *   <li><b>地面目标色 floorTarget/floorAmount</b>：FLOOR 与 BLOCKED 语义在滤镜末端向
 *       目标色收敛——这是各原游戏风格的基调来源（三角洲的暗蓝黑地面 / 塔科夫的纸面米黄 /
 *       暗区的墨绿底），仅靠全局 tint 无法把杂色地面压成统一基调；</li>
 *   <li><b>墙体目标色 wallTarget/wallWhiten</b>：墙体向各自风格的建筑色收敛
 *       （三角洲冷白 / 塔科夫暖米白 / 暗区灰绿），与地面目标色形成强反差
 *       （对标参考图“暗地面 + 亮建筑”的二值化观感）；</li>
 *   <li><b>语义恒定强调移到滤镜后</b>：DOOR/STAIR 的战术强调色此前在滤镜<b>前</b>混合，
 *       会被去饱和/染色冲淡（去饱和 0.18 的三角洲下房门几乎变灰）。v2.5.3 起在滤镜
 *       <b>后</b>混合，任何预设下门恒为青绿、楼梯恒为暖金——战术可读性真正恒定。
 *       EDGE 压暗仍在滤镜前（描边属于底图结构，应随滤镜整体走）。</li>
 * </ol></p>
 *
 * <p>烘焙管线（{@link #bake}）：
 * <ol>
 *   <li>{@link MapSemantics#VOID} → 全透明（配合底幕实现透明度语义）；</li>
 *   <li>EDGE 各通道压暗 0.78（滤镜前）；</li>
 *   <li>样式 ≠ NONE 时套用预设：亮度 LUT（contrast/brightness + sCurve，256 项，样式变化时重建）
 *       → 饱和度灰度插值 → 全局 tint 混色 → 地面目标色收敛（FLOOR/BLOCKED）
 *       → 墙体目标色收敛（WALL）；</li>
 *   <li>语义恒定强调（滤镜后）：DOOR 向青绿混色 0.55、STAIR 向暖金混色 0.40。</li>
 * </ol>
 * 样式变化只需重建 LUT，256 个瓦片全量重烤也不产生逐像素乘除的 GC 与 CPU 压力。</p>
 */
final class FactoryMapPalette {

    /**
     * 滤镜预设样式。参数含义：
     * <ul>
     *   <li>{@code contrast / brightness} —— 亮度 LUT（围绕 0.5 中点缩放 + 平移）；</li>
     *   <li>{@code sCurve} —— S 曲线强度（0..1，与 smoothstep 插值；暗部下沉亮部上抬）；</li>
     *   <li>{@code saturation} —— 饱和度（1 = 原样，0 = 全灰，&gt;1 增饱和）；</li>
     *   <li>{@code tint / tintAmount} —— 整体染色（ARGB 颜色 + 混色强度，tint = 0 不染）；</li>
     *   <li>{@code wallTarget / wallWhiten} —— 墙体向目标色收敛（对标各风格的建筑色）；</li>
     *   <li>{@code floorTarget / floorAmount} —— 地面向目标色收敛（FLOOR/BLOCKED，基调色）。</li>
     * </ul>
     */
    enum Style {
        /** 彩色原色，不套滤镜（默认）。 */
        NONE(1.0, 0.0, 0.0, 1.0, 0, 0.0, 0, 0.0, 0, 0.0),
        /**
         * 三角洲行动：近黑蓝的地面基调 + 冷白建筑强反差 + 轻微海军蓝染色，
         * 近单色去饱和（对标参考图 #0f1419 底 / 浅白建筑）。
         */
        DELTA(1.35, -0.06, 0.55, 0.16, 0xFF0D1218, 0.12, 0xEEF2F6, 0.78, 0xFF111820, 0.58),
        /** 逃离塔科夫：纸质地图——米黄纸面基调 + 暖米白建筑 + 柔和对比（ tint 较轻保留细节）。 */
        TARKOV(1.20, 0.06, 0.28, 0.32, 0xFFD9CFB6, 0.24, 0xFFF6E8, 0.55, 0xFFCFC3A4, 0.50),
        /** 暗区突围：夜视仪墨绿——深绿地面基调 + 近中性灰白建筑 + 绿色染色（墙体保持低饱和，青绿门靠色相区分）。 */
        DARKZONE(1.28, -0.02, 0.40, 0.45, 0xFF142E23, 0.30, 0xFFE0E4E0, 0.58, 0xFF0F231B, 0.50),
        /** Apex 英雄：明亮干净——提亮 + 增饱和 + 轻微冷灰地面，色彩鲜活。 */
        APEX(1.12, 0.09, 0.20, 1.28, 0, 0.0, 0xFFF2F4F6, 0.30, 0xFF3A4750, 0.22),
        /** 绝地求生：卫星遥感——自然色调轻度去饱和 + 暖褐地面 + 轻微暖染。 */
        PUBG(1.10, 0.02, 0.15, 0.80, 0xFF2E2A22, 0.10, 0xFFE8E4DC, 0.22, 0xFF2F2C24, 0.28);

        /** 对比度（围绕 0.5 中点缩放）。 */
        final double contrast;
        /** 亮度偏移。 */
        final double brightness;
        /** S 曲线强度（0 = 线性，1 = 全 smoothstep）。 */
        final double sCurve;
        /** 饱和度（1 = 原样）。 */
        final double saturation;
        /** 整体染色（ARGB）；0 = 不染色。 */
        final int tint;
        /** 染色强度（0..1）。 */
        final double tintAmount;
        /** 墙体目标色（ARGB）。 */
        final int wallTarget;
        /** 墙体向目标色收敛的强度（0..1）。 */
        final double wallWhiten;
        /** 地面目标色（ARGB；FLOOR/BLOCKED 收敛目标）。 */
        final int floorTarget;
        /** 地面向目标色收敛的强度（0..1）。 */
        final double floorAmount;

        Style(double contrast, double brightness, double sCurve, double saturation,
                int tint, double tintAmount, int wallTarget, double wallWhiten,
                int floorTarget, double floorAmount) {
            this.contrast = contrast;
            this.brightness = brightness;
            this.sCurve = sCurve;
            this.saturation = saturation;
            this.tint = tint;
            this.tintAmount = tintAmount;
            this.wallTarget = wallTarget;
            this.wallWhiten = wallWhiten;
            this.floorTarget = floorTarget;
            this.floorAmount = floorAmount;
        }
    }

    /**
     * 配置 id → 样式。未知 / null 值一律回退 {@link Style#NONE}
     * （彩色原色不滤镜，保证配置文件被手改坏时地图仍可用）。
     */
    static Style styleFromId(String id) {
        if (id == null) return Style.NONE;
        try {
            return Style.valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Style.NONE;
        }
    }

    private final int[] lut = new int[256];
    private Style style = Style.NONE;
    private boolean dirty = true;

    // ==================== 语义基础色 ====================

    /** 语义 → 基础色（未滤镜）。采样回退（方块无 MapColor 时）与 UI 图例的统一源头。 */
    static int baseColor(MapSemantics s) {
        return switch (s) {
            case VOID -> 0xFF0D141A;
            case WALL -> 0xFFD5DCE0;   // 浅白建筑——参考图中建筑以白色块呈现
            case FLOOR -> 0xFF39424A;
            case EDGE -> 0xFF7F939B;
            case STAIR -> 0xFFC9A45C;  // 暖金楼梯
            case DOOR -> 0xFF3FBF9A;   // 青绿主题色（与 HERRA HUD 统一）
            case WATER -> 0xFF17303F;  // 深蓝黑水体
            case BLOCKED -> 0xFF161F26;
            case DECOR -> 0xFF2A3A31;
        };
    }

    /** 语义 → 基础色（未滤镜，图例用）。实例方法转发静态色表（保留既有调用点）。 */
    public int color(MapSemantics s) {
        return baseColor(s);
    }

    /** 语义 → 未滤镜的图例色（图例展示语义本身的颜色，不受当前滤镜影响）。 */
    public int legendColor(MapSemantics s) {
        return color(s);
    }

    // ==================== 烘焙主入口 ====================

    /**
     * 新主入口：语义 + 方块真实颜色 → 最终烘焙色。
     *
     * @param s         语义分类（VOID → 全透明 0x00000000，配合底幕实现透明度语义）
     * @param baseColor 采样到的方块颜色（ARGB；无采样色时为语义基础色）
     * @return ARGB 烘焙色（非 VOID 恒不透明；VOID 恒全透明）
     */
    public int bake(MapSemantics s, int baseColor) {
        if (s == MapSemantics.VOID) return 0x00000000;
        int r = baseColor >>> 16 & 0xFF;
        int g = baseColor >>> 8 & 0xFF;
        int b = baseColor & 0xFF;

        // 1) EDGE 压暗（滤镜前）：描边属于底图结构，随滤镜整体走。
        if (s == MapSemantics.EDGE) {
            r = scale(r, 0.78);
            g = scale(g, 0.78);
            b = scale(b, 0.78);
        }

        // 2) 预设滤镜（NONE 直通，即彩色原色）。
        if (style != Style.NONE) {
            if (dirty) rebuildLut();
            r = lut[r];
            g = lut[g];
            b = lut[b];
            // 饱和度：向灰度插值（sat > 1 时向外推，公式对称）。
            int gray = (int) Math.round(r * 0.299 + g * 0.587 + b * 0.114);
            r = clamp(gray + (r - gray) * style.saturation);
            g = clamp(gray + (g - gray) * style.saturation);
            b = clamp(gray + (b - gray) * style.saturation);
            // 整体染色。
            if (style.tint != 0) {
                r = mix(r, style.tint >>> 16 & 0xFF, style.tintAmount);
                g = mix(g, style.tint >>> 8 & 0xFF, style.tintAmount);
                b = mix(b, style.tint & 0xFF, style.tintAmount);
            }
            // 地面基调：FLOOR/BLOCKED 向目标色收敛（各风格的“底色”，v2.5.3）。
            if (style.floorAmount > 0 && (s == MapSemantics.FLOOR || s == MapSemantics.BLOCKED)) {
                r = mix(r, style.floorTarget >>> 16 & 0xFF, style.floorAmount);
                g = mix(g, style.floorTarget >>> 8 & 0xFF, style.floorAmount);
                b = mix(b, style.floorTarget & 0xFF, style.floorAmount);
            }
            // 建筑提亮：WALL 向目标色收敛（与地面基调形成强反差，v2.5.3 参数化目标色）。
            if (style.wallWhiten > 0 && s == MapSemantics.WALL) {
                r = mix(r, style.wallTarget >>> 16 & 0xFF, style.wallWhiten);
                g = mix(g, style.wallTarget >>> 8 & 0xFF, style.wallWhiten);
                b = mix(b, style.wallTarget & 0xFF, style.wallWhiten);
            }
        }

        // 3) 语义恒定强调（v2.5.3 移到滤镜后）：DOOR 青绿出入点、STAIR 暖金垂直连接。
        //    滤镜前的强调会被去饱和/染色冲淡（旧版三角洲下房门几乎变灰）；
        //    0.75 的强度保证单像素宽的门在去饱和/纸面/夜视预设下仍一眼可辨
        //    （视觉走查：0.55 时塔科夫纸面上绿门与卡其底色融合，0.75 达标）。
        if (s == MapSemantics.DOOR) {
            r = mix(r, 0x35, 0.75);
            g = mix(g, 0xD8, 0.75);
            b = mix(b, 0xA0, 0.75);
        } else if (s == MapSemantics.STAIR) {
            r = mix(r, 0xE0, 0.40);
            g = mix(g, 0xB0, 0.40);
            b = mix(b, 0x60, 0.40);
        }

        return 0xFF000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    /** 上下层叠加时的透明度（alpha 字节值）：上层墙体轮廓、下层地面淡显。 */
    public int overlayAlpha(MapSemantics s) {
        return switch (s) {
            case WALL -> 0x2E;
            case FLOOR, EDGE, DOOR, STAIR -> 0x18;
            default -> 0;
        };
    }

    // ==================== 状态 ====================

    /** 切换滤镜预设；与当前不同才置脏（值不变零开销，渲染器每帧调用）。 */
    public void setStyle(Style style) {
        if (style == null) style = Style.NONE;
        if (style != this.style) {
            this.style = style;
            dirty = true;
        }
    }

    /** 标记全部瓦片需重烤（签名变化时由渲染器调用）。 */
    public void markDirty() { dirty = true; }

    /** 当前样式（测试与设置界面读取）。 */
    public Style style() { return style; }

    // ==================== 辅助 ====================

    /**
     * 单通道混色：{@code a + (b - a) * t}，四舍五入后钳制 0..255。
     * （色板保持纯 JVM 可测：不引用 Minecraft 类，钳制用本地实现。）
     */
    private static int mix(int a, int b, double t) {
        return clamp(a + (b - a) * t);
    }

    /** 单通道缩放（EDGE 压暗等），四舍五入后钳制 0..255。 */
    private static int scale(int v, double factor) {
        return clamp(v * factor);
    }

    /** 浮点 → 钳制到 0..255 的字节值（四舍五入）。 */
    private static int clamp(double v) {
        int i = (int) Math.round(v);
        return i < 0 ? 0 : Math.min(i, 255);
    }

    /** 钳制到 0..1（LUT 的 smoothstep 要求输入在单位区间内）。 */
    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    /**
     * 亮度 LUT：对比度围绕 0.5 中点缩放 + 亮度平移，钳制到 [0,1] 后与 smoothstep
     * （{@code d*d*(3-2d)}）按 sCurve 强度插值（v2.5.3）。两级映射均单调，插值仍单调，
     * 不产生色阶反转；样式变化时重建（256 项）。
     */
    private void rebuildLut() {
        for (int v = 0; v < 256; v++) {
            double d = clamp01((v / 255.0 - 0.5) * style.contrast + 0.5 + style.brightness);
            if (style.sCurve > 0) {
                double ss = d * d * (3 - 2 * d);
                d = d + (ss - d) * style.sCurve;
            }
            lut[v] = clamp(d * 255.0);
        }
        dirty = false;
    }
}
