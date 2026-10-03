package dev.draginventory.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** Antialiased keycaps, rasterized at eight times HUD resolution instead of enlarging bitmap letters. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class HudKeyIcons {
    public static final int HEIGHT = 13;
    private static final int RESOLUTION = 8;
    private static final Map<String, Icon> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static int serial;

    private HudKeyIcons() {}

    @SubscribeEvent
    public static void registerReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            if (RenderSystem.isOnRenderThread()) clear();
            else RenderSystem.recordRenderCall(HudKeyIcons::clear);
        });
    }

    public static int width(String label) {
        return get(label).width();
    }

    public static void draw(GuiGraphics graphics, String label, int x, int y, boolean enabled) {
        Icon icon = get(label);
        graphics.flush();
        float[] previous = RenderSystem.getShaderColor().clone();
        float shade = enabled ? 1f : 0.55f;
        RenderSystem.setShaderColor(shade, shade, shade, 1);
        try {
            graphics.blit(icon.texture(), x, y, icon.width(), HEIGHT, 0, 0,
                    icon.width() * RESOLUTION, HEIGHT * RESOLUTION,
                    icon.width() * RESOLUTION, HEIGHT * RESOLUTION);
        } finally {
            RenderSystem.setShaderColor(previous[0], previous[1], previous[2], previous[3]);
        }
    }

    private static Icon get(String label) {
        Icon existing = CACHE.get(label);
        if (existing != null) return existing;
        // Logical fonts use the platform's font fallback, including translated key names.
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 9 * RESOLUTION);
        var scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        scratch.setFont(font);
        int textWidth = scratch.getFontMetrics().stringWidth(label);
        scratch.dispose();
        int width = Math.max(13, Math.min(48, (int) Math.ceil(textWidth / (double) RESOLUTION) + 6));
        var bitmap = new BufferedImage(width * RESOLUTION, HEIGHT * RESOLUTION, BufferedImage.TYPE_INT_ARGB);
        var painter = bitmap.createGraphics();
        painter.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        painter.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        painter.setColor(Color.WHITE);
        painter.fillRoundRect(0, 0, bitmap.getWidth(), bitmap.getHeight(), 3 * RESOLUTION, 3 * RESOLUTION);
        if (textWidth > (width - 4) * RESOLUTION)
            font = font.deriveFont(font.getSize2D() * (width - 4) * RESOLUTION / textWidth);
        painter.setFont(font);
        painter.setColor(new Color(0x202020));
        var metrics = painter.getFontMetrics();
        painter.drawString(label, (bitmap.getWidth() - metrics.stringWidth(label)) / 2f,
                (bitmap.getHeight() - metrics.getAscent() - metrics.getDescent()) / 2f + metrics.getAscent());
        painter.dispose();
        var pixels = new NativeImage(bitmap.getWidth(), bitmap.getHeight(), false);
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) {
            int argb = bitmap.getRGB(x, y);
            pixels.setPixelRGBA(x, y, (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >>> 16) & 0xFF));
        }
        var texture = new DynamicTexture(pixels);
        texture.setFilter(true, false);
        var id = ResourceLocation.fromNamespaceAndPath("draginventory", "hud_keys/" + serial++);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        Icon result = new Icon(id, width);
        CACHE.put(label, result);
        while (CACHE.size() > 32) {
            var oldest = CACHE.entrySet().iterator();
            Minecraft.getInstance().getTextureManager().release(oldest.next().getValue().texture());
            oldest.remove();
        }
        return result;
    }

    private static void clear() {
        CACHE.values().forEach(icon -> Minecraft.getInstance().getTextureManager().release(icon.texture()));
        CACHE.clear();
    }

    private record Icon(ResourceLocation texture, int width) {}
}
