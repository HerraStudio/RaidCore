package dev.draginventory.client;

import com.lowdragmc.lowdraglib2.gui.hud.ModularHudLayer;
import com.lowdragmc.lowdraglib2.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import java.util.Set;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.world.level.GameType;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** A display-only LDLib2 HUD: all inventory input remains owned by Minecraft. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class ReferenceHotbar {
    public static final int SLOT_SIZE = 24;
    public static final int FIRST_VISIBLE_SLOT = 4;
    public static final int VISIBLE_SLOT_COUNT = 5;
    public static final int BAR_WIDTH = VISIBLE_SLOT_COUNT * SLOT_SIZE;
    public static final int HEALTH_WIDTH = 160;
    private static final Set<ResourceLocation> RAISED_LAYERS = Set.of(
            VanillaGuiLayers.JUMP_METER,
            VanillaGuiLayers.ARMOR_LEVEL, VanillaGuiLayers.VEHICLE_HEALTH,
            VanillaGuiLayers.AIR_LEVEL, VanillaGuiLayers.SELECTED_ITEM_NAME);
    private static final ResourceLocation ATTACK_BACKGROUND =
            ResourceLocation.withDefaultNamespace("hud/hotbar_attack_indicator_background");
    private static final ResourceLocation ATTACK_PROGRESS =
            ResourceLocation.withDefaultNamespace("hud/hotbar_attack_indicator_progress");
    private static ModularUI ui;
    private static UIElement offhand;
    private static UIElement healthBar;
    private static UIElement staminaBar;
    private static int layoutWidth;
    private static boolean offhandOnLeft;
    private static final ModularHudLayer LAYER = ReferenceHotbar::getOrCreateUI;

    private ReferenceHotbar() {}

    @SubscribeEvent
    public static void renderHotbar(RenderGuiLayerEvent.Pre event) {
        if (event.getName().equals(VanillaGuiLayers.EXPERIENCE_BAR)
                || event.getName().equals(VanillaGuiLayers.EXPERIENCE_LEVEL)) {
            event.setCanceled(true);
            return;
        }
        boolean hotbar = event.getName().equals(VanillaGuiLayers.HOTBAR);
        boolean health = event.getName().equals(VanillaGuiLayers.PLAYER_HEALTH);
        boolean food = event.getName().equals(VanillaGuiLayers.FOOD_LEVEL);
        if (!hotbar && !health && !food && !RAISED_LAYERS.contains(event.getName())) return;
        var mc = Minecraft.getInstance();
        // The vanilla layer's hide-GUI gate still applies; spectator menus stay vanilla.
        if (mc.options.hideGui || mc.gameMode == null || mc.gameMode.getPlayerMode() == GameType.SPECTATOR
                || !(mc.getCameraEntity() instanceof Player player)) return;

        if (health || food) {
            if (mc.gameMode.canHurtPlayer()) {
                if (health) mc.gui.leftHeight = Math.max(mc.gui.leftHeight, 45);
                if (food) mc.gui.rightHeight = Math.max(mc.gui.rightHeight, 45);
            }
            event.setCanceled(true);
            return;
        }

        if (!hotbar) {
            // The 24px cells plus bottom inset need 6px more clearance than vanilla.
            // Render in-place with a balanced pose scope, even if another listener cancels Post.
            var graphics = event.getGuiGraphics();
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(0,
                        event.getName().equals(VanillaGuiLayers.JUMP_METER) ? -16 : -6, 0);
                event.getLayer().render(graphics, event.getPartialTick());
            } finally {
                graphics.pose().popPose();
            }
            event.setCanceled(true);
            return;
        }

        getOrCreateUI();
        healthBar.setVisible(mc.gameMode.canHurtPlayer());
        staminaBar.setVisible(mc.gameMode.canHurtPlayer());
        int guiWidth = event.getGuiGraphics().guiWidth();
        if (layoutWidth != guiWidth) {
            layoutWidth = guiWidth;
            float available = (guiWidth - BAR_WIDTH) / 2f - SLOT_SIZE - 21;
            float healthWidth = Math.min(HEALTH_WIDTH, Math.max(82, available));
            healthBar.layout(layout -> layout.width(healthWidth).bottom(available < 82 ? 44 : 8));
        }
        offhand.setVisible(!player.getOffhandItem().isEmpty());
        boolean left = player.getMainArm().getOpposite() == HumanoidArm.LEFT;
        if (left != offhandOnLeft) {
            offhand.layout(l -> l.left(left ? -SLOT_SIZE - 5 : BAR_WIDTH + 5));
            offhandOnLeft = left;
        }
        LAYER.render(event.getGuiGraphics(), event.getPartialTick());
        renderAttackIndicator(event.getGuiGraphics(), player);
        event.setCanceled(true);
    }

    /** Construction is delayed until rendering, when LDLib2 resources are available. */
    public static ModularUI getOrCreateUI() {
        if (ui == null) {
            var root = new UIElement().layout(l -> l.widthPercent(100).heightPercent(100));
            var bar = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                    .leftPercent(50).marginLeft(-BAR_WIDTH / 2f).bottom(3)
                    .width(BAR_WIDTH).height(SLOT_SIZE));
            root.addChild(bar);
            healthBar = new StatusBar(true).layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                    .left(8).bottom(8));
            staminaBar = new StatusBar(false).layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                    .leftPercent(50).marginLeft(-BAR_WIDTH / 2f).bottom(31));
            root.addChild(healthBar);
            root.addChild(staminaBar);
            for (int visibleSlot = 0; visibleSlot < VISIBLE_SLOT_COUNT; visibleSlot++) {
                int slotX = visibleSlot * SLOT_SIZE;
                bar.addChild(new HotbarSlot(FIRST_VISIBLE_SLOT + visibleSlot)
                        .layout(layout -> layout.positionType(TaffyPosition.ABSOLUTE).left(slotX).top(0)));
            }
            offhandOnLeft = true;
            offhand = new HotbarSlot(-1).layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(-SLOT_SIZE - 5).top(0));
            bar.addChild(offhand);
            ui = ModularUI.of(UI.of(root));
        }
        return ui;
    }

    private static void renderAttackIndicator(GuiGraphics graphics, Player player) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.attackIndicator().get() != AttackIndicatorStatus.HOTBAR) return;
        float attack = mc.player.getAttackStrengthScale(0);
        if (attack >= 1) return;
        int left = (graphics.guiWidth() - BAR_WIDTH) / 2;
        int x = player.getMainArm() == HumanoidArm.RIGHT ? left + BAR_WIDTH + 5 : left - 23;
        int y = graphics.guiHeight() - 23;
        int fill = Math.min(18, (int) (attack * 19));
        graphics.blitSprite(ATTACK_BACKGROUND, x, y, 18, 18);
        if (fill > 0) graphics.blitSprite(ATTACK_PROGRESS, 18, 18, 0, 18 - fill,
                x, y + 18 - fill, 18, fill);
    }

    private static final class HotbarSlot extends UIElement {
        private static final IGuiTexture NORMAL = new GuiTextureGroup(
                new ColorRectTexture(0xB5222222), new ColorBorderTexture(-1, 0xCC696969));
        private static final IGuiTexture SELECTED = new GuiTextureGroup(
                new ColorRectTexture(0xC0444444), new ColorBorderTexture(-1, 0xFFF2F2F2));
        private static final IGuiTexture INSET = new ColorBorderTexture(-1, 0x90202020);
        private final int index;

        private HotbarSlot(int index) {
            this.index = index;
            layout(l -> l.width(SLOT_SIZE).height(SLOT_SIZE));
        }

        @Override
        public void drawBackgroundTexture(GUIContext context) {
            if (!(Minecraft.getInstance().getCameraEntity() instanceof Player player)) return;
            boolean selected = index >= 0 && player.getInventory().selected == index;
            float x = getPositionX(), y = getPositionY();
            context.drawTexture(selected ? SELECTED : NORMAL, x, y, SLOT_SIZE, SLOT_SIZE);
            if (!selected) context.drawTexture(INSET, x + 1, y + 1, SLOT_SIZE - 2, SLOT_SIZE - 2);
        }

        @Override
        public void drawBackgroundAdditional(GUIContext context) {
            var mc = Minecraft.getInstance();
            if (!(mc.getCameraEntity() instanceof Player player)) return;
            var graphics = context.graphics;
            int x = Math.round(getPositionX()), y = Math.round(getPositionY());
            ItemStack stack = index < 0 ? player.getOffhandItem() : player.getInventory().getItem(index);
            if (!stack.isEmpty()) {
                graphics.pose().pushPose();
                float pop = stack.getPopTime() - context.partialTick;
                if (pop > 0) {
                    float scale = 1 + pop / 5;
                    graphics.pose().translate(x + 12, y + 12, 0);
                    graphics.pose().scale(1 / scale, (scale + 1) / 2, 1);
                    graphics.pose().translate(-x - 12, -y - 12, 0);
                }
                graphics.renderItem(player, stack, x + 4, y + 4, index + 2);
                graphics.pose().popPose();
                // Vanilla decorations include count, durability, cooldown and mod decorators.
                graphics.renderItemDecorations(mc.font, stack, x + 4, y + 4);
            }
        }
    }
}

