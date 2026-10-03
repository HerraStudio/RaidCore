package dev.draginventory.client.wheel;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Hold to keep open; releasing equips the highlighted slot. Right-click/Escape cancel. */
public class HotbarWheelScreen extends Screen {
    private boolean initialized;
    private boolean finished;
    private double scrollRemainder;

    public HotbarWheelScreen() {
        super(Component.translatable("draginventory.wheel.title"));
    }

    @Override
    protected void init() {
        if (!initialized) {
            initialized = true;
            WheelClient.begin(this);
        }
    }

    protected boolean isTriggerHeld() { return WheelKeyBindings.isHeld(); }

    @Override
    public void tick() {
        if (finished) return;
        if (!WheelClient.valid(this)) finish(false);
        else if (!isTriggerHeld()) confirmOnRelease();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!finished) WheelClient.render(this, graphics, mouseX, mouseY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (!finished) WheelClient.hover(this, mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        mouseMoved(mouseX, mouseY);
        return true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) finish(false);
        else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && WheelClient.clickSector(this, x, y)) finish(true);
        return true;
    }
    @Override public boolean mouseReleased(double x, double y, int button) { return true; }
    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!Double.isFinite(vertical)) return true;
        WheelClient.hover(this, x, y);
        scrollRemainder += vertical;
        int steps = (int) scrollRemainder;
        if (steps != 0) {
            scrollRemainder -= steps;
            // Vanilla hotbar scrolling moves to the previous slot for positive (upward) input.
            WheelClient.scroll(-steps);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) onClose();
        return true;
    }

    @Override
    public boolean keyReleased(int key, int scanCode, int modifiers) {
        if (WheelKeyBindings.matches(key, scanCode) && !isTriggerHeld()) confirmOnRelease();
        return true;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { finish(false); }

    @Override
    public void removed() {
        if (!finished) {
            finished = true;
            WheelClient.end(this, false);
        }
    }

    private void confirmOnRelease() {
        if (finished) return;
        if (minecraft != null && WheelClient.valid(this)) {
            // Sample before mouse capture recenters the cursor; stationary scroll choices remain selected.
            double x = minecraft.mouseHandler.xpos() * width / minecraft.getWindow().getScreenWidth();
            double y = minecraft.mouseHandler.ypos() * height / minecraft.getWindow().getScreenHeight();
            WheelClient.hover(this, x, y);
        }
        finish(true);
    }

    private void finish(boolean commit) {
        if (finished) return;
        finished = true;
        WheelClient.end(this, commit);
        if (minecraft != null && minecraft.screen == this) {
            minecraft.setScreen(null);
            // grabMouse samples held buttons; the dismissing click must remain a UI action.
            minecraft.options.keyAttack.setDown(false);
            minecraft.options.keyUse.setDown(false);
            while (minecraft.options.keyAttack.consumeClick()) {}
            while (minecraft.options.keyUse.consumeClick()) {}
        }
    }
}
