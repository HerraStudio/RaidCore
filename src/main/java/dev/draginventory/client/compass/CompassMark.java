package dev.draginventory.client.compass;

import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 方位条上的统一标点视图。来源有四类：
 * <ul>
 *   <li>本模组战术标点（中键标记，只读桥接）</li>
 *   <li>指令创建的测试标点（验证联动效果，60 秒后自动过期）</li>
 *   <li>外部 HERRA 模组通过 {@link CompassMarkerProvider} 注入</li>
 *   <li>玩家死亡时自动记录的死亡标点（靠近后自动消失）</li>
 * </ul>
 *
 * <p>图标语义（与 3D 战术标点保持一致，让方位条下的标记一眼对应世界里的标点）：
 * ENEMY = 红色感叹号、LOCATION = 白色菱形、ITEM = 物品本体图标
 * （{@code icon} 非空时渲染物品贴图）、DEATH = X 十字、EXTERNAL = 菱形。</p>
 */
public record CompassMark(Object key, Kind kind, Vec3 position, int color,
                          @Nullable Component label, boolean showDistance, long createdAtMillis,
                          @Nullable ItemStack icon) {

    public enum Kind { ENEMY, LOCATION, ITEM, EXTERNAL, DEATH }

    public static CompassMark of(Object key, Kind kind, Vec3 position, int color,
                                 @Nullable Component label, boolean showDistance) {
        return new CompassMark(key, kind, position, color, label, showDistance,
                System.currentTimeMillis(), null);
    }

    /** 带物品图标的工厂（ITEM 标点在方位条上直接渲染掉落物贴图，与 3D 标点一致）。 */
    public static CompassMark of(Object key, Kind kind, Vec3 position, int color,
                                 @Nullable Component label, boolean showDistance,
                                 long createdAtMillis, @Nullable ItemStack icon) {
        return new CompassMark(key, kind, position, color, label, showDistance, createdAtMillis, icon);
    }
}
