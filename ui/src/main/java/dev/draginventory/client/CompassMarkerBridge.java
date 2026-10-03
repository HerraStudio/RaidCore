package dev.draginventory.client;

import dev.draginventory.client.compass.CompassMark;
import dev.draginventory.client.compass.CompassPalette;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import net.minecraft.world.phys.Vec3;

/**
 * 方位条 &lt;-&gt; 战术标点 的只读桥接。
 * 与 {@code TacticalMarkerManager} 同包以访问其包级私有 MARKERS 表，
 * 绝不写入、不清除、不改变战术标点的任何状态（满足“只读不写”约束）。
 *
 * <p><b>与 3D 战术标点完全同步</b>（v1.5.2 修复“敌人标点消失后方位条上还残留”）：
 * <ul>
 *   <li>整体可见性：3D 标点渲染要求 {@code canInput}（无界面打开 / 玩家存活 /
 *       非旁观等），方位条战术标点遵循同一条件——3D 不显示时方位条也不显示</li>
 *   <li>单个有效性：逐标点复检 {@code marker.valid}（过期 / 目标失效 / 物品被拾取），
 *       与 3D 标点的 maintain 清理同源，双保险防异步时序残留</li>
 *   <li>视觉一致：ENEMY = 红 {@code 0xFF4949} 感叹号、LOCATION = 白 {@code 0xFFFFFF}
 *       菱形、ITEM = 物品贴图本体——与 3D 战术标点的图标和颜色一字不差</li>
 * </ul></p>
 *
 * <p>单个标点的任何异常都被隔离，不会拖垮方位条或其它 HUD 的渲染；
 * 战术标点系统未来新增类型时按 LOCATION 兜底显示。</p>
 */
public final class CompassMarkerBridge {
    private CompassMarkerBridge() {}

    /** 3D 战术标点的固定配色（与 TacticalMarkerHud.draw 完全一致）。 */
    private static final int COLOR_ENEMY = 0xFF4949;
    private static final int COLOR_NORMAL = 0xFFFFFF;

    /** 把当前战术标点转换为方位条标记视图；无标点时返回空列表。partialTick 用于移动目标的平滑插值。 */
    public static List<CompassMark> collect(CompassPalette palette, float partialTick) {
        if (!dev.draginventory.client.compass.CompassConfig.MARKERS_TACTICAL.get()) return List.of();
        Minecraft mc = Minecraft.getInstance();
        // 与 3D 战术标点（TacticalMarkerHud.render）同一可见性条件：
        // 3D 标点不显示时（打开界面 / 玩家死亡 / 旁观等）方位条上的战术标点也一并隐藏。
        if (!TacticalMarkerManager.canInput(mc)) return List.of();
        if (TacticalMarkerManager.hasNoMarkers()) return List.of();
        var markers = TacticalMarkerManager.allMarkers(); // v2.5.7：含外部联动标点（只读，绝不写入）
        long now = Util.getMillis();
        List<CompassMark> result = new ArrayList<>(markers.size());
        for (TacticalMarker marker : markers) {
            // 只读：过期清理由战术标点系统自己的 maintain() 负责；
            // 这里再复检一次 valid()，与 3D 标点同源判定，防止渲染时序差导致方位条残留。
            try {
                if (!marker.valid(mc.level, now)) continue;
                Vec3 position = marker.position(partialTick);
                CompassMark.Kind kind = switch (marker.type()) {
                    case ENEMY -> CompassMark.Kind.ENEMY;
                    case ITEM -> CompassMark.Kind.ITEM;
                    case LOCATION -> CompassMark.Kind.LOCATION;
                    // 未来战术标点新增类型时的兜底，避免穷举 switch 直接抛异常。
                    default -> CompassMark.Kind.LOCATION;
                };
                // 颜色与 3D 战术标点一字不差：敌人红、其余白。
                int color = kind == CompassMark.Kind.ENEMY ? COLOR_ENEMY : COLOR_NORMAL;
                // 创建时间用战术标点自身的创建时刻：CompassMark 是逐帧重建的临时视图，
                // 若用“现在”会导致脉冲动画相位每帧重置、随帧时长随机抖动。
                // ITEM 标点携带物品本体贴图（方位条下渲染与 3D 标点一致的物品图标）。
                result.add(CompassMark.of("tactical:" + System.identityHashCode(marker), kind,
                        position, color, null, true, marker.createdAt(),
                        kind == CompassMark.Kind.ITEM ? marker.item().copy() : null));
            } catch (RuntimeException ignored) {
                // 单个标点状态异常（目标正在失效等）不影响其余标点与整个 HUD。
            }
        }
        return result;
    }
}
