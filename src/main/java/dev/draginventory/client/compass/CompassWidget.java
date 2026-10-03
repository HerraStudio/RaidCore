package dev.draginventory.client.compass;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import dev.draginventory.client.CompassMarkerBridge;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 方位条控件：LDLib2 UIElement 子类，负责
 * <ul>
 *   <li>读取玩家 yaw 并驱动 {@link CompassHeading} 弹簧（帧率无关）</li>
 *   <li>聚合标点（战术标点只读桥接 / 测试标点 / 外部提供者）</li>
 *   <li>计算布局、边缘渐隐、惯性倾斜、入场动画与吸附高亮</li>
 *   <li>把每个元素分发给当前 {@link CompassStyle} 皮肤绘制</li>
 * </ul>
 * 同一个类同时用于 HUD 与设置界面实时预览（预览模式不读玩家状态）。
 */
public final class CompassWidget extends UIElement {
    /** 预览模式的演示标点相对方位（度）。 */
    private static final float[] PREVIEW_BEARINGS = {38f, -22f, 84f};

    /** HUD 实例共享 CompassHub.LIVE（供 API 查询）；预览实例使用独立弹簧。 */
    private final CompassHeading heading;
    private final boolean preview;

    /** 上次可见状态：false -> true 时触发入场动画。 */
    private boolean wasVisible;
    private long entryStart = Long.MIN_VALUE;
    private long lastFrameMillis = Long.MIN_VALUE;
    private Player displayedPlayer;

    /** 预览模式状态。 */
    private float previewTarget = 206f;
    private float previewBase = 206f;
    private boolean sway = true;

    /** 预览自动摆动（设置界面开关）。开启时以当前朝向重新锚定摆动中心。 */
    public void setSway(boolean sway) {
        this.sway = sway;
        if (sway) {
            previewBase = previewTarget;
        }
    }

    /** 本帧缓存（drawBackgroundTexture 与 drawBackgroundAdditional 共享）。 */
    private CompassStyleContext frame;
    private boolean frameVisible;

    public CompassWidget(boolean preview) {
        this.preview = preview;
        this.heading = preview ? CompassHeading.create() : CompassHub.LIVE;
    }

    // ==================== 预览控制（设置界面） ====================

    /** 拖拽预览：dx 像素映射为度数。 */
    public void dragPreview(float deltaX) {
        previewTarget = Mth.wrapDegrees(previewTarget + deltaX * 0.55f);
        if (previewTarget < 0) previewTarget += 360f;
    }

    /** 预览自动摆动。 */
    public void swayPreview(long now) {
        previewTarget = Mth.wrapDegrees(previewBase + 34f * (float) Math.sin(now / 3400.0));
        if (previewTarget < 0) previewTarget += 360f;
    }

    public void setPreviewHeading(float degrees) {
        // 只改视角目标，不动 previewBase（演示标点的世界锚点）：
        // 拖动滑杆模拟转向时，标点应像真实 HUD 一样固定在世界方位上滑过条带，
        // 而不是粘在条带中心跟着走。
        previewTarget = Mth.wrapDegrees(degrees);
        if (previewTarget < 0) previewTarget += 360f;
        heading.snapTo(previewTarget);
    }

    // ==================== 状态更新 ====================

    private void updateState(GUIContext context) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        float seconds = secondsSinceLastFrame(mc, now);

        // 世界切换检测（退出/重进/切维度）：旧世界的测试标点、死亡标点与朝向状态立即失效。
        if (!preview) {
            CompassHub.syncWorld(mc.level);
            CompassHub.tickPlayer(mc.player);
        }

        boolean visible = isVisible(mc);
        if (visible && !wasVisible) {
            entryStart = CompassConfig.ENTRY_ANIMATION.get() ? now : Long.MIN_VALUE;
            if (!preview) {
                // 区域跟踪复位：HUD 重新可见时若视角已在某方位区域内，重新触发一次进入事件。
                heading.clearCardinalZone();
            }
        }
        wasVisible = visible;
        frameVisible = visible;
        if (!visible) {
            lastFrameMillis = now;
            frame = null;
            return;
        }

        // 目标航向：实机用玩家视角（带插值）。
        float target;
        {
            Player player = mc.player;
            if (player == null) {
                frame = null;
                return;
            }
            if (displayedPlayer != player) {
                displayedPlayer = player;
                heading.snapTo(CompassHeading.toHeading(player.getYRot()));
            }
            target = CompassHeading.toHeading(player.getViewYRot(context.partialTick));
        }

        float snapRange = CompassConfig.SNAP_ASSIST.get()
                ? CompassConfig.SNAP_RANGE.get().floatValue()
                : 0f;
        heading.step(target, seconds, (float) CompassConfig.SMOOTHNESS.get().doubleValue(), snapRange);

        if (!preview) {
            // “进入基数方位区域”边沿事件（原始视角判定，快扫不漏、重进重发）。
            int cardinalEvent = heading.pollCardinalEvent();
            if (cardinalEvent >= 0) {
                CompassHub.fireCardinal(cardinalEvent);
            }
        }

        float entryProgress = 1f;
        float entryAlpha = 1f;
        if (entryStart != Long.MIN_VALUE) {
            float p = Mth.clamp((now - entryStart) / 450f, 0f, 1f);
            entryProgress = p;
            entryAlpha = 1f - (1f - p) * (1f - p); // ease-out
            if (p >= 1f) entryStart = Long.MIN_VALUE;
        }

        CompassPalette palette = currentPalette();
        CompassStyle style = currentStyle();
        float alpha = (float) CompassConfig.OPACITY.get().doubleValue() * entryAlpha;

        // 预览保真：预览实例不再挂在 LDLib UI 树上（旧版 syncPreviewLayout 已删），
        // 设置界面经 renderStandalone 直接传入几何。

        float width = getSizeWidth();
        float height = Mth.clamp(style.widgetHeight(), 40f, 72f);
        frame = new CompassStyleContext(getPositionX(), getPositionY(), width, height,
                heading.smooth(), heading.velocityDegPerSec(), heading.cardinalGlow(),
                heading.nearestCardinal(), alpha, entryProgress, now, palette, style);
    }

    private float secondsSinceLastFrame(Minecraft mc, long now) {
        if (lastFrameMillis == Long.MIN_VALUE) {
            lastFrameMillis = now;
            return 0.016f;
        }
        float seconds = (now - lastFrameMillis) / 1000f;
        lastFrameMillis = now;
        return Mth.clamp(seconds, 0f, 0.1f);
    }

    private boolean isVisible(Minecraft mc) {
        if (preview) return true;
        if (!CompassConfig.ENABLED.get()) return false;
        if (mc.options.hideGui || mc.level == null) return false;
        if (!(mc.getCameraEntity() instanceof Player player) || player.isSpectator()) return false;
        if (CompassConfig.HIDE_WITH_DEBUG.get() && isDebugScreenOpen(mc)) return false;
        return true;
    }

    private static boolean isDebugScreenOpen(Minecraft mc) {
        try {
            return mc.gui.getDebugOverlay().showDebugScreen();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static CompassPalette currentPalette() {
        // 零分配缓存：逐帧调用时只做 6 次配置读取 + 整数比较，
        // 仅在配色/覆盖真正变化时重建（含 distinctFrom 的 6 次 RGBtoHSB）。
        // 预色解析后比较，外部热重载改文件也能被感知。
        CompassPalette base = CompassPalette.byId(CompassConfig.PALETTE.get());
        int accent = CompassPalette.resolve(CompassConfig.COLOR_ACCENT.get(), base.accent());
        int text = CompassPalette.resolve(CompassConfig.COLOR_TEXT.get(), base.text());
        int dim = CompassPalette.resolve(CompassConfig.COLOR_DIM.get(), base.dim());
        int tick = CompassPalette.resolve(CompassConfig.COLOR_TICK.get(), base.tick());
        int background = CompassPalette.resolve(CompassConfig.COLOR_BACKGROUND.get(), base.background());
        if (cachedPalette == null || !cachedPalette.id().equals(base.id())
                || cachedPalette.accent() != accent || cachedPalette.text() != text
                || cachedPalette.dim() != dim || cachedPalette.tick() != tick
                || cachedPalette.background() != background) {
            // 标点色运行时保障与 accent 可区分（覆盖撞色时自动偏移）。
            cachedPalette = new CompassPalette(base.id(), accent, text, dim, tick, background,
                    CompassPalette.distinctFrom(accent, base.markerEnemy()),
                    CompassPalette.distinctFrom(accent, base.markerLocation()),
                    CompassPalette.distinctFrom(accent, base.markerItem()));
        }
        return cachedPalette;
    }

    private static CompassPalette cachedPalette;

    public static CompassStyle currentStyle() {
        return CompassStyle.byId(CompassConfig.STYLE.get());
    }

    // ==================== 绘制 ====================

    @Override
    public void drawBackgroundTexture(GUIContext context) {
        if (preview) return; // 预览实例仅经 renderStandalone 绘制
        updateState(context);
        if (frame == null || !frameVisible || frame.alpha <= 0.01f) return;
        GuiGraphics g = context.graphics;
        effectiveScale = (float) CompassConfig.SCALE.get().doubleValue();
        g.pose().pushPose();
        try {
            applyEntryOffset(g);
            applyScale(g, -1f);
            currentStyle().drawBackground(frame, g);
        } finally {
            g.pose().popPose();
        }
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        if (preview) return; // 预览实例仅经 renderStandalone 绘制
        if (frame == null || !frameVisible || frame.alpha <= 0.01f) return;
        GuiGraphics g = context.graphics;
        Font font = com.lowdragmc.lowdraglib2.gui.LDLibFonts.font();
        effectiveScale = (float) CompassConfig.SCALE.get().doubleValue();
        g.pose().pushPose();
        try {
            applyEntryOffset(g);
            applyScale(g, -1f);
            drawTicksAndLabels(g, font);
            drawMarkers(g, font, context.partialTick);
            currentStyle().drawCenter(frame, font, g);
            currentStyle().drawForeground(frame, g);
        } finally {
            g.pose().popPose();
        }
    }

    /**
     * 独立渲染（设置界面预览专用）：不经过 LDLib UI 树，直接以给定几何绘制。
     * 与 HUD 完全同一套绘制路径（同弹簧、同皮肤、同标点逻辑），仅状态源不同。
     *
     * @param scaleOverride 预览等效缩放：同时考虑配置缩放与预览盒可用宽高，
     *                      避免大缩放/超宽条带溢出预览盒（旧版直接用配置 SCALE 会溢出）。
     */
    void renderStandalone(GuiGraphics g, Font font, float x, float y, float w, long now, float scaleOverride) {
        float seconds = standaloneDt(now);
        if (sway) {
            swayPreview(now);
        }
        float snapRange = CompassConfig.SNAP_ASSIST.get()
                ? CompassConfig.SNAP_RANGE.get().floatValue()
                : 0f;
        heading.step(previewTarget, seconds, (float) CompassConfig.SMOOTHNESS.get().doubleValue(), snapRange);

        CompassPalette palette = currentPalette();
        CompassStyle style = currentStyle();
        float alpha = (float) CompassConfig.OPACITY.get().doubleValue();
        float height = Mth.clamp(style.widgetHeight(), 40f, 72f);
        frame = new CompassStyleContext(x, y, w, height,
                heading.smooth(), heading.velocityDegPerSec(), heading.cardinalGlow(),
                heading.nearestCardinal(), alpha, 1f, now, palette, style);
        frameVisible = true;
        effectiveScale = scaleOverride;

        g.pose().pushPose();
        try {
            // v1.5.3：预览缩放以条带自身中心为锚（水平 + 垂直双轴）。
            // 旧版复用 HUD 的 applyScale（x 锚条带中心、y 锚屏幕原点 0），fitScale < 1 时
            // 条带垂直方向向 y=0 收缩而整体上漂；调用方又按缩放后宽度定位左上角（水平再漂移），
            // 双重错位叠加 → 调宽条带 / 大缩放 / 高皮肤时演示滑出演示盒。
            // HUD 路径的 y=0 锚点保持不变：全屏拖拽定位的坐标换算（hudTopY = offset_y × scale）依赖它。
            if (Math.abs(scaleOverride - 1f) > 0.001f) {
                float cx = x + w / 2f;
                float cy = y + height / 2f;
                g.pose().translate(cx, cy, 0);
                g.pose().scale(scaleOverride, scaleOverride, 1);
                g.pose().translate(-cx, -cy, 0);
            }
            style.drawBackground(frame, g);
            drawTicksAndLabels(g, font);
            drawMarkers(g, font, 0);
            style.drawCenter(frame, font, g);
            style.drawForeground(frame, g);
        } finally {
            g.pose().popPose();
        }
    }

    /** 独立渲染的帧间隔（秒，限制在 0~0.1 防止掉帧后跳变）。 */
    private float standaloneDt(long now) {
        if (standaloneLastMillis == Long.MIN_VALUE) {
            standaloneLastMillis = now;
            return 0.016f;
        }
        float seconds = (now - standaloneLastMillis) / 1000f;
        standaloneLastMillis = now;
        return Mth.clamp(seconds, 0f, 0.1f);
    }

    private long standaloneLastMillis = Long.MIN_VALUE;

    /**
     * 入场滑落：出现时从上方 8px 平滑滑入（与淡入同步的 ease-out）。
     * 布局热同步不会重播入场动画（entryStart 只在“不可见 -> 可见”边沿重置），
     * 因此拖动设置滑条不会触发本效果。
     */
    private void applyEntryOffset(GuiGraphics g) {
        if (frame == null || frame.entryProgress >= 1f) return;
        float p = frame.entryProgress;
        g.pose().translate(0, -8f * (1f - p) * (1f - p), 0);
    }

    /** 本帧渲染的等效整体缩放（HUD = 配置值；预览 = 盒内适配值），密度自适应用。 */
    private float effectiveScale = 1f;

    /** 以控件水平中心为锚点应用整体缩放（scale <= 0 时读配置）。 */
    private void applyScale(GuiGraphics g, float scaleOverride) {
        float scale = scaleOverride > 0f ? scaleOverride : (float) CompassConfig.SCALE.get().doubleValue();
        if (Math.abs(scale - 1f) < 0.001f) return;
        float cx = frame != null ? frame.centerX : getPositionX() + getSizeWidth() / 2f;
        g.pose().translate(cx, 0, 0);
        g.pose().scale(scale, scale, 1);
        g.pose().translate(-cx, 0, 0);
    }

    private void drawTicksAndLabels(GuiGraphics g, Font font) {
        CompassStyle style = currentStyle();
        // 密度自适应：视觉每度像素（含整体缩放）。条带拉宽/放大后相邻刻度变稀时
        // 自动改用更小步长加密刻度与数字（字体与刻度长度不变，只加数量），
        // 让拉长后的条带在视觉密度上与拉宽前一致。
        double pxPerDeg = (frame.width / 2f - 6f) / (frame.range / 2f) * Math.max(0.01f, effectiveScale);
        int minorStep = CompassSteps.effectiveMinorStep(Math.max(5, CompassConfig.MINOR_STEP.get()), pxPerDeg);
        int numberStep = CompassSteps.effectiveNumberStep(
                Math.max(minorStep, CompassConfig.NUMBER_STEP.get()), pxPerDeg);
        boolean showNumbers = CompassConfig.SHOW_NUMBERS.get();
        boolean showCardinals = CompassConfig.SHOW_CARDINALS.get();
        boolean showIntercardinals = CompassConfig.SHOW_INTERCARDINALS.get();
        float half = frame.range / 2f;
        float from = frame.heading - half;
        float to = frame.heading + half;

        // 四个独立通道各自按自己的步长对齐渲染，互不依赖整除关系：
        // 旧实现只有 minorStep 单一网格，minorStep=10 时 45 不是它的倍数，
        // 东北/东南/西南/西北标签会全部消失（手改配置成 7 连 N/E/S/W 都丢）。
        // 优先级与旧实现一致：基数 > 次方位 > 数字 > 次级刻度。

        // 1) 次级刻度：跳过被更高优先级占用的角度，避免同一位置双画。
        for (int deg = floorStep(from, minorStep); deg <= to; deg += minorStep) {
            int wrapped = Math.floorMod(deg, 360);
            if (wrapped % 45 == 0 || wrapped % numberStep == 0) continue;
            drawTickOnly(style, g, deg, CompassStyle.TickKind.MINOR, 0.9f);
        }
        // 2) 角度数字（非 45 倍数；即使关闭数字显示也保留 MAJOR 刻度，与旧实现一致）。
        for (int deg = floorStep(from, numberStep); deg <= to; deg += numberStep) {
            int wrapped = Math.floorMod(deg, 360);
            if (wrapped % 45 == 0) continue;
            float x = xOf(deg);
            if (Float.isNaN(x)) continue;
            float alpha = elementAlpha(x);
            if (showNumbers) {
                style.drawNumber(frame, font, g, x, wrapped, alpha);
            }
            style.drawTick(frame, g, x, CompassStyle.TickKind.MAJOR, wrapped, alpha);
        }
        // 3) 次方位（45 倍数非 90 倍数；关闭显示时仍保留 MAJOR 刻度）。
        for (int deg = floorStep(from, 45); deg <= to; deg += 45) {
            int wrapped = Math.floorMod(deg, 360);
            if (wrapped % 90 == 0) continue;
            float x = xOf(deg);
            if (Float.isNaN(x)) continue;
            float alpha = elementAlpha(x);
            if (showIntercardinals) {
                style.drawIntercardinal(frame, font, g, x, intercardinalName(wrapped), alpha);
            }
            style.drawTick(frame, g, x, CompassStyle.TickKind.MAJOR, wrapped, alpha);
        }
        // 4) 基数方位（90 倍数；关闭显示时仍保留 CARDINAL 刻度）。
        for (int deg = floorStep(from, 90); deg <= to; deg += 90) {
            int wrapped = Math.floorMod(deg, 360);
            float x = xOf(deg);
            if (Float.isNaN(x)) continue;
            float alpha = elementAlpha(x);
            if (showCardinals) {
                boolean nearest = wrapped == frame.nearestCardinal;
                style.drawCardinal(frame, font, g, x, cardinalName(wrapped), nearest, alpha);
            }
            style.drawTick(frame, g, x, CompassStyle.TickKind.CARDINAL, wrapped, alpha);
        }
    }

    private static int floorStep(float from, int step) {
        return (int) Math.floor(from / step) * step;
    }

    /** 角度 -> x 坐标；超出条带可视范围（含 8px 余量）返回 NaN 表示跳过。 */
    private float xOf(int degrees) {
        float x = frame.degreesToX(degrees);
        if (x < frame.originX - 8f || x > frame.originX + frame.width + 8f) return Float.NaN;
        return x;
    }

    private float elementAlpha(float x) {
        return frame.alpha * frame.edgeFade(x) * stagger(x);
    }

    /** 次级刻度专用（无标签，只有刻度线）。 */
    private void drawTickOnly(CompassStyle style, GuiGraphics g, int degrees,
                              CompassStyle.TickKind kind, float alphaScale) {
        float x = xOf(degrees);
        if (Float.isNaN(x)) return;
        style.drawTick(frame, g, x, kind, Math.floorMod(degrees, 360), elementAlpha(x) * alphaScale);
    }

    /** 入场动画的错峰系数：中央元素先出现。 */
    private float stagger(float x) {
        if (frame.entryProgress >= 1f) return 1f;
        float t = Math.abs(x - frame.centerX) / (frame.width / 2f);
        return Mth.clamp(frame.entryProgress * 1.7f - t * 0.7f, 0f, 1f);
    }

    private void drawMarkers(GuiGraphics g, Font font, float partialTick) {
        if (!CompassConfig.MARKERS_ENABLED.get()) return;
        Minecraft mc = Minecraft.getInstance();
        // 用帧内插值取眼睛位置，避免移动中标点相对条带抖动。
        Vec3 eye = preview || mc.player == null ? Vec3.ZERO : mc.player.getEyePosition(partialTick);
        List<CompassMark> marks = preview ? previewMarks() : collectLiveMarks(partialTick, eye);
        if (marks.isEmpty()) return;
        CompassStyle style = currentStyle();
        boolean showDistance = CompassConfig.MARKERS_DISTANCE.get();
        boolean showLabels = CompassConfig.MARKERS_LABELS.get();
        float halfRange = frame.range / 2f;
        float edgeInset = Math.max(2f, frame.width * 0.012f);
        for (CompassMark mark : marks) {
            float bearing = preview ? previewBearingOf(mark) : bearingBetween(eye, mark.position());
            // 屏外标点边缘吸附：方位差在 (半视野, 全视野] 内的标点钳制到对应边缘并压暗，
            // 提示“目标在视野外不远处、朝这个方向转”；超过全视野（几乎在身后）则不显示。
            float diff = CompassHeading.wrapDegrees(bearing - frame.heading);
            float x;
            float alpha;
            boolean clampedLeft = false;
            boolean clampedRight = false;
            if (diff < -halfRange) {
                if (diff < -frame.range) continue;
                x = frame.originX + edgeInset;
                alpha = frame.alpha * 0.5f;
                clampedLeft = true;
            } else if (diff > halfRange) {
                if (diff > frame.range) continue;
                x = frame.originX + frame.width - edgeInset;
                alpha = frame.alpha * 0.5f;
                clampedRight = true;
            } else {
                x = frame.degreesToX(bearing);
                alpha = frame.alpha * frame.edgeFade(x);
            }
            // 标点下方文字行：标签 + 距离合成一行（各自可独立关闭）。
            String text = null;
            String dist = null;
            if (mark.showDistance() && showDistance) {
                if (preview) {
                    dist = previewDistanceOf(mark);
                } else {
                    int meters = (int) Math.floor(eye.distanceTo(mark.position()));
                    dist = meters + "m";
                }
            }
            String label = showLabels && mark.label() != null ? mark.label().getString() : null;
            if (label != null && dist != null) {
                text = label + " " + dist;
            } else {
                text = label != null ? label : dist;
            }
            style.drawMarker(frame, font, g, mark, x, alpha, text);
            // 屏外标点方向箭头：吸附到边缘时在标点内侧加一个小箭头（箭头尖朝向目标方向），
            // 明确指示“往哪边转才能看到目标”。画在内侧避免被控件边界裁剪。
            if (clampedLeft) {
                CompassPaint.chevronLeft(g, x + 7.5f, frame.originY + style.markerY(), 3f, mark.color(), alpha * 0.8f);
            } else if (clampedRight) {
                CompassPaint.chevronRight(g, x - 7.5f, frame.originY + style.markerY(), 3f, mark.color(), alpha * 0.8f);
            }
        }
    }

    private List<CompassMark> collectLiveMarks(float partialTick, Vec3 eye) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.player instanceof LocalPlayer player) || mc.level == null) return List.of();
        List<CompassMark> result = null;
        // 1) 战术标点（只读桥接，同样传入插值保证移动目标平滑）
        if (CompassConfig.MARKERS_TACTICAL.get()) {
            List<CompassMark> tactical = CompassMarkerBridge.collect(frame.palette, partialTick);
            if (!tactical.isEmpty()) {
                result = new ArrayList<>(tactical);
            }
        }
        // 2) 测试标点
        if (CompassConfig.MARKERS_TEST.get() && !CompassHub.testMarks().isEmpty()) {
            if (result == null) result = new ArrayList<>();
            result.addAll(CompassHub.testMarks());
        }
        // 3) 死亡标点（同维度才显示；玩家靠近后自动清除，取回装备后不再打扰）。
        // 靠近清除仅对活着的玩家生效：死亡瞬间玩家就在死亡点上（距离≈0），
        // 若不排除会把刚生成的标点立即清掉。
        if (CompassConfig.MARKERS_DEATH.get() && CompassHub.hasDeathMark(player.level().dimension())) {
            boolean near = !player.isDeadOrDying()
                    && eye.distanceToSqr(CompassHub.deathPosition())
                            <= CompassHub.DEATH_MARK_CLEAR_RANGE * CompassHub.DEATH_MARK_CLEAR_RANGE;
            if (near) {
                CompassHub.clearDeathMark();
            } else {
                CompassMark death = CompassHub.deathMark(frame.palette);
                if (death != null) {
                    if (result == null) result = new ArrayList<>();
                    result.add(death);
                }
            }
        }
        // 4) 外部提供者
        var providers = CompassHub.providers();
        if (!providers.isEmpty()) {
            if (result == null) result = new ArrayList<>();
            var context = new CompassMarkerProvider.Context(mc.level, player, frame.now);
            for (CompassMarkerProvider provider : providers) {
                try {
                    provider.collectMarkers(context, result::add);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return result != null ? result : List.of();
    }

    /**
     * 当前活动标点总数（{@code /compass info} 诊断用，主线程调用）。
     * 与 {@link #collectLiveMarks} 同源（战术桥 + 测试 + 死亡 + 外部提供者），
     * 但只读无副作用：不触发死亡标点的靠近自动清除，也不依赖渲染帧状态。
     */
    static int countLiveMarks() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.player instanceof LocalPlayer player) || mc.level == null) return 0;
        if (!CompassConfig.MARKERS_ENABLED.get()) return 0;
        int count = 0;
        if (CompassConfig.MARKERS_TACTICAL.get()) {
            // partialTick 只影响颜色/位置插值，不影响数量，传 0 即可。
            count += CompassMarkerBridge.collect(currentPalette(), 0).size();
        }
        if (CompassConfig.MARKERS_TEST.get()) {
            count += CompassHub.testMarks().size();
        }
        if (CompassConfig.MARKERS_DEATH.get() && CompassHub.hasDeathMark(player.level().dimension())) {
            count++;
        }
        List<CompassMarkerProvider> providers = CompassHub.providers();
        if (!providers.isEmpty()) {
            List<CompassMark> sink = new ArrayList<>();
            var context = new CompassMarkerProvider.Context(mc.level, player, System.currentTimeMillis());
            for (CompassMarkerProvider provider : providers) {
                try {
                    provider.collectMarkers(context, sink::add);
                } catch (RuntimeException ignored) {
                }
            }
            count += sink.size();
        }
        return count;
    }

    // ==================== 预览演示数据 ====================

    private List<CompassMark> previewMarks;
    private CompassPalette previewMarksPalette;

    private List<CompassMark> previewMarks() {
        // 缓存键用当前生效配色实例：仅配色变化时重建，其余帧零分配。
        // 预览标点使用与实际战术标点同款的颜色与图标（红感叹号 / 白菱形 / 物品贴图），
        // 设置界面里看到的就是游戏内的样子。
        CompassPalette palette = currentPalette();
        if (previewMarks == null || previewMarksPalette != palette) {
            previewMarksPalette = palette;
            previewMarks = List.of(
                    CompassMark.of("preview-enemy", CompassMark.Kind.ENEMY, Vec3.ZERO,
                            0xFF4949,
                            Component.translatable("draginventory.compass.marker.enemy"), true,
                            System.currentTimeMillis(), null),
                    CompassMark.of("preview-location", CompassMark.Kind.LOCATION, Vec3.ZERO,
                            0xFFFFFF,
                            Component.translatable("draginventory.compass.marker.location"), true,
                            System.currentTimeMillis(), null),
                    CompassMark.of("preview-item", CompassMark.Kind.ITEM, Vec3.ZERO,
                            0xFFFFFF,
                            Component.translatable("draginventory.compass.marker.item"), true,
                            System.currentTimeMillis(),
                            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND)));
        }
        return previewMarks;
    }

    private float previewBearingOf(CompassMark mark) {
        int index = switch (mark.key().toString()) {
            case "preview-enemy" -> 0;
            case "preview-location" -> 1;
            default -> 2;
        };
        return Mth.wrapDegrees(previewBase + PREVIEW_BEARINGS[index]);
    }

    private String previewDistanceOf(CompassMark mark) {
        return switch (mark.key().toString()) {
            case "preview-enemy" -> "37m";
            case "preview-location" -> "124m";
            default -> "58m";
        };
    }

    // ==================== 文本 ====================

    static String cardinalName(int degrees) {
        boolean cjk = CompassConfig.CJK_LABELS.get();
        return switch (degrees) {
            case 0 -> cjk ? "北" : "N";
            case 90 -> cjk ? "东" : "E";
            case 180 -> cjk ? "南" : "S";
            case 270 -> cjk ? "西" : "W";
            default -> String.valueOf(degrees);
        };
    }

    static String intercardinalName(int degrees) {
        boolean cjk = CompassConfig.CJK_LABELS.get();
        return switch (degrees) {
            case 45 -> cjk ? "东北" : "NE";
            case 135 -> cjk ? "东南" : "SE";
            case 225 -> cjk ? "西南" : "SW";
            case 315 -> cjk ? "西北" : "NW";
            default -> String.valueOf(degrees);
        };
    }

    /** 玩家眼睛位置 -> 目标位置的罗盘方位（0 = 北，顺时针）。 */
    static float bearingBetween(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        float bearing = (float) Math.toDegrees(Math.atan2(dx, -dz));
        return bearing < 0 ? bearing + 360f : bearing;
    }
}
