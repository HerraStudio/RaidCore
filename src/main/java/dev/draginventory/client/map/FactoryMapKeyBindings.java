package dev.draginventory.client.map;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public final class FactoryMapKeyBindings {
    /**
     * 开/关大地图（默认 M）。v2.5.3：显式声明 IN_GAME 冲突上下文（此前走原版三参构造，
     * NeoForge 默认 UNIVERSAL——与原版游戏内按键的冲突检测口径不一致）；标签改名
     * “开/关大地图”（反馈：玩家在按键绑定里按“大地图”检索不到该条目）。
     * 屏幕内 M 关图走 {@code matches()}（该实现不检查冲突上下文，IN_GAME 下不受影响）。
     */
    public static final KeyMapping OPEN_MAP = new KeyMapping(
            "key.draginventory.map",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M,
            "key.categories.draginventory");

    /** 清除全部地图标点（反馈⑧：清屏外也可一键清除）。 */
    public static final KeyMapping CLEAR_MARKERS = new KeyMapping(
            "key.draginventory.map_clear_markers",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C,
            "key.categories.draginventory");

    /** 大地图 HUD（清屏模式）开关——地图内 H 键与右下角提示共用同一绑定。 */
    public static final KeyMapping TOGGLE_MAP_HUD = new KeyMapping(
            "key.draginventory.map_hud",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H,
            "key.categories.draginventory");

    private FactoryMapKeyBindings() {}

    @EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registrar {
        private Registrar() {}

        @SubscribeEvent
        public static void register(RegisterKeyMappingsEvent event) {
            event.register(OPEN_MAP);
            event.register(CLEAR_MARKERS);
            event.register(TOGGLE_MAP_HUD);
        }
    }
}
