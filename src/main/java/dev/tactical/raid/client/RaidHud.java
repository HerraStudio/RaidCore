package dev.tactical.raid.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Client presentation only: countdown completion is always decided by the server. */
public final class RaidHud {
    private static final int FADE_MILLIS = 230;
    private static final int GREEN = 0xFF24CA88;
    private UUID session;
    private String zone = "";
    private boolean extracting;
    private double remainingTicks;
    private double clockCorrection;
    private int totalTicks = 400;
    private long syncedAt;
    private long transitionAt;
    private float transitionFrom;
    private float transitionTo;
    private long pulseAt;
    private int displayedSeconds = -1;

    public void accept(CompoundTag snapshot) {
        long now = Util.getMillis();
        boolean nextExtracting = "EXTRACTING".equals(snapshot.getString("status"));
        UUID nextSession = snapshot.hasUUID("session") ? snapshot.getUUID("session") : null;
        String nextZone = snapshot.getString("zone");
        boolean sameCountdown = extracting && nextExtracting
                && java.util.Objects.equals(session, nextSession) && zone.equals(nextZone);
        if (nextExtracting) {
            double previous = remainingAt(now);
            double ticks = Math.max(1, snapshot.getInt("remainingTicks"));
            // Always trust a fresh server clock, including after a slow or stalled server tick.
            // Blend small corrections without restarting the entry animation.
            remainingTicks = ticks;
            clockCorrection = sameCountdown ? Math.max(-5, Math.min(5, previous - ticks)) : 0;
            totalTicks = Math.max(1, snapshot.getInt("totalTicks"));
            syncedAt = now;
            session = nextSession;
            zone = nextZone;
            if (!sameCountdown) displayedSeconds = -1;
        } else if (extracting) {
            remainingTicks = remainingAt(now);
            clockCorrection = 0;
            syncedAt = now;
        }
        if (extracting != nextExtracting) {
            transitionFrom = visibility(now);
            transitionTo = nextExtracting ? 1 : 0;
            transitionAt = now;
        }
        extracting = nextExtracting;
    }

    public void clear() {
        session = null;
        zone = "";
        extracting = false;
        remainingTicks = 0;
        clockCorrection = 0;
        syncedAt = transitionAt = pulseAt = 0;
        transitionFrom = transitionTo = 0;
        displayedSeconds = -1;
    }

    public void render(GuiGraphics graphics, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui) return;
        long now = Util.getMillis();
        float visible = visibility(now);
        if (visible <= 0.001f) return;
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int panelWidth = Math.max(120, Math.min(260, screenWidth - 20));
        int left = (screenWidth - panelWidth) / 2;
        // The existing compass occupies the first row of the HUD.
        int top = Math.max(8, Math.min(42, screenHeight / 6)) - Math.round((1 - visible) * 8);
        int right = left + panelWidth;
        int bottom = top + 88;
        double ticks = remainingAt(now);
        int seconds = (int) Math.ceil(ticks / 20.0);
        if (extracting && seconds != displayedSeconds) {
            if (displayedSeconds >= 0 && seconds < displayedSeconds) pulseAt = now;
            displayedSeconds = seconds;
        }
        float pulseProgress = Math.min(1, Math.max(0, (now - pulseAt) / 220f));
        float pulse = pulseAt == 0 ? 0 : (float) Math.sin(Math.PI * pulseProgress);
        float progress = (float) Math.max(0, Math.min(1, 1 - ticks / totalTicks));

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 450);
        graphics.fill(left, top, right, bottom, alpha(0xDB152127, visible));
        graphics.fill(left + 2, top + 1, right - 2, top + 23, alpha(0x70337C60, visible));
        graphics.fill(left, top, left + 2, bottom, alpha(GREEN, visible));
        graphics.fill(right - 2, top, right, bottom, alpha(GREEN, visible));
        graphics.fill(left + 2, top, right - 2, top + 1, alpha(0x8875AA99, visible));
        graphics.fill(left + 2, bottom - 1, right - 2, bottom, alpha(0x8875AA99, visible));
        centered(graphics, "即将撤离", (left + right) / 2, top + 7, alpha(0xFFFFFFFF, visible));
        for (int x = left + 5; x < right - 5; x += 9) {
            graphics.fill(x, top + 24, Math.min(x + 5, right - 5), top + 25, alpha(0xC035B97C, visible));
        }
        String zoneName = minecraft.font.plainSubstrByWidth(zone, panelWidth - 22);
        if (!zoneName.isBlank()) centered(graphics, zoneName, (left + right) / 2, top + 30,
                alpha(0xFFE0E9E5, visible));
        String clock = String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        float clockScale = 1.22f + pulse * 0.055f;
        int clockWidth = digitalWidth(clock);
        graphics.pose().pushPose();
        graphics.pose().translate((left + right) / 2f, top + 43, 0);
        graphics.pose().scale(clockScale, clockScale, 1);
        digitalClock(graphics, clock, -clockWidth / 2, 0, alpha(GREEN, visible));
        graphics.pose().popPose();
        int trackLeft = left + 10;
        int trackRight = right - 10;
        graphics.fill(trackLeft, bottom - 9, trackRight, bottom - 6, alpha(0xAA2D4542, visible));
        graphics.fill(trackLeft, bottom - 9, trackLeft + Math.round((trackRight - trackLeft) * progress),
                bottom - 6, alpha(GREEN, visible));
        graphics.pose().popPose();
    }

    private double remainingAt(long now) {
        if (!extracting) return remainingTicks;
        double elapsed = Math.max(0, now - syncedAt);
        // Snapshots arrive every five server ticks. After one missing update, wait for the
        // server rather than count to a fictional completion while the server is stalled.
        double prediction = Math.min(5, elapsed / 50.0);
        double correctionBlend = Math.max(0, 1 - elapsed / 150.0);
        return Math.max(1, remainingTicks - prediction
                + clockCorrection * correctionBlend * correctionBlend);
    }

    private float visibility(long now) {
        float time = Math.min(1, Math.max(0, (now - transitionAt) / (float) FADE_MILLIS));
        return transitionFrom + (transitionTo - transitionFrom) * bezier(time);
    }

    /** Cubic-bezier(0.18, 0.72, 0.25, 1), including its time-axis inversion. */
    private static float bezier(float x) {
        float low = 0, high = 1, t = x;
        for (int i = 0; i < 12; i++) {
            float inverse = 1 - t;
            float curveX = 3 * inverse * inverse * t * 0.18f + 3 * inverse * t * t * 0.25f + t * t * t;
            if (curveX < x) low = t; else high = t;
            t = (low + high) * 0.5f;
        }
        float inverse = 1 - t;
        return 3 * inverse * inverse * t * 0.72f + 3 * inverse * t * t + t * t * t;
    }

    private static int alpha(int color, float visibility) {
        return (Math.round((color >>> 24) * visibility) << 24) | (color & 0xFFFFFF);
    }

    private static void centered(GuiGraphics graphics, String text, int center, int y, int color) {
        var font = Minecraft.getInstance().font;
        int left = center - font.width(text) / 2;
        graphics.drawString(font, text, left, y + 1, alpha(0x99000000, (color >>> 24) / 255f), false);
        graphics.drawString(font, text, left, y, color, false);
    }

    private static int digitalWidth(String text) {
        int width = -3;
        for (int i = 0; i < text.length(); i++) width += text.charAt(i) == ':' ? 7 : 16;
        return width;
    }

    private static void digitalClock(GuiGraphics graphics, String clock, int x, int y, int color) {
        int[] segments = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};
        for (int i = 0; i < clock.length(); i++) {
            char digit = clock.charAt(i);
            if (digit == ':') {
                graphics.fill(x + 1, y + 7, x + 4, y + 10, color);
                graphics.fill(x + 1, y + 16, x + 4, y + 19, color);
                x += 7;
                continue;
            }
            int bits = segments[digit - '0'];
            if ((bits & 1) != 0) graphics.fill(x + 3, y, x + 10, y + 3, color);
            if ((bits & 2) != 0) graphics.fill(x + 10, y + 3, x + 13, y + 11, color);
            if ((bits & 4) != 0) graphics.fill(x + 10, y + 14, x + 13, y + 22, color);
            if ((bits & 8) != 0) graphics.fill(x + 3, y + 22, x + 10, y + 25, color);
            if ((bits & 16) != 0) graphics.fill(x, y + 14, x + 3, y + 22, color);
            if ((bits & 32) != 0) graphics.fill(x, y + 3, x + 3, y + 11, color);
            if ((bits & 64) != 0) graphics.fill(x + 3, y + 11, x + 10, y + 14, color);
            x += 16;
        }
    }
}
