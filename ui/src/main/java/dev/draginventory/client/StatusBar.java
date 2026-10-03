package dev.draginventory.client;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import dev.draginventory.PlayerStamina;
import dev.draginventory.StaminaState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

final class StatusBar extends UIElement {
    private static final IGuiTexture FRAME = new ColorRectTexture(0xB0181818);
    private static final IGuiTexture TRACK = new ColorRectTexture(0x903A3A3A);
    private static final IGuiTexture WHITE = new ColorRectTexture(0xFFF5F5F5);
    private static final IGuiTexture RED = new ColorRectTexture(0xFFE34A43);
    private static final IGuiTexture TRAIL = new ColorRectTexture(0xA09B9B9B);
    private final boolean health;
    private Player displayedPlayer;
    private float displayedFill;
    private float trailingFill;

    StatusBar(boolean health) {
        this.health = health;
        layout(layout -> layout.width(health ? ReferenceHotbar.HEALTH_WIDTH : ReferenceHotbar.BAR_WIDTH)
                .height(health ? 22 : 7));
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        var minecraft = Minecraft.getInstance();
        if (!(minecraft.getCameraEntity() instanceof Player player)) return;
        float target;
        if (health) {
            float absorption = player.getAbsorptionAmount();
            target = (player.getHealth() + absorption) / Math.max(1, player.getMaxHealth() + absorption);
        } else {
            target = player.getData(PlayerStamina.STATE).value() / StaminaState.MAXIMUM;
        }
        target = Mth.clamp(target, 0, 1);
        if (displayedPlayer != player) {
            displayedPlayer = player;
            displayedFill = target;
            trailingFill = target;
        }
        float seconds = minecraft.isPaused() ? 0 : Math.min(0.1f, minecraft.getTimer().getRealtimeDeltaTicks() / 20);
        displayedFill = Mth.lerp(1 - (float) Math.exp(-14 * seconds), displayedFill, target);
        trailingFill = Math.max(displayedFill,
                Mth.lerp(1 - (float) Math.exp(-4 * seconds), trailingFill, target));
        if (Math.abs(displayedFill - target) < 0.001f) displayedFill = target;
        if (Math.abs(trailingFill - target) < 0.001f) trailingFill = target;

        float barX = getPositionX();
        float barY = getPositionY();
        float barWidth = getSizeWidth();
        if (health) {
            if (player instanceof AbstractClientPlayer clientPlayer) {
                PlayerFaceRenderer.draw(context.graphics, clientPlayer.getSkin(),
                        Math.round(barX), Math.round(barY), 20);
            }
            barX += 26;
            barWidth -= 26;
            String name = player.getName().getString();
            float nameScale = Math.min(1, barWidth / Math.max(1, minecraft.font.width(name)));
            context.graphics.pose().pushPose();
            context.graphics.pose().translate(barX, barY + 13, 0);
            context.graphics.pose().scale(nameScale, nameScale, 1);
            context.graphics.drawString(minecraft.font, name, 0, 0, 0xFFE0E0E0, false);
            context.graphics.pose().popPose();
            barY += 3;
        }
        context.drawTexture(FRAME, barX, barY, barWidth, 7);
        context.drawTexture(TRACK, barX + 1, barY + 1, barWidth - 2, 5);
        if (trailingFill > displayedFill) {
            context.drawTexture(TRAIL, barX + 1, barY + 1, (barWidth - 2) * trailingFill, 5);
        }
        if (displayedFill > 0) {
            context.drawTexture(!health && target <= StaminaState.LOW_FRACTION ? RED : WHITE,
                    barX + 1, barY + 1, (barWidth - 2) * displayedFill, 5);
        }
    }
}
