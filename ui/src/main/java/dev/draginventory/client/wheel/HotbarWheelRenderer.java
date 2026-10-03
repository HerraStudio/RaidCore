package dev.draginventory.client.wheel;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Five native inventory items in an animated, resolution-independent tactical wheel. */
public final class HotbarWheelRenderer {
    private static final int MINT = 0x35D8A0;
    private static final int TEXT = 0xE0E9E8;
    private static final int MUTED = 0x829594;
    private static final int PANEL = 0x0B1519;
    private static final int BORDER = 0x526365;
    private static final double TWO_PI = Math.PI * 2;
    private static final double HALF_SECTOR = Math.PI / WheelGeometry.SECTORS;
    private static final double GAP = Math.toRadians(1.2);
    private static TextureTarget itemTarget;

    private HotbarWheelRenderer() {}

    public static double centerX(int guiWidth) { return guiWidth / 2.0; }
    public static double centerY(int guiHeight) { return guiHeight / 2.0; }

    public static double outerRadius(int guiWidth, int guiHeight) {
        return Math.min(112, Math.min(guiWidth, guiHeight) * 0.34);
    }

    public static double deadRadius(int guiWidth, int guiHeight) {
        return Math.min(34, outerRadius(guiWidth, guiHeight) * 0.34);
    }

    /** Called when the client leaves a world; the next render recreates the small icon target. */
    public static void close() {
        if (itemTarget != null) {
            itemTarget.destroyBuffers();
            itemTarget = null;
        }
    }

    public static void render(GuiGraphics graphics, ItemStack[] stacks, int currentSlot,
                              int hoveredSector, WheelAnimation.Frame frame,
                              double mouseX, double mouseY) {
        float alpha = Mth.clamp(frame.alpha(), 0, 1);
        // Vanilla's text renderer treats almost-transparent packed colors as opaque.
        if (alpha < 0.025f) return;
        Minecraft mc = Minecraft.getInstance();
        int width = graphics.guiWidth(), height = graphics.guiHeight();
        double radius = outerRadius(width, height);
        double inner = deadRadius(width, height);
        double cx = centerX(width), cy = centerY(height);
        float[] highlights = frame.highlights();
        graphics.flush();
        float[] oldColor = RenderSystem.getShaderColor().clone();
        boolean oldBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean oldDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean oldCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        try {
            graphics.fill(0, 0, width, height, rgba(0x02080B, alpha * 0.30f));
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(cx, cy, 200);
                graphics.pose().scale(frame.scale(), frame.scale(), 1);
                ring(graphics, 0, radius + 7, 0, TWO_PI, rgba(0x000000, alpha * 0.15f));
                ring(graphics, radius + 3, radius + 4, 0, TWO_PI, rgba(BORDER, alpha * 0.13f));
                for (int sector = 0; sector < WheelGeometry.SECTORS; sector++) {
                    float highlight = Mth.clamp(highlights[sector], 0, 1);
                    double angle = WheelGeometry.angle(sector) + frame.rotation();
                    graphics.pose().pushPose();
                    try {
                        graphics.pose().translate(Math.cos(angle) * highlight * 3,
                                Math.sin(angle) * highlight * 3, 0);
                        double start = angle - HALF_SECTOR + GAP;
                        double end = angle + HALF_SECTOR - GAP;
                        int fill = blend(PANEL, 0x17473F, highlight * 0.74f);
                        ring(graphics, inner, radius, start, end, rgba(fill, alpha * 0.94f));
                        // A narrow low-opacity fringe softens the curved silhouette at any GUI scale.
                        ring(graphics, radius, radius + 0.8, start, end, rgba(fill, alpha * 0.28f));
                        ring(graphics, inner - 0.7, inner, start, end, rgba(fill, alpha * 0.22f));
                        ring(graphics, inner, inner + 0.8, start, end,
                                rgba(blend(BORDER, MINT, highlight), alpha * (0.34f + highlight * 0.40f)));
                        ring(graphics, radius - 0.8, radius, start, end,
                                rgba(blend(BORDER, MINT, highlight), alpha * (0.48f + highlight * 0.50f)));
                        if (WheelGeometry.FIRST_SLOT + sector == currentSlot) {
                            ring(graphics, radius - 4.3, radius - 3.3, start + 0.06, end - 0.06,
                                    rgba(TEXT, alpha * 0.85f));
                        }
                        if (highlight > 0.001f) {
                            ring(graphics, radius - 7, radius - 5.7, start + 0.05, end - 0.05,
                                    rgba(MINT, alpha * highlight * 0.42f));
                        }
                    } finally {
                        graphics.pose().popPose();
                    }
                }
                ring(graphics, 0, inner - 2, 0, TWO_PI, rgba(PANEL, alpha * 0.90f));
                ring(graphics, inner - 6, inner - 5.3, 0, TWO_PI, rgba(BORDER, alpha * 0.24f));

                float selectionStrength = 0;
                for (float highlight : highlights) selectionStrength = Math.max(selectionStrength, highlight);
                if (selectionStrength > 0.001f) {
                    double markerAngle = frame.highlightAngle() + frame.rotation();
                    double markerWidth = Math.toRadians(9 + 3 * frame.selectionPulse());
                    ring(graphics, inner - 3, inner + 1.5, markerAngle - markerWidth,
                            markerAngle + markerWidth, rgba(MINT, alpha * selectionStrength));
                    ring(graphics, radius + 1.5, radius + 3.2, markerAngle - 0.12,
                            markerAngle + 0.12, rgba(MINT, alpha * selectionStrength * 0.90f));
                }
                for (int sector = 0; sector < WheelGeometry.SECTORS; sector++) {
                    renderSectorItem(graphics, mc, stack(stacks, sector), sector, radius,
                            frame.rotation(), highlights[sector], alpha);
                }
                renderCenter(graphics, mc.font, stacks, hoveredSector, inner, alpha);
            } finally {
                graphics.flush();
                graphics.pose().popPose();
            }

            float titleY = (float) Math.max(5, cy - radius * frame.scale() - 24);
            centered(graphics, mc.font, Component.translatable("draginventory.wheel.title").getString(),
                    (float) cx, titleY, 0.90f, TEXT, alpha * 0.94f, width - 20);
        } finally {
            graphics.flush();
            RenderSystem.setShaderColor(oldColor[0], oldColor[1], oldColor[2], oldColor[3]);
            if (oldDepth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (oldCull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if (oldBlend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        }
    }

    private static void renderSectorItem(GuiGraphics graphics, Minecraft mc, ItemStack stack,
                                         int sector, double radius, float rotation,
                                         float highlight, float alpha) {
        highlight = Mth.clamp(highlight, 0, 1);
        double angle = WheelGeometry.angle(sector) + rotation;
        double itemRadius = radius * 0.70 + highlight * 3;
        float x = (float) (Math.cos(angle) * itemRadius);
        float y = (float) (Math.sin(angle) * itemRadius);
        float scale = Mth.clamp((float) radius / 76, 1, 1.38f) * (1 + highlight * 0.055f);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x, y - 3, 0);
            graphics.pose().scale(scale, scale, 1);
            if (stack.isEmpty()) {
                centered(graphics, mc.font, "—", 0, -4, 1, MUTED, alpha * 0.43f, 30);
            } else {
                compositeItem(graphics, mc, stack, alpha);
            }
        } finally {
            graphics.pose().popPose();
        }
        String key = mc.options.keyHotbarSlots[WheelGeometry.FIRST_SLOT + sector]
                .getTranslatedKeyMessage().getString();
        // Rebound keys keep their real name, with automatic fitting for long labels.
        float keyY = y + scale * 11 + 4;
        int keyWidth = Math.max(12, Math.min(36, mc.font.width(key) + 7));
        graphics.fill(Math.round(x - keyWidth / 2f), Math.round(keyY - 2),
                Math.round(x + keyWidth / 2f), Math.round(keyY + 8), rgba(0x061014, alpha * 0.70f));
        graphics.fill(Math.round(x - keyWidth / 2f), Math.round(keyY + 8),
                Math.round(x + keyWidth / 2f), Math.round(keyY + 9),
                rgba(blend(BORDER, MINT, highlight), alpha * (0.30f + highlight * 0.65f)));
        centered(graphics, mc.font, key, x, keyY, 0.76f,
                blend(MUTED, TEXT, highlight), alpha * (stack.isEmpty() ? 0.65f : 0.92f), keyWidth - 4);
    }

    private static void renderCenter(GuiGraphics graphics, Font font, ItemStack[] stacks,
                                     int hoveredSector, double inner, float alpha) {
        String name;
        if (hoveredSector >= 0 && hoveredSector < WheelGeometry.SECTORS) {
            ItemStack item = stack(stacks, hoveredSector);
            name = item.isEmpty() ? Component.translatable("draginventory.wheel.empty").getString()
                    : item.getHoverName().getString();
        } else {
            name = Component.translatable("draginventory.wheel.title").getString();
        }
        graphics.fill(-1, -15, 1, -13, rgba(hoveredSector >= 0 ? MINT : MUTED, alpha * 0.90f));
        float maxWidth = (float) inner * 1.55f;
        float textScale = 0.78f;
        int available = Math.max(12, (int) (maxWidth / textScale));
        if (font.width(name) > available) {
            // Break on font glyph boundaries rather than clipping Chinese or custom item names.
            String first = font.plainSubstrByWidth(name, available);
            String second = name.substring(first.length());
            if (font.width(second) > available) {
                second = font.plainSubstrByWidth(second, Math.max(6, available - font.width("…"))) + "…";
            }
            centered(graphics, font, first, 0, -4, textScale, TEXT, alpha, maxWidth);
            centered(graphics, font, second, 0, 7, textScale, TEXT, alpha * 0.86f, maxWidth);
        } else {
            centered(graphics, font, name, 0, 0, textScale, TEXT, alpha, maxWidth);
        }
    }

    private static ItemStack stack(ItemStack[] stacks, int sector) {
        // Accept either a five-item wheel snapshot or a full nine-slot hotbar snapshot.
        int index = stacks.length == WheelGeometry.SECTORS ? sector : WheelGeometry.FIRST_SLOT + sector;
        return index >= 0 && index < stacks.length && stacks[index] != null ? stacks[index] : ItemStack.EMPTY;
    }

    private static void centered(GuiGraphics graphics, Font font, String text, float x, float y,
                                 float scale, int rgb, float alpha, float maxWidth) {
        if (alpha < 0.025f || text.isEmpty()) return;
        int width = font.width(text);
        if (width > 0) scale = Math.min(scale, maxWidth / width);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x, y, 0);
            graphics.pose().scale(scale, scale, 1);
            graphics.drawString(font, text, -width / 2, 0, rgba(rgb, alpha), false);
        } finally {
            graphics.pose().popPose();
        }
    }

    private static void ring(GuiGraphics graphics, double inner, double outer,
                             double start, double end, int color) {
        if ((color >>> 24) == 0 || end <= start || outer <= inner) return;
        graphics.flush();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f pose = graphics.pose().last().pose();
        int pieces = Math.max(1, (int) Math.ceil((end - start) * outer / 3));
        for (int piece = 0; piece < pieces; piece++) {
            double a = start + (end - start) * piece / pieces;
            double b = start + (end - start) * (piece + 1) / pieces;
            buffer.addVertex(pose, (float) (Math.cos(a) * inner), (float) (Math.sin(a) * inner), 0).setColor(color);
            buffer.addVertex(pose, (float) (Math.cos(a) * outer), (float) (Math.sin(a) * outer), 0).setColor(color);
            buffer.addVertex(pose, (float) (Math.cos(b) * outer), (float) (Math.sin(b) * outer), 0).setColor(color);
            buffer.addVertex(pose, (float) (Math.cos(b) * inner), (float) (Math.sin(b) * inner), 0).setColor(color);
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    /** Fade the whole vanilla item/decorations result; opaque block models ignore shader alpha. */
    private static void compositeItem(GuiGraphics graphics, Minecraft mc, ItemStack stack, float alpha) {
        graphics.flush();
        int drawTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readTarget = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        float[] color = RenderSystem.getShaderColor().clone();
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        RenderSystem.getModelViewStack().pushMatrix();
        try {
            if (itemTarget == null) {
                itemTarget = new TextureTarget(96, 96, true, Minecraft.ON_OSX);
                itemTarget.setClearColor(0, 0, 0, 0);
                itemTarget.setFilterMode(GL11.GL_LINEAR);
            }
            itemTarget.clear(Minecraft.ON_OSX);
            itemTarget.bindWrite(true);
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, 24, 24, 0, -1000, 1000), VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.getModelViewStack().identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            var itemGraphics = new GuiGraphics(mc, graphics.bufferSource());
            itemGraphics.renderItem(stack, 4, 4);
            itemGraphics.renderItemDecorations(mc.font, stack, 4, 4, Integer.toString(stack.getCount()));
            itemGraphics.flush();
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderTexture(0, itemTarget.getColorTextureId());
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        var pose = graphics.pose().last().pose();
        buffer.addVertex(pose, -12, -12, 0).setUv(0, 1).setColor(1f, 1f, 1f, alpha);
        buffer.addVertex(pose, -12, 12, 0).setUv(0, 0).setColor(1f, 1f, 1f, alpha);
        buffer.addVertex(pose, 12, 12, 0).setUv(1, 0).setColor(1f, 1f, 1f, alpha);
        buffer.addVertex(pose, 12, -12, 0).setUv(1, 1).setColor(1f, 1f, 1f, alpha);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    private static int rgba(int rgb, float alpha) {
        return Math.round(Mth.clamp(alpha, 0, 1) * 255) << 24 | rgb & 0xFFFFFF;
    }

    private static int blend(int from, int to, float amount) {
        amount = Mth.clamp(amount, 0, 1);
        int red = Math.round((from >>> 16 & 255) + ((to >>> 16 & 255) - (from >>> 16 & 255)) * amount);
        int green = Math.round((from >>> 8 & 255) + ((to >>> 8 & 255) - (from >>> 8 & 255)) * amount);
        int blue = Math.round((from & 255) + ((to & 255) - (from & 255)) * amount);
        return red << 16 | green << 8 | blue;
    }
}
