package dev.draginventory.client;

import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.slf4j.LoggerFactory;
import org.lwjgl.opengl.GL11;

/** Replaces GWO's tiny key badges and adds the laser toggle affordance. */
public final class GwoControlHud {
    private static final ResourceLocation SEMI = id("textures/gui/hud/ui_switch_semi.png");
    private static final ResourceLocation BURST = id("textures/gui/hud/ui_switch_burst.png");
    private static final ResourceLocation AUTO = id("textures/gui/hud/ui_switch_auto.png");
    private static final ResourceLocation TACTICAL_OFF = id("textures/gui/hud/jup_ui_hud_icon_tactical_stance_off.png");
    private static final ResourceLocation TACTICAL_ON = id("textures/gui/hud/jup_ui_hud_icon_tactical_stance_on.png");
    private static final int ICON_SIZE = 12;
    private static final int SPACING = 14;
    private static Access access;
    private static boolean attempted;

    private GwoControlHud() {}

    public static boolean render(GuiGraphics graphics, Minecraft minecraft, Object view, ItemStack gun,
                                 int centerX, int y, KeyMapping cycleMode, KeyMapping tacticalStance) {
        State state = state(view, gun);
        if (state == null || state.modes().isEmpty()) return false;
        String fireKey = keyText(cycleMode), tacticalKey = keyText(tacticalStance);
        int fireWidth = HudKeyIcons.width(fireKey) + 6 + ICON_SIZE + (state.modes().size() - 1) * SPACING;
        int tacticalWidth = state.tacticalSupported() ? HudKeyIcons.width(tacticalKey) + 6 + SPACING + ICON_SIZE : 0;
        int groupGap = tacticalWidth > 0 ? 8 : 0;
        int baseWidth = fireWidth + groupGap + tacticalWidth;
        int totalWidth = baseWidth + (state.laserInstalled() ? 10 + HudKeyIcons.width(state.laserKey()) + 6 + minecraft.font.width("激光") : 0);
        int left = Math.max(centerX - 48, centerX - baseWidth / 2);
        // Keep the full row inside the native panel, including unusually long rebound key names.
        float scale = Math.min(1f, (centerX + 114 - left) / (float) totalWidth);
        graphics.flush();
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        float[] old = RenderSystem.getShaderColor().clone();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(left, y, 0);
            graphics.pose().scale(scale, scale, 1);
            int x = 0;
            if (tacticalWidth > 0) {
                drawTactical(graphics, x, 0, state, tacticalKey);
                x += tacticalWidth + groupGap;
            }
            drawFireMode(graphics, x, 0, state, fireKey);
            if (state.laserInstalled()) drawLaser(graphics, minecraft, x + fireWidth + 10, 0, state);
        } finally {
            graphics.flush();
            graphics.pose().popPose();
            RenderSystem.setShaderColor(old[0], old[1], old[2], old[3]);
            if (!blend) RenderSystem.disableBlend();
        }
        return true;
    }

    private static void drawTactical(GuiGraphics graphics, int x, int y, State state, String key) {
        HudKeyIcons.draw(graphics, key, x, y, state.tacticalAllowed());
        int iconX = x + HudKeyIcons.width(key) + 6;
        blit(graphics, TACTICAL_OFF, iconX, y, ICON_SIZE, 96, state.tacticalEnabled() ? 0.55f : 1);
        blit(graphics, TACTICAL_ON, iconX + SPACING, y, ICON_SIZE, 96, state.tacticalEnabled() ? 1 : 0.55f);
        int selectedX = iconX + (state.tacticalEnabled() ? SPACING : 0);
        graphics.fill(selectedX + 1, y + ICON_SIZE + 1,
                selectedX + ICON_SIZE - 1, y + ICON_SIZE + 2,
                0xFFFFFFFF);
    }

    private static void drawFireMode(GuiGraphics graphics, int x, int y, State state, String key) {
        HudKeyIcons.draw(graphics, key, x, y, true);
        int iconX = x + HudKeyIcons.width(key) + 6;
        for (int i = 0; i < state.modes().size(); i++) {
            blit(graphics, modeIcon(state.modes().get(i)), iconX + i * SPACING, y, ICON_SIZE, 100,
                    i == state.selectedMode() ? 1 : 0.55f);
        }
        int selected = Math.max(0, state.selectedMode());
        graphics.fill(iconX + selected * SPACING + 1, y + ICON_SIZE + 1,
                iconX + selected * SPACING + ICON_SIZE - 1, y + ICON_SIZE + 2, 0xFFFFFFFF);
    }

    private static void drawLaser(GuiGraphics graphics, Minecraft minecraft, int x, int y, State state) {
        HudKeyIcons.draw(graphics, state.laserKey(), x, y, state.laserEnabled());
        int textX = x + HudKeyIcons.width(state.laserKey()) + 6;
        graphics.drawString(minecraft.font, "激光", textX, y + 2,
                state.laserEnabled() ? 0xFFFFFFFF : 0xFF8C8C8C, false);
        if (state.laserEnabled()) graphics.fill(textX, y + 13, textX + minecraft.font.width("激光"), y + 14, 0xFFFFFFFF);
    }

    private static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int size, int sourceSize, float shade) {
        graphics.flush();
        float[] old = RenderSystem.getShaderColor().clone();
        RenderSystem.setShaderColor(shade, shade, shade, 1);
        graphics.blit(texture, x, y, size, size, 0, 0, sourceSize, sourceSize, sourceSize, sourceSize);
        RenderSystem.setShaderColor(old[0], old[1], old[2], old[3]);
    }

    private static String keyText(KeyMapping key) {
        return key == null ? "?" : key.getTranslatedKeyMessage().getString().toUpperCase(Locale.ROOT);
    }

    private static ResourceLocation modeIcon(Object mode) {
        return switch (mode.toString().toUpperCase(Locale.ROOT)) {
            case "BURST" -> BURST;
            case "AUTO" -> AUTO;
            default -> SEMI;
        };
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("gwo", path);
    }

    private static State state(Object view, ItemStack gun) {
        if (gun == null || gun.isEmpty()) return null;
        if (!attempted) initialize();
        if (access == null) return null;
        try {
            Object definition = access.definition().invoke(view, gun);
            if (definition == null) return null;
            List<?> modes = (List<?>) access.modes().invoke(definition);
            Object selected = access.fireMode().invoke(view, gun);
            int selectedMode = Math.max(0, modes.indexOf(selected));
            Object tactical = access.tacticalEvaluate().invoke(null, gun, definition);
            boolean tacticalSupported = (boolean) access.supported().invoke(tactical);
            boolean tacticalAllowed = (boolean) access.allowed().invoke(tactical)
                    && (boolean) access.aiming().invoke(access.weaponStatus());
            boolean tacticalEnabled = (boolean) access.tacticalEnabled().invoke(null, gun);
            boolean laserInstalled = (boolean) access.hasLaser().invoke(null, gun, definition);
            boolean laserEnabled = laserInstalled && (boolean) access.laserEnabled().invoke(null, gun);
            Object laserKey = access.laserKey().get(null);
            String laserLabel = laserKey instanceof KeyMapping mapping ? keyText(mapping) : "K";
            return new State(modes, selectedMode, tacticalSupported, tacticalAllowed, tacticalEnabled,
                    laserInstalled, laserEnabled, laserLabel);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LoggerFactory.getLogger(GwoControlHud.class).debug("GWO control HUD unavailable", e);
            return null;
        }
    }

    private static void initialize() {
        attempted = true;
        try {
            Class<?> definition = Class.forName("com.sgr792.gwo.content.GunDefinition");
            Class<?> view = Class.forName("com.sgr792.gwo.modular.IGunView");
            Class<?> tactical = Class.forName("com.sgr792.gwo.modular.TacticalStanceEligibility");
            Class<?> result = Class.forName("com.sgr792.gwo.modular.TacticalStanceEligibility$Result");
            Class<?> gunData = Class.forName("com.sgr792.gwo.item.GunData");
            Class<?> laserRenderer = Class.forName("com.sgr792.gwo.client.render.fx.LaserModuleRenderer");
            Class<?> input = Class.forName("com.sgr792.gwo.client.input.ClientInputEventHandler");
            Class<?> weaponStatus = Class.forName("com.sgr792.gwo.client.camera.GunWeaponStatus");
            Field laserKey = input.getDeclaredField("TOGGLE_LASER");
            laserKey.setAccessible(true);
            access = new Access(view.getMethod("definition", ItemStack.class), definition.getMethod("fireModes"),
                    view.getMethod("getFireMode", ItemStack.class), tactical.getMethod("evaluate", ItemStack.class, definition),
                    result.getMethod("supported"), result.getMethod("allowed"),
                    Class.forName("com.sgr792.gwo.client.runtime.ClientTacticalStancePrediction")
                            .getMethod("enabled", ItemStack.class),
                    laserRenderer.getMethod("hasInstalledLaser", ItemStack.class, definition),
                    gunData.getMethod("laserEnabled", ItemStack.class), laserKey,
                    weaponStatus.getField("INSTANCE").get(null), weaponStatus.getMethod("isAiming"));
        } catch (ReflectiveOperationException | LinkageError e) {
            LoggerFactory.getLogger(GwoControlHud.class).debug("GWO control HUD API unavailable", e);
        }
    }

    private record Access(Method definition, Method modes, Method fireMode, Method tacticalEvaluate,
                          Method supported, Method allowed, Method tacticalEnabled, Method hasLaser,
                          Method laserEnabled, Field laserKey, Object weaponStatus, Method aiming) {}

    private record State(List<?> modes, int selectedMode, boolean tacticalSupported, boolean tacticalAllowed,
                         boolean tacticalEnabled, boolean laserInstalled, boolean laserEnabled, String laserKey) {}
}
