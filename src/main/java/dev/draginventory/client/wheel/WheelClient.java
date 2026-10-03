package dev.draginventory.client.wheel;

import dev.draginventory.client.WeaponScroll;
import dev.draginventory.mixin.CarriedItemSync;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/** Screen owns active input; HUD owns the closing visual after mouse control has already returned. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class WheelClient {
    private static final WheelHoldGesture HOLD = new WheelHoldGesture();
    private static final WheelAnimation ANIMATION = new WheelAnimation();
    private static HotbarWheelScreen owner;
    private static ClientLevel world;
    private static UUID playerId;
    private static ItemStack[] stacks = emptyStacks();
    private static int currentSlot = -1;
    private static int hover = -1;
    private static double pointerX;
    private static double pointerY;
    private static boolean scrollSelection;
    private static WheelAnimation.Frame displayedFrame;

    private WheelClient() {}

    public static WheelAnimation animation() { return ANIMATION; }
    public static boolean isWheelOpen() { return owner != null && Minecraft.getInstance().screen == owner; }
    public static int selectedSector() { return hover; }

    @SubscribeEvent
    public static void key(InputEvent.Key event) {
        if (!WheelKeyBindings.matches(event.getKey(), event.getScanCode())) return;
        if (event.getAction() != GLFW.GLFW_PRESS && event.getAction() != GLFW.GLFW_RELEASE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof HotbarWheelScreen) return;
        boolean down = event.getAction() == GLFW.GLFW_PRESS || WheelKeyBindings.isHeld();
        boolean allowed = eligible(mc) && (event.getAction() != GLFW.GLFW_PRESS
                || WheelKeyBindings.OPEN_WHEEL.getKeyModifier().isActive(null));
        if (HOLD.update(down, allowed, Util.getMillis())) mc.setScreen(new HotbarWheelScreen());
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean held = WheelKeyBindings.isHeld();
        while (WheelKeyBindings.OPEN_WHEEL.consumeClick()) {}
        if (mc.screen instanceof HotbarWheelScreen) {
            HOLD.suspend(held);
            return;
        }
        if (!samePlayer(mc)) {
            if (owner != null) cancel();
            ANIMATION.reset();
            stacks = emptyStacks();
        }
        boolean eligible = eligible(mc);
        long now = Util.getMillis();
        if (HOLD.update(held, eligible, now)) mc.setScreen(new HotbarWheelScreen());
    }

    static void begin(HotbarWheelScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        owner = screen;
        world = mc.level;
        playerId = mc.player == null ? null : mc.player.getUUID();
        hover = -1;
        scrollSelection = false;
        pointerX = HotbarWheelRenderer.centerX(screen.width);
        pointerY = HotbarWheelRenderer.centerY(screen.height);
        refreshItems();
        long now = Util.getMillis();
        ANIMATION.open(now);
        ANIMATION.hover(-1, now);
        displayedFrame = ANIMATION.frame(now);
        while (WheelKeyBindings.OPEN_WHEEL.consumeClick()) {}
        HOLD.suspend(true);
    }

    static boolean valid(HotbarWheelScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        return owner == screen && mc.screen == screen && samePlayer(mc) && mc.gameMode != null
                && mc.player.isAlive() && !mc.player.isSpectator() && mc.isWindowActive()
                && mc.getOverlay() == null && !mc.options.hideGui;
    }

    static void hover(HotbarWheelScreen screen, double mouseX, double mouseY) {
        if (owner != screen) return;
        boolean moved = Math.abs(mouseX - pointerX) > 0.01 || Math.abs(mouseY - pointerY) > 0.01;
        pointerX = mouseX;
        pointerY = mouseY;
        if (moved) scrollSelection = false;
        if (scrollSelection) return;
        hover = sectorAt(screen, mouseX, mouseY);
        ANIMATION.hover(hover, Util.getMillis());
    }

    private static int sectorAt(HotbarWheelScreen screen, double mouseX, double mouseY) {
        // Match what is actually on screen, rather than advancing a fast spin between frames.
        WheelAnimation.Frame frame = displayedFrame != null ? displayedFrame : ANIMATION.frame(Util.getMillis());
        Minecraft mc = Minecraft.getInstance();
        // Selection spans the screen; only the fixed physical-pixel center zone retains the candidate.
        double dx = (mouseX - HotbarWheelRenderer.centerX(screen.width))
                * mc.getWindow().getWidth() / screen.width;
        double dy = (mouseY - HotbarWheelRenderer.centerY(screen.height))
                * mc.getWindow().getHeight() / screen.height;
        return WheelDirection.select(hover, dx, dy, frame.rotation());
    }

    static boolean clickSector(HotbarWheelScreen screen, double mouseX, double mouseY) {
        if (owner != screen) return false;
        int clicked = sectorAt(screen, mouseX, mouseY);
        if (clicked < 0) return false;
        pointerX = mouseX;
        pointerY = mouseY;
        hover = clicked;
        scrollSelection = false;
        ANIMATION.hover(hover, Util.getMillis());
        return true;
    }

    public static void scroll(int steps) {
        if (steps == 0 || owner == null || !valid(owner)) return;
        int start = hover;
        if (start < 0) start = currentSlot >= WheelGeometry.FIRST_SLOT
                && currentSlot < WheelGeometry.FIRST_SLOT + WheelGeometry.SECTORS
                ? currentSlot - WheelGeometry.FIRST_SLOT : (steps > 0 ? WheelGeometry.SECTORS - 1 : 0);
        hover = WheelGeometry.nextSector(start, steps);
        scrollSelection = true;
        long now = Util.getMillis();
        ANIMATION.hover(hover, now);
        // Bring the next item toward the same visual anchor while the whole wheel turns.
        ANIMATION.spin(-steps, now);
    }

    static void render(HotbarWheelScreen screen, net.minecraft.client.gui.GuiGraphics graphics, double mouseX, double mouseY) {
        if (owner != screen) return;
        Minecraft mc = Minecraft.getInstance();
        double rawX = mc.mouseHandler.xpos() * screen.width / mc.getWindow().getScreenWidth();
        double rawY = mc.mouseHandler.ypos() * screen.height / mc.getWindow().getScreenHeight();
        long now = Util.getMillis();
        displayedFrame = ANIMATION.frame(now);
        hover(screen, rawX, rawY);
        refreshItems();
        displayedFrame = ANIMATION.frame(now);
        HotbarWheelRenderer.render(graphics, stacks, currentSlot, hover, displayedFrame, rawX, rawY);
    }

    static void end(HotbarWheelScreen screen, boolean commit) {
        if (owner != screen) return;
        Minecraft mc = Minecraft.getInstance();
        if (commit && valid(screen) && hover >= 0) {
            int target = WheelGeometry.slot(hover);
            WeaponScroll.select(mc.player.getInventory(), target);
            ((CarriedItemSync) mc.gameMode).draginventory$syncCarriedItem();
        }
        refreshItems();
        owner = null;
        ANIMATION.close(Util.getMillis());
        HOLD.suspend(WheelKeyBindings.isHeld());
    }

    public static void cancel() {
        HotbarWheelScreen screen = owner;
        if (screen != null) screen.onClose();
    }

    @SubscribeEvent
    public static void renderClosing(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        long now = Util.getMillis();
        if (owner != null || !ANIMATION.isVisible(now) || mc.screen != null
                || !samePlayer(mc) || mc.options.hideGui || mc.player == null || !mc.player.isAlive()) return;
        HotbarWheelRenderer.render(event.getGuiGraphics(), stacks, currentSlot, hover,
                ANIMATION.frame(now), pointerX, pointerY);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void quietBackdrop(RenderGuiLayerEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        // Keep the wheel's title and instructions readable at large GUI scales.
        // Normal HUD layers return as soon as the short closing transition completes.
        if (samePlayer(mc) && ANIMATION.isVisible(Util.getMillis())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        owner = null;
        world = null;
        playerId = null;
        stacks = emptyStacks();
        HOLD.reset();
        ANIMATION.reset();
        displayedFrame = null;
        HotbarWheelRenderer.close();
    }

    private static boolean samePlayer(Minecraft mc) {
        return mc.player != null && mc.level == world && mc.player.getUUID().equals(playerId);
    }

    private static boolean eligible(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.player.isAlive()
                && !mc.player.isSpectator() && mc.gameMode != null && mc.screen == null
                && mc.getOverlay() == null && mc.isWindowActive() && mc.mouseHandler.isMouseGrabbed()
                && !mc.options.hideGui;
    }

    private static void refreshItems() {
        Minecraft mc = Minecraft.getInstance();
        if (!samePlayer(mc)) return;
        for (int i = 0; i < WheelGeometry.SECTORS; i++) stacks[i] = mc.player.getInventory().getItem(WheelGeometry.slot(i)).copy();
        currentSlot = mc.player.getInventory().selected;
    }

    private static ItemStack[] emptyStacks() {
        return new ItemStack[]{ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
    }
}
