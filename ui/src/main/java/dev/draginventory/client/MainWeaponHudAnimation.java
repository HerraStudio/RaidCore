package dev.draginventory.client;

import dev.draginventory.WeaponSlots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Applies inside GWO's own saved, scaled panel pose; GWO's finally block restores it. */
public final class MainWeaponHudAnimation {
    private static final float PIVOT_X = 86f;
    private static final float PIVOT_Y = 40f;

    private MainWeaponHudAnimation() {}

    public static void apply(GuiGraphics graphics, Minecraft minecraft) {
        if (minecraft.player == null || minecraft.player.isSpectator()) return;
        int selected = minecraft.player.getInventory().selected;
        if (!WeaponSlots.isWeaponSlot(selected)) return;
        WeaponHudDynamics.apply(graphics);
        if (selected != WeaponSwitchAnimation.to()) return;
        var motion = WeaponSwitchAnimation.mainPose(System.currentTimeMillis());
        if (motion.scale() == 1f && motion.x() == 0f && motion.y() == 0f) return;
        graphics.pose().translate(PIVOT_X + motion.x(), PIVOT_Y + motion.y(), 0);
        graphics.pose().scale(motion.scale(), motion.scale(), 1);
        graphics.pose().translate(-PIVOT_X, -PIVOT_Y, 0);
    }
}
