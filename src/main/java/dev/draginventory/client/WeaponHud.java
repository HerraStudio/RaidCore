package dev.draginventory.client;

import dev.draginventory.WeaponSlots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

/** Draws the three alternate weapon hints while leaving GWO's original HUD intact. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class WeaponHud {
    private static final int HEADER_X = 14;
    private static final int HEADER_Y = -4;
    private static final int ROW_WIDTH = 144;
    private static final int ICON_WIDTH = 34;
    private static final int ICON_HEIGHT = 14;
    private static Player displayedPlayer;
    private static int displayedSlot = -1;

    private WeaponHud() {}

    @SubscribeEvent
    public static void render(RenderGuiLayerEvent.Pre event) {
        if (!GwoHudBridge.isGunHud(event.getName())) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || !(minecraft.getCameraEntity() instanceof Player player) || player.isSpectator()
                || !GwoHudBridge.installed()) return;
        int selected = player.getInventory().selected;
        if (displayedPlayer != player) {
            displayedPlayer = player;
        } else if (displayedSlot != selected && WeaponSwitchAnimation.to() != selected) {
            // Also animate selections from rebound hotbar keys and other vanilla input.
            WeaponSwitchAnimation.trigger(displayedSlot, selected);
        }
        displayedSlot = selected;
        if (!WeaponSlots.isWeaponSlot(selected)) return;

        GuiGraphics graphics = event.getGuiGraphics();
        var layout = GwoHudBridge.layout(minecraft.getWindow());
        int occupied = WeaponScroll.occupied(player.getInventory());
        int[] slots = WeaponSlots.candidates(selected, occupied);
        if (slots.length == 0) return;
        Component[] labels = new Component[slots.length];
        int[] labelWidths = new int[slots.length];
        int rowWidth = 0;
        for (int i = 0; i < slots.length; i++) {
            int binding = WeaponSlots.bindingIndex(slots[i], occupied);
            labels[i] = minecraft.options.keyHotbarSlots[binding].getTranslatedKeyMessage();
            labelWidths[i] = Math.min(20, minecraft.font.width(labels[i]));
            rowWidth += labelWidths[i] + 3 + ICON_WIDTH + 5;
        }
        float rowScale = Math.min(1, ROW_WIDTH / (float) rowWidth);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(layout.x(), layout.y(), 0);
            graphics.pose().scale(layout.scale(), layout.scale(), 1);
            WeaponHudDynamics.apply(graphics);
            graphics.pose().translate(HEADER_X + WeaponSwitchAnimation.offset(System.currentTimeMillis()), HEADER_Y, 0);
            graphics.pose().scale(rowScale, rowScale, 1);
            int x = 0;
            for (int i = 0; i < slots.length; i++) {
                float labelScale = labelWidths[i] / (float) Math.max(1, minecraft.font.width(labels[i]));
                graphics.pose().pushPose();
                graphics.pose().translate(x, (ICON_HEIGHT - minecraft.font.lineHeight * labelScale) / 2f, 0);
                graphics.pose().scale(labelScale, labelScale, 1);
                graphics.drawString(minecraft.font, labels[i], 0, 0, 0xFFFFFFFF, false);
                graphics.pose().popPose();
                int iconX = x + labelWidths[i] + 3;
                if (!WeaponSlotIcons.draw(graphics, player.getInventory().getItem(slots[i]),
                        iconX, 0, ICON_WIDTH, ICON_HEIGHT)) {
                    // A nonempty unsupported item still has a binding; empty slots never reach this row.
                    graphics.drawString(minecraft.font, "-", iconX + 13, 3, 0x99FFFFFF, false);
                }
                x += labelWidths[i] + 3 + ICON_WIDTH + 5;
            }
        } finally {
            graphics.pose().popPose();
        }
    }
}
