package dev.draginventory.client.map;

import static dev.draginventory.client.map.FactoryMapUI.*;

import dev.draginventory.client.TacticalMarkerManager;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 左上角常驻方形小地图（反馈⑨，Xaero / 三角洲风格）。
 *
 * <p><b>渲染管线</b>（复用大地图的瓦片渲染器，零新增采样逻辑）：
 * <ul>
 *   <li>楼层：缓存玩家所在楼层（{@code resolvePlayerFloor}），仅当玩家所在方块坐标
 *       变化或每 20 帧才重算，避免每帧方块查询；</li>
 *   <li>视图：合成活动层（{@link MinimapView}）——把玩家楼层包装成单层目录喂给
 *       {@link FactoryMapTiles}，<b>不做任何 discover / 目录扫描</b>（catalog 为空 →
 *       渲染器不画上下叠加层，小地图只显示当前层，零 discover 开销）；</li>
 *   <li>瓦片：独立静态 {@link FactoryMapTiles} 实例（独立 LRU 池，与大地图屏幕互不
 *       干扰）。tiles 自带换世界失效（draw 首检 lastWorld），静态常驻无需显式关闭；</li>
 *   <li>标点：{@code markers.visible} 开启时按楼层 ±3 过滤，画 5×5 类型色方块 +
 *       中心 1×1 深色点（不画序号/距离，小地图保持干净）；</li>
 *   <li>玩家箭头始终居中指向朝向；顶部中央 "N" 北向指示；四角 3px ACCENT 角标
 *       （战术感）；下方居中一行倒计时数字（{@link MapMatchTimer}，v2.6.0 起只显示
 *       m:ss 数字本体，不再携带前后缀文案；v2.6.1 起归零后的结束字样停留约
 *       2.5 秒即整行消失）。</li>
 * </ul></p>
 *
 * <p>守卫：F1（hideGui）、任意界面打开（含大地图/设置界面/聊天）、旁观模式、
 * {@code general.enabled} 或 {@code minimap.enabled} 关闭时全部隐藏。
 * 注册见 {@link FactoryMapHudRegistration}（registerAboveAll）。</p>
 */
final class FactoryMinimapHud implements LayeredDraw.Layer {
    /** 错误修正：NeoForge 的 LayeredDraw 对层渲染无异常保护，任何一层抛异常会中断
     * 整条 HUD 渲染链并崩溃游戏。小地图是每帧执行的常驻层，必须自保：异常时记日志
     * 并暂时停用小地图，游戏本体与其余 HUD 不受影响。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("DragInventory/MinimapHud");
    /** 熔断后到自动重试的间隔（毫秒）：约 10 秒，给瞬态故障（资源重载等）恢复时间。 */
    private static final long RETRY_DELAY_MS = 10_000L;
    /** 连续熔断次数达到上限后本会话永久停用（防御真正的死循环异常）。 */
    private static final int MAX_FAILURES = 3;

    /** 熔断状态（v2.5.2 从“永久布尔”改为可恢复）：brokenUntil = 0 表示正常；
     * 否则为“停用至该时刻”。换世界会全部重置——v2.5.1 的永久布尔标志在跨世界后
     * 仍然生效，导致“旧世界熔断一次 → 新世界小地图再也不显示且设置开关无效”。 */
    private static long brokenUntil;
    private static int failureCount;
    private static long lastLoggedAt;

    /** 小地图独立瓦片池（静态常驻：换世界自动失效重烤，见类注释）。 */
    private static final FactoryMapTiles TILES = new FactoryMapTiles();

    // ==================== 对局计时行（v2.5.5：加粗 + 醒目色 + 流动动效） ====================

    /** 时间值常态色：醒目金（深色底幕上高对比，符合“结束时间醒目”反馈）。 */
    private static final int TIMER_GOLD = 0xFFFFC84A;
    /** 最后 60 秒转红（比金色更紧迫）。 */
    private static final int TIMER_RED = 0xFFFF6B5E;
    /** 秒位跳动脉冲时长（毫秒）。 */
    private static final long TIMER_PULSE_MS = 300L;
    /** 归零后结束字样的停留时长（毫秒，v2.6.1）：显示约 2.5 秒后整行消失
     * （用户给定 2~3 秒区间的中值，常量可调）。 */
    private static final long TIMER_OVER_LINGER_MS = 2_500L;
    /** 上一次渲染的剩余秒数与变化时刻（秒位跳动脉冲的触发沿）。 */
    private static long lastTimerSecond = Long.MIN_VALUE;
    private static long timerSecondChangedAt;

    /** 加粗文字宽度（Font.width(String) 不含粗体加宽，必须用带样式的组件置测）。 */
    private static int boldWidth(Font font, String s) {
        return s.isEmpty() ? 0 : font.width(Component.literal(s).withStyle(boldStyle()));
    }

    private static Style boldStyle() { return Style.EMPTY.withBold(true); }

    /** 绘制加粗文字（颜色走样式，与 boldWidth 同口径）。 */
    private static void drawBold(GuiGraphics g, Font font, String s, int x, int y, int color) {
        if (s.isEmpty()) return;
        g.drawString(font, Component.literal(s).withStyle(boldStyle().withColor(color)), x, y, 0xFFFFFF, false);
    }

    /** 把 RGB 通道压暗到 (1-factor)：呼吸/闪烁动效用亮度摆动实现，不依赖样式透明度。 */
    private static int dim(int argb, float factor) {
        int r = (int) (((argb >>> 16) & 0xFF) * (1f - factor));
        int gr = (int) (((argb >>> 8) & 0xFF) * (1f - factor));
        int b = (int) ((argb & 0xFF) * (1f - factor));
        return 0xFF000000 | (r << 16) | (gr << 8) | b;
    }

       // ==================== 楼层缓存（避免每帧方块查询） ====================

    /** 缓存的玩家楼层 Y（NO_FLOOR = 尚未计算，首帧触发）。 */
    private static int lastFloor = FactoryMapLayerResolver.NO_FLOOR;
    private static int lastBlockX, lastBlockY, lastBlockZ;
    private static int frameCount;
    /** 楼层缓存所属的世界（身份比较）：换世界时重置楼层缓存与熔断状态（v2.5.2）。 */
    private static Object floorCacheWorld;

    /** 小地图到渲染器的最小视图：合成活动层 + 空目录（无叠加层，无 discover）。 */
    private record MinimapView(FactoryMapLayerResolver.Layer layer) implements FactoryMapView {
        @Override public FactoryMapLayerResolver.Layer activeLayer() { return layer; }
        @Override public FactoryMapLayerResolver.LayerCatalog catalog() {
            return FactoryMapLayerResolver.LayerCatalog.empty();
        }
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker dt) {
        long now = net.minecraft.Util.getMillis();
        if (brokenUntil != 0) {
            if (now < brokenUntil) return;
            if (failureCount >= MAX_FAILURES) return;
            brokenUntil = 0;  // 冷却结束，重试一次
        }
        try {
            renderInner(g, dt);
        } catch (Exception e) {
            failureCount++;
            brokenUntil = now + RETRY_DELAY_MS;
            // 同一熔断周期内只记一次完整堆栈，避免每帧刷屏。
            if (now - lastLoggedAt > RETRY_DELAY_MS) {
                lastLoggedAt = now;
                LOGGER.error("Factory minimap HUD failed to render (attempt {}/{}); minimap paused for {}ms. "
                        + "Root cause:", failureCount, MAX_FAILURES, RETRY_DELAY_MS, e);
            }
        }
    }

    private void renderInner(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.player.isSpectator()) return;
        if (mc.options.hideGui || mc.screen != null || mc.getOverlay() != null) return;
        if (!MapConfig.ENABLED.get() || !MapConfig.MINIMAP_ENABLED.get()) return;

        // 换世界检测（v2.5.2）：重置楼层缓存与熔断状态——进入新世界/新存档不应继承
        // 旧世界的熔断（旧版的“新游戏小地图不显示”根因之一）；v2.5.5 同时重置计时
        // 动效状态（脉冲沿）；v2.5.6 重置玩家图标动效（平滑朝向/尾迹不跨世界残留）。
        if (floorCacheWorld != mc.level) {
            floorCacheWorld = mc.level;
            lastFloor = FactoryMapLayerResolver.NO_FLOOR;
            brokenUntil = 0;
            failureCount = 0;
            lastTimerSecond = Long.MIN_VALUE;
            PlayerMarkerFX.INSTANCE.reset();
        }

        // 楼层缓存：所在方块坐标变化，或每 20 帧（方块被放置/破坏不需移动也会改楼层）。
        // 死区防抖（±2 格，与大地图 resolveAutoFloor 同口径）：楼层边界小幅抖动
        // 不触发换层，避免瓦片键（含 floorY）反复失效重烤导致小地图闪烁。
        // v2.5.2：解析后对齐到共享层目录（大地图会话维护）——两个视图显示同一层。
        frameCount++;
        int bx = Mth.floor(mc.player.getX()), by = Mth.floor(mc.player.getY()), bz = Mth.floor(mc.player.getZ());
        if (lastFloor == FactoryMapLayerResolver.NO_FLOOR
                || bx != lastBlockX || by != lastBlockY || bz != lastBlockZ
                || frameCount % 20 == 0) {
            lastBlockX = bx;
            lastBlockY = by;
            lastBlockZ = bz;
            int resolved = FactoryMapLayerResolver.resolvePlayerFloor(mc.level, mc.player);
            resolved = FactoryMapLayerResolver.alignToSharedCatalog(mc.level, resolved);
            if (lastFloor == FactoryMapLayerResolver.NO_FLOOR || Math.abs(resolved - lastFloor) > 2) {
                lastFloor = resolved;
            }
        }

        int size = MapConfig.MINIMAP_SIZE.get();
        int x = 6, y = 6;
        // 底幕：近实心深色，保证未加载区域的标点/箭头可读。
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xE609151B);
        var t = new MapCoordinateTransform(mc.player.getX(), mc.player.getZ(),
                MapConfig.MINIMAP_ZOOM.get(), x, y, size, size);
        var view = new MinimapView(new FactoryMapLayerResolver.Layer(
                lastFloor, lastFloor, 0, 0, 0, 0, 1, List.of()));
        g.enableScissor(x, y, x + size, y + size);
        TILES.draw(g, mc, t, view);
        if (MapConfig.MARKERS_VISIBLE.get()) {
            float partial = dt.getGameTimeDeltaPartialTick(true);
            for (var marker : TacticalMarkerManager.snapshot(partial)) {
                var pos = marker.position(partial);
                if (Math.abs(pos.y - 1 - lastFloor) > 3) continue; // 与大地图同口径：楼层 ±3
                int mx = Mth.floor(t.worldToScreenX(pos.x)), my = Mth.floor(t.worldToScreenY(pos.z));
                int color = color(marker.type());
                g.fill(mx - 2, my - 2, mx + 3, my + 3, color); // 5×5 类型色方块
                g.fill(mx, my, mx + 1, my + 1, 0xFF0B171D);    // 中心 1×1 深色点
            }
        }
        // 玩家导航箭头（v2.5.7 依用户 SVG 重做）：接入动效——尾迹残影（身后渐隐，越快越明显）
        // 先画（垫在下层），主图标最后（平滑转向 + 速度弹性拉伸 + 静止呼吸）。
        // v2.5.7 动效优化：喂 partialTick 插值坐标/朝向——消除 20Hz tick 台阶，速度估计
        // 连续（拉伸/尾迹不再锯齿）；小地图图标恒居中，无需位置插值。
        PlayerMarkerFX fx = PlayerMarkerFX.INSTANCE;
        float partial = dt.getGameTimeDeltaPartialTick(true);
        var ipos = mc.player.getPosition(partial);
        fx.update(ipos.x, ipos.z, mc.player.getYRot());
        for (var s : fx.trail()) {
            player(g, (float) t.worldToScreenX(s.x()), (float) t.worldToScreenY(s.z()),
                    s.yaw(), s.alpha(), fx.stretch(), s.scale());
        }
        player(g, x + size / 2, y + size / 2, fx.smoothYaw(), 1f, fx.stretch(), fx.drawScale(Util.getMillis()));
        g.disableScissor();

        // 边框 + 四角 3px ACCENT 角标（战术感，参考三角洲）。
        box(g, x - 1, y - 1, size + 2, size + 2, 0xFF2D3748);
        corner(g, x - 1, y - 1, 1, 1);          // 左上
        corner(g, x + size, y - 1, -1, 1);      // 右上
        corner(g, x - 1, y + size, 1, -1);      // 左下
        corner(g, x + size, y + size, -1, -1);  // 右下
        // 顶部中央 "N" 北向指示（GREEN）。
        text(g, "N", x + (size - mc.font.width("N")) / 2, y + 1, GREEN);

        // 对局计时（v2.6.0 只留数字）：仅 m:ss 数字本体居中于小地图下方，无前后缀文案；
        // 加粗；醒目金、最后 60 秒转红加速闪烁；每秒秒位跳变脉冲 + 冒号 1Hz 呼吸保留；
        // 归零显示“对局已结束”。
        drawMatchTimer(g, mc, x, y + size + 4, size);
    }

    /**
     * 对局倒计时数字（v2.6.0 起<b>只留数字本体</b>，用户反馈移除“对局还有…结束”前后缀）：
     * 小地图下方居中显示 m:ss（分钟可超 59），整段加粗；颜色不变——常态醒目金、
     * 最后 60 秒转红；动效全保留——每秒秒位跳变脉冲缩放、常态冒号 1Hz 呼吸、紧急段
     * 2Hz 闪烁；归零显示“对局已结束”（红色居中）；超宽（极长倒计时 × 最小小地图）时
     * 0.8 缩排兜底（保留旧版行为）。v2.6.1：归零后的结束字样仅停留约 2.5 秒
     * （{@link #TIMER_OVER_LINGER_MS}）即整行消失，不再常驻。
     */
    private static void drawMatchTimer(GuiGraphics g, Minecraft mc, int left, int y, int size) {
        long remaining = MapMatchTimer.remainingSeconds();
        if (remaining < 0) return; // 未在倒计时（/map timer off 且未注册 Provider）：整行隐藏

        Font font = mc.font;
        long now = Util.getMillis();

        if (remaining == 0) { // 归零：红色“对局已结束”，不再显示时间。
            long endedAt = MapMatchTimer.endedAtMillis();
            if (endedAt != 0L && now - endedAt > TIMER_OVER_LINGER_MS) return; // 停留期满：整行消失
            String over = tr("match_timer_over");
            drawBold(g, font, over, left + (size - boldWidth(font, over)) / 2, y, TIMER_RED);
            return;
        }

        boolean urgent = remaining <= 60;
        int timeColor = urgent ? TIMER_RED : TIMER_GOLD;

        // 秒位跳变脉冲：剩余秒数变化沿触发，300ms 正弦升起又落回（紧急时幅度更大）。
        if (remaining != lastTimerSecond) {
            lastTimerSecond = remaining;
            timerSecondChangedAt = now;
        }
        float phase = (now - timerSecondChangedAt) / (float) TIMER_PULSE_MS;
        float pulse = phase >= 0 && phase < 1f
                ? 1f + (urgent ? 0.22f : 0.12f) * (float) Math.sin(phase * Math.PI)
                : 1f;

        // 时间三段：分 : 秒。常态冒号 1Hz 呼吸（亮度摆动）；最后 60 秒改为整段 2Hz 闪烁。
        String time = MapMatchTimer.formatSeconds(remaining);
        int colonIdx = time.lastIndexOf(':');
        String mins = time.substring(0, colonIdx);
        String secs = time.substring(colonIdx + 1);
        int digitColor = timeColor;
        int colonColor = timeColor;
        if (urgent) {
            float blink = 0.5f + 0.5f * (float) Math.sin(now / 1000.0 * Math.PI * 4); // 2Hz
            digitColor = dim(timeColor, 0.42f * blink);
            colonColor = digitColor;
        } else {
            float breath = 0.5f + 0.5f * (float) Math.sin(now / 1000.0 * Math.PI * 2); // 1Hz
            colonColor = dim(timeColor, 0.40f * breath);
        }

        // v2.6.0：不拆前后缀，只测数字本体宽度；小地图下方居中，超宽时 0.8 缩排兜底。
        int minsW = boldWidth(font, mins);
        int colonW = boldWidth(font, ":");
        int secsW = boldWidth(font, secs);
        int timeW = minsW + colonW + secsW;

        float rowScale = timeW > size ? 0.8f : 1f;
        int startX = left + Math.round((size - timeW * rowScale) / 2.0f);

        g.pose().pushPose();
        if (rowScale != 1f) g.pose().scale(rowScale, rowScale, 1f);
        float sx = startX / rowScale;
        float sy = y / rowScale;

        // 数字组：以组中心为锚脉冲缩放（秒位跳变时只有数字在“跳动”）。
        if (pulse != 1f) {
            float anchorX = sx + timeW / 2f, anchorY = sy + 4f;
            g.pose().pushPose();
            g.pose().translate(anchorX, anchorY, 0);
            g.pose().scale(pulse, pulse, 1f);
            g.pose().translate(-anchorX, -anchorY, 0);
            drawBold(g, font, mins, Math.round(sx), Math.round(sy), digitColor);
            drawBold(g, font, ":", Math.round(sx + minsW), Math.round(sy), colonColor);
            drawBold(g, font, secs, Math.round(sx + minsW + colonW), Math.round(sy), digitColor);
            g.pose().popPose();
        } else {
            drawBold(g, font, mins, Math.round(sx), Math.round(sy), digitColor);
            drawBold(g, font, ":", Math.round(sx + minsW), Math.round(sy), colonColor);
            drawBold(g, font, secs, Math.round(sx + minsW + colonW), Math.round(sy), digitColor);
        }
        g.pose().popPose();
    }

    /** 3px ACCENT 角标：横 3×1 + 竖 1×3 组成的 L 形，盖在边框角上（dx/dy = 向内延伸方向 ±1）。 */
    private static void corner(GuiGraphics g, int cx, int cy, int dx, int dy) {
        int hx = dx > 0 ? cx : cx - 2;
        g.fill(hx, cy, hx + 3, cy + 1, ACCENT);
        int vy = dy > 0 ? cy : cy - 2;
        g.fill(cx, vy, cx + 1, vy + 3, ACCENT);
    }
}
