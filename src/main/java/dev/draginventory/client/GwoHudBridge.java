package dev.draginventory.client;

import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.platform.Window;
import net.neoforged.fml.ModList;
import java.lang.reflect.Method;
import net.minecraft.world.item.ItemStack;
import org.slf4j.LoggerFactory;

/** Small optional bridge; Drag Inventory never links against GWO classes at compile time. */
public final class GwoHudBridge {
    public static final ResourceLocation GUN_HUD = ResourceLocation.fromNamespaceAndPath("gwo", "gun_hud");
    private static boolean attempted;
    private static IconAccess iconAccess;

    private GwoHudBridge() {}

    public static boolean isGunHud(ResourceLocation layer) {
        return GUN_HUD.equals(layer);
    }

    public static boolean installed() {
        return ModList.get().isLoaded("gwo");
    }

    /** Resolve the actual weapon in this slot, including weapons from external GWO packs. */
    public static IconSources icons(ItemStack stack) {
        if (stack.isEmpty() || !installed()) return null;
        if (!attempted) {
            attempted = true;
            try {
                Class<?> views = Class.forName("com.sgr792.gwo.modular.GunViews");
                Class<?> view = Class.forName("com.sgr792.gwo.modular.IGunView");
                Class<?> definition = Class.forName("com.sgr792.gwo.content.GunDefinition");
                iconAccess = new IconAccess(views.getMethod("view", ItemStack.class),
                        view.getMethod("definition", ItemStack.class),
                        definition.getMethod("hudWeaponIcon"), definition.getMethod("iconTexture"));
            } catch (ReflectiveOperationException | LinkageError e) {
                LoggerFactory.getLogger(GwoHudBridge.class).warn("GWO slot icon API unavailable", e);
            }
        }
        if (iconAccess == null) return null;
        try {
            Object view = iconAccess.view().invoke(null, stack);
            if (view == null) return null;
            Object definition = iconAccess.definition().invoke(view, stack);
            if (definition == null) return null;
            return new IconSources((ResourceLocation) iconAccess.hud().invoke(definition),
                    (ResourceLocation) iconAccess.item().invoke(definition));
        } catch (ReflectiveOperationException | LinkageError e) {
            iconAccess = null;
            LoggerFactory.getLogger(GwoHudBridge.class).warn("GWO slot icons disabled after API failure", e);
            return null;
        }
    }

    public record IconSources(ResourceLocation hud, ResourceLocation item) {}
    private record IconAccess(Method view, Method definition, Method hud, Method item) {}

    /** GWO 2.12.87's GunHudLayout/HudViewport coordinates, in GUI units. */
    public static Layout layout(Window window) {
        float gui = (float) window.getGuiScale();
        float scale = Math.min(window.getWidth() / 1920f, window.getHeight() / 1080f) * 2f / gui;
        return new Layout(window.getWidth() / gui - 206f * scale,
                window.getHeight() / gui - 88f * scale, scale);
    }

    public record Layout(float x, float y, float scale) {}
}
