package dev.tactical.raid.client;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/** Acknowledging the result does not decide the outcome or change inventory client-side. */
public final class RaidSettlementScreen extends Screen {
    private final CompoundTag result;
    private final Runnable onAck;
    private final long openedAt = Util.getMillis();
    private final boolean extracted;
    private final String outcomeText;
    private final int accent;
    private boolean acknowledged;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    public RaidSettlementScreen(CompoundTag result, Runnable onAck) {
        super(Component.literal("对局结算"));
        this.result = result.copy();
        this.onAck = java.util.Objects.requireNonNull(onAck);
        extracted = "EXTRACTED".equals(result.getString("outcome"));
        boolean failed = "DEAD".equals(result.getString("outcome")) || "TIMED_OUT".equals(result.getString("outcome"));
        outcomeText = switch (result.getString("outcome")) {
            case "EXTRACTED" -> "撤离成功";
            case "DEAD" -> "行动失败";
            case "TIMED_OUT" -> "行动超时";
            default -> "对局已中断";
        };
        accent = extracted ? 0xFF35C38B : failed ? 0xFFDE776F : 0xFFB5C4C7;
    }

    @Override protected void init() {
        panelWidth = Math.min(330, Math.max(180, width - 24));
        panelHeight = Math.min(236, Math.max(180, height - 24));
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        addRenderableWidget(new ReturnButton(panelLeft + 20, panelTop + panelHeight - 37,
                panelWidth - 40, 23));
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void onClose() {
        if (!acknowledged) {
            acknowledged = true;
            onAck.run();
        }
        if (minecraft != null && minecraft.screen == this) minecraft.setScreen(null);
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBlurredBackground(partialTick);
        float time = Math.min(1, Math.max(0, (Util.getMillis() - openedAt) / 280f));
        float visible = 1 - (1 - time) * (1 - time) * (1 - time);
        graphics.fill(0, 0, width, height, alpha(0xAC0D151C, visible));
        int left = panelLeft, top = panelTop;
        int right = left + panelWidth, bottom = top + panelHeight;
        graphics.fill(left, top, right, bottom, alpha(0xEE17252E, visible));
        border(graphics, left, top, right, bottom, alpha(0xA86B818D, visible));
        graphics.fill(left, top, left + 3, bottom, alpha(accent, visible));
        graphics.fill(left + 3, top + 1, right - 1, top + 56, alpha(0x69324E52, visible));
        text(graphics, "对局结算", left + 20, top + 12, alpha(0xFFFFFFFF, visible));
        text(graphics, outcomeText, left + 20, top + 31, alpha(accent, visible));
        int row = top + 71;
        String map = result.getString("map");
        if (!map.isBlank()) {
            metric(graphics, "战区", font.plainSubstrByWidth(map, panelWidth - 98), row, visible);
            row += 21;
        }
        long seconds = Math.max(0, result.getLong("elapsedTicks")) / 20;
        String elapsed = String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        metric(graphics, "行动时间", elapsed, row, visible);
        row += 21;
        metric(graphics, "击杀数", Integer.toString(Math.max(0, result.getInt("kills"))), row, visible);
        row += 21;
        if (result.contains("carriedStacks") && row < bottom - 58) {
            metric(graphics, "携带物品", Integer.toString(Math.max(0, result.getInt("carriedStacks"))), row, visible);
        }
        graphics.fill(left + 20, bottom - 47, right - 20, bottom - 46, alpha(0x80576E79, visible));
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void metric(GuiGraphics graphics, String label, String value, int y, float visible) {
        text(graphics, label, panelLeft + 20, y, alpha(0xFFD2DFE3, visible));
        text(graphics, value, panelLeft + panelWidth - 20 - font.width(value), y, alpha(0xFFFFFFFF, visible));
    }

    private void text(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.drawString(font, text, x, y + 1, alpha(0xA0000000, (color >>> 24) / 255f), false);
        graphics.drawString(font, text, x, y, color, false);
    }

    private static int alpha(int color, float visibility) {
        return (Math.round((color >>> 24) * visibility) << 24) | (color & 0xFFFFFF);
    }

    private static void border(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
        graphics.fill(left, top, right, top + 1, color);
        graphics.fill(left, bottom - 1, right, bottom, color);
        graphics.fill(left, top, left + 1, bottom, color);
        graphics.fill(right - 1, top, right, bottom, color);
    }

    private final class ReturnButton extends AbstractButton {
        private ReturnButton(int x, int y, int width, int height) {
            super(x, y, width, height, Component.literal("返回"));
        }

        @Override public void onPress() { RaidSettlementScreen.this.onClose(); }

        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int background = isHoveredOrFocused() ? 0xFF34586A : 0xDD223A4B;
            int outline = isHoveredOrFocused() ? accent : 0xFF6B818D;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), background);
            border(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(), outline);
            String label = getMessage().getString();
            text(graphics, label, getX() + (getWidth() - font.width(label)) / 2,
                    getY() + (getHeight() - font.lineHeight) / 2, 0xFFFFFFFF);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
