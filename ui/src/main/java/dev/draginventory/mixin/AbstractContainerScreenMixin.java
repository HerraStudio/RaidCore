package dev.draginventory.mixin;

import dev.draginventory.Gesture;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin extends Screen {
    @Shadow @Final protected AbstractContainerMenu menu;
    @Shadow protected boolean isQuickCrafting;
    @Shadow private boolean doubleclick;
    @Shadow private boolean skipNextRelease;
    @Shadow private long lastClickTime;
    @Shadow protected abstract void slotClicked(Slot slot, int id, int button, ClickType type);
    @Shadow protected abstract boolean isHovering(int x, int y, int width, int height, double mx, double my);

    @Unique private final Gesture draginventory$gesture = new Gesture();
    @Unique private Slot draginventory$source;
    @Unique private ItemStack draginventory$pressedStack = ItemStack.EMPTY;
    @Unique private boolean draginventory$alreadyCarrying;

    protected AbstractContainerScreenMixin(Component title) { super(title); }

    // After Screen.mouseClicked: recipe/search/buttons keep their own input handling.
    @Inject(method = "mouseClicked", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;findSlot(DD)Lnet/minecraft/world/inventory/Slot;"), cancellable = true)
    private void draginventory$press(double x, double y, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button != 0) {
            if (draginventory$gesture.active()) cir.setReturnValue(true);
            else draginventory$gesture.reset();
            return;
        }
        if (minecraft == null || minecraft.player == null) return;
        Slot slot = draginventory$slotAt(x, y);
        draginventory$source = slot;
        draginventory$pressedStack = slot == null ? ItemStack.EMPTY : slot.getItem().copy();
        draginventory$alreadyCarrying = !menu.getCarried().isEmpty();
        isQuickCrafting = false;
        doubleclick = false;
        skipNextRelease = false;
        lastClickTime = 0;
        if (draginventory$gesture.press(slot, x, y, Util.getMillis(),
                !draginventory$alreadyCarrying && slot != null && slot.hasItem() && slot.mayPickup(minecraft.player))) {
            // Virtual dispatch preserves crafting, trading and creative slot behavior.
            slotClicked(slot, slot.index, 0, ClickType.QUICK_MOVE);
        }
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void draginventory$drag(double x, double y, int button, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (draginventory$gesture.active()) {
            if (button == 0) draginventory$update(x, y);
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void draginventory$hold(GuiGraphics graphics, int x, int y, float partialTick, CallbackInfo ci) {
        if (!draginventory$gesture.active()) return;
        // Recover if a subclass consumed release (for example, a creative tab).
        if (GLFW.glfwGetMouseButton(minecraft.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_RELEASE) {
            draginventory$finish(x, y);
        } else draginventory$update(x, y);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void draginventory$release(double x, double y, int button, CallbackInfoReturnable<Boolean> cir) {
        if (draginventory$gesture.active()) {
            if (button == 0) {
                super.mouseReleased(x, y, button);
                draginventory$finish(x, y);
            }
            cir.setReturnValue(true);
        }
    }

    @Unique
    private Slot draginventory$slotAt(double x, double y) {
        for (Slot slot : menu.slots) {
            if (slot.isActive() && isHovering(slot.x, slot.y, 16, 16, x, y)) return slot;
        }
        return null;
    }

    @Unique
    private void draginventory$update(double x, double y) {
        if (!draginventory$gesture.update(x, y, Util.getMillis()) || draginventory$alreadyCarrying) return;
        Slot source = draginventory$source;
        // Server updates and tab switches must not pick up an unrelated new stack.
        if (source != null && menu.slots.contains(source) && source.isActive()
                && source.mayPickup(minecraft.player) && menu.getCarried().isEmpty()
                && !draginventory$pressedStack.isEmpty()
                && ItemStack.isSameItemSameComponents(source.getItem(), draginventory$pressedStack)) {
            slotClicked(source, source.index, 0, ClickType.PICKUP);
        }
    }

    @Unique
    private void draginventory$finish(double x, double y) {
        draginventory$update(x, y);
        Slot target = draginventory$slotAt(x, y);
        boolean quickMove = draginventory$gesture.quickMove();
        boolean placing = draginventory$gesture.dragging() || draginventory$alreadyCarrying;
        if (!quickMove && placing && !menu.getCarried().isEmpty()) {
            // Outside release cancels; result slots must never be harvested a second time.
            if (target != null && target.mayPlace(menu.getCarried())) {
                slotClicked(target, target.index, 0, ClickType.PICKUP);
            }
            draginventory$returnRemainder();
        }
        draginventory$gesture.release(target, Util.getMillis());
        if (draginventory$alreadyCarrying || draginventory$pressedStack.isEmpty()) draginventory$gesture.reset();
        draginventory$source = null;
        draginventory$pressedStack = ItemStack.EMPTY;
        draginventory$alreadyCarrying = false;
    }

    @Unique
    private void draginventory$returnRemainder() {
        if (menu.getCarried().isEmpty() || draginventory$alreadyCarrying) return;
        draginventory$putBack(draginventory$source);
        // Crafting/result slots cannot accept returns: try the player's inventory.
        for (int pass = 0; pass < 2 && !menu.getCarried().isEmpty(); pass++) {
            for (Slot slot : menu.slots) {
                if (slot.container == minecraft.player.getInventory() && (pass == 1 || slot.hasItem())) {
                    draginventory$putBack(slot);
                    if (menu.getCarried().isEmpty()) break;
                }
            }
        }
        // If no legal slot has space, retain the cursor stack; never delete it.
    }

    @Unique
    private void draginventory$putBack(Slot slot) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty() || slot == null || !menu.slots.contains(slot) || !slot.isActive() || !slot.mayPlace(carried)) return;
        ItemStack existing = slot.getItem();
        if (existing.isEmpty() || (ItemStack.isSameItemSameComponents(existing, carried)
                && existing.getCount() < slot.getMaxStackSize(carried))) {
            slotClicked(slot, slot.index, 0, ClickType.PICKUP);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void draginventory$key(int key, int scan, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (draginventory$gesture.active() && key != GLFW.GLFW_KEY_ESCAPE
                && !minecraft.options.keyInventory.matches(key, scan)) cir.setReturnValue(true);
        else draginventory$gesture.reset();
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void draginventory$removed(CallbackInfo ci) {
        draginventory$gesture.reset();
        draginventory$source = null;
        draginventory$pressedStack = ItemStack.EMPTY;
    }
}
