package dev.draginventory.client.wheel;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public final class WheelKeyBindings {
    public static final KeyMapping OPEN_WHEEL = new KeyMapping("key.draginventory.wheel",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
            "key.categories.draginventory");

    private WheelKeyBindings() {}

    /** Screen opening releases KeyMapping states, so held input is read from the physical device. */
    public static boolean isHeld() {
        if (!OPEN_WHEEL.getKeyModifier().isActive(null)) return false;
        long window = Minecraft.getInstance().getWindow().getWindow();
        InputConstants.Key key = OPEN_WHEEL.getKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        if (key.getType() == InputConstants.Type.SCANCODE) {
            for (int code = GLFW.GLFW_KEY_SPACE; code <= GLFW.GLFW_KEY_LAST; code++) {
                if (GLFW.glfwGetKeyScancode(code) == key.getValue() && InputConstants.isKeyDown(window, code)) return true;
            }
            return false;
        }
        int code = key.getValue();
        if (code == GLFW.GLFW_KEY_UNKNOWN) return false;
        if (code == GLFW.GLFW_KEY_LEFT_ALT) {
            return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                    || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
        }
        return InputConstants.isKeyDown(window, code);
    }

    public static boolean matches(int keyCode, int scanCode) {
        InputConstants.Key key = OPEN_WHEEL.getKey();
        if (key.getType() == InputConstants.Type.SCANCODE) return key.getValue() == scanCode;
        return key.equals(InputConstants.getKey(keyCode, scanCode))
                || (key.getType() == InputConstants.Type.KEYSYM && key.getValue() == GLFW.GLFW_KEY_LEFT_ALT
                && keyCode == GLFW.GLFW_KEY_RIGHT_ALT);
    }

    @EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registrar {
        @SubscribeEvent
        public static void register(RegisterKeyMappingsEvent event) {
            event.register(OPEN_WHEEL);
        }
    }
}
