package dev.draginventory.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.slf4j.LoggerFactory;
import org.lwjgl.opengl.GL11;

/** White alpha masks of each weapon's own HUD/item texture, cached independently of GWO's main HUD. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class WeaponSlotIcons {
    private static final Map<ResourceLocation, Optional<Icon>> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static final int MAX_ENTRIES = 32;

    private WeaponSlotIcons() {}

    @SubscribeEvent
    public static void registerReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            if (RenderSystem.isOnRenderThread()) clear();
            else RenderSystem.recordRenderCall(WeaponSlotIcons::clear);
        });
    }

    public static boolean draw(GuiGraphics graphics, ItemStack stack, int x, int y, int width, int height) {
        var sources = GwoHudBridge.icons(stack);
        if (sources == null) return false;
        Icon icon = get(sources.hud());
        if (icon == null) icon = get(sources.item());
        if (icon == null) return false;
        float scale = Math.min(width / (float) icon.width(), height / (float) icon.height());
        graphics.flush();
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        float[] color = RenderSystem.getShaderColor().clone();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x + (width - icon.width() * scale) / 2f,
                    y + (height - icon.height() * scale) / 2f, 0);
            graphics.pose().scale(scale, scale, 1);
            graphics.blit(icon.texture(), 0, 0, 0, 0, icon.width(), icon.height(), icon.width(), icon.height());
        } finally {
            graphics.pose().popPose();
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            if (!blend) RenderSystem.disableBlend();
        }
        return true;
    }

    private static Icon get(ResourceLocation source) {
        if (source == null) return null;
        Optional<Icon> cached = CACHE.get(source);
        if (cached == null) {
            cached = Optional.ofNullable(load(source));
            CACHE.put(source, cached);
            while (CACHE.size() > MAX_ENTRIES) {
                var oldest = CACHE.entrySet().iterator();
                oldest.next().getValue().ifPresent(WeaponSlotIcons::release);
                oldest.remove();
            }
        }
        return cached.orElse(null);
    }

    private static Icon load(ResourceLocation source) {
        var minecraft = Minecraft.getInstance();
        var resource = minecraft.getResourceManager().getResource(source);
        if (resource.isEmpty()) return null;
        try (var stream = resource.get().open(); var image = NativeImage.read(stream)) {
            int minX = image.getWidth(), minY = image.getHeight(), maxX = -1, maxY = -1;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getPixelRGBA(x, y) >>> 24) < 16) continue;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
            if (maxX < minX) return null;
            int width = maxX - minX + 1, height = maxY - minY + 1;
            var mask = new NativeImage(width, height, false);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int alpha = image.getPixelRGBA(x + minX, y + minY) & 0xFF000000;
                    mask.setPixelRGBA(x, y, alpha | 0x00FFFFFF);
                }
            }
            var texture = new DynamicTexture(mask);
            texture.setFilter(true, false);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("draginventory",
                    "weapon_masks/" + source.getNamespace() + "/" + source.getPath());
            minecraft.getTextureManager().register(id, texture);
            return new Icon(id, width, height);
        } catch (IOException | RuntimeException e) {
            LoggerFactory.getLogger(WeaponSlotIcons.class).warn("Cannot read weapon HUD texture {}", source, e);
            return null;
        }
    }

    private static void release(Icon icon) {
        Minecraft.getInstance().getTextureManager().release(icon.texture());
    }

    private static void clear() {
        CACHE.values().forEach(icon -> icon.ifPresent(WeaponSlotIcons::release));
        CACHE.clear();
    }

    private record Icon(ResourceLocation texture, int width, int height) {}
}
