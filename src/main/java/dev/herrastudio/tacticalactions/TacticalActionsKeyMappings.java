package dev.herrastudio.tacticalactions;

import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class TacticalActionsKeyMappings {
    public static final KeyMapping LEAN_LEFT = new KeyMapping("key.tacticalactions.lean_left", GLFW.GLFW_KEY_Q, "key.categories.tacticalactions");
    public static final KeyMapping LEAN_RIGHT = new KeyMapping("key.tacticalactions.lean_right", GLFW.GLFW_KEY_E, "key.categories.tacticalactions");
    public static final KeyMapping PRONE = new KeyMapping("key.tacticalactions.prone", GLFW.GLFW_KEY_Z, "key.categories.tacticalactions");

    private TacticalActionsKeyMappings() {}

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(LEAN_LEFT);
        event.register(LEAN_RIGHT);
        event.register(PRONE);
    }
}
