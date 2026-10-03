package dev.draginventory.client.compass;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * 方位条按键绑定：开关方位条 HUD（默认未绑定，避免与 GWO 枪械模组的 K/L 等键冲突）。
 *
 * <p>绑定入口：原版“选项 -&gt; 控制 -&gt; 按键绑定 -&gt; Drag Inventory”。
 * 屏幕打开时按键事件先交给屏幕处理，不会误触发（原版 KeyboardHandler 行为）。</p>
 */
public final class CompassKeyBindings {

    /** 开关方位条的按键。默认未绑定（UNKNOWN），由玩家在控制设置里自选。 */
    public static final KeyMapping TOGGLE_COMPASS = new KeyMapping(
            "key.draginventory.compass",
            InputConstants.UNKNOWN.getValue(),
            "key.categories.draginventory");

    private CompassKeyBindings() {}

    @EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registrar {
        private Registrar() {}

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE_COMPASS);
        }
    }
}
