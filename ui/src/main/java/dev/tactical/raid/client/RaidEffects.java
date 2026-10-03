package dev.tactical.raid.client;

import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns independent Photon runtimes; server packets only describe where an effect belongs. */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD)
public final class RaidEffects {
    public static final String DEFAULT_FX = "tactical_inventory:evacuation_smoke";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double VIEW_DISTANCE_SQUARED = 128 * 128;
    private static final int MAX_ACTIVE_EFFECTS = 16;
    private static final int LOAD_RETRY_TICKS = 100;

    private static final Map<String, ActiveSmoke> ACTIVE = new HashMap<>();
    private static final Map<ResourceLocation, FX> DEFINITIONS = new HashMap<>();
    private static final Map<ResourceLocation, Long> RETRY_AFTER = new HashMap<>();
    private static final Set<ResourceLocation> WARNED = new HashSet<>();
    private static List<SmokeZone> zones = List.of();
    private static ClientLevel world;
    private static long ticks;

    private RaidEffects() {}

    public record SmokeZone(String id, String dimension, double x, double y, double z, String fx) {
        public SmokeZone(String id, String dimension, double x, double y, double z) {
            this(id, dimension, x, y, z, DEFAULT_FX);
        }

        private boolean valid() {
            return id != null && !id.isBlank() && dimension != null
                    && ResourceLocation.tryParse(dimension) != null
                    && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && fx != null && ResourceLocation.tryParse(fx) != null;
        }
    }

    private record ActiveSmoke(SmokeZone zone, FXRuntime runtime) {}

    /** Called on the client thread after a server zone update. Duplicate IDs never emit twice. */
    public static void setZones(List<SmokeZone> incoming) {
        Map<String, SmokeZone> unique = new LinkedHashMap<>();
        for (SmokeZone zone : incoming) {
            if (zone != null && zone.valid()) unique.put(zone.id(), zone);
        }
        zones = List.copyOf(unique.values());
        ACTIVE.entrySet().removeIf(entry -> {
            if (entry.getValue().zone().equals(unique.get(entry.getKey()))) return false;
            entry.getValue().runtime().destroy(true);
            return true;
        });
    }

    /** Photon owns particle ticking/rendering. This only manages world, range and runtime lifetime. */
    public static void tick() {
        ticks++;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != world) {
            destroyRuntimes();
            world = level;
        }
        if (level == null || minecraft.player == null) return;

        String dimension = level.dimension().location().toString();
        List<SmokeZone> nearby = new ArrayList<>();
        for (SmokeZone zone : zones) {
            if (!zone.dimension().equals(dimension)) continue;
            if (minecraft.player.distanceToSqr(zone.x(), zone.y(), zone.z()) > VIEW_DISTANCE_SQUARED) continue;
            if (!level.hasChunkAt(BlockPos.containing(zone.x(), zone.y(), zone.z()))) continue;
            nearby.add(zone);
        }
        nearby.sort(Comparator.comparingDouble(zone ->
                minecraft.player.distanceToSqr(zone.x(), zone.y(), zone.z())));
        if (nearby.size() > MAX_ACTIVE_EFFECTS) nearby.subList(MAX_ACTIVE_EFFECTS, nearby.size()).clear();

        Set<String> visible = new HashSet<>();
        for (SmokeZone zone : nearby) visible.add(zone.id());
        ACTIVE.entrySet().removeIf(entry -> {
            if (visible.contains(entry.getKey())) return false;
            entry.getValue().runtime().destroy(true);
            return true;
        });

        for (SmokeZone zone : nearby) {
            ActiveSmoke active = ACTIVE.get(zone.id());
            if (active != null && active.runtime().isValid() && !active.runtime().isFinished()) continue;
            if (active != null) {
                active.runtime().destroy(true);
                ACTIVE.remove(zone.id());
            }
            start(zone, level);
        }
    }

    private static void start(SmokeZone zone, ClientLevel level) {
        ResourceLocation id = ResourceLocation.parse(zone.fx());
        if (ticks < RETRY_AFTER.getOrDefault(id, 0L)) return;
        FXRuntime runtime = null;
        try {
            FX definition = DEFINITIONS.get(id);
            if (definition == null) {
                // A resource reload may run before Photon's own cache listener: read the current stack once.
                definition = FXHelper.getFX(id, false);
                if (definition == null) {
                    retry(id, null);
                    return;
                }
                DEFINITIONS.put(id, definition);
            }
            runtime = definition.createRuntime();
            runtime.root.updatePos(new Vector3f((float) zone.x(), (float) zone.y(), (float) zone.z()));
            runtime.emit(new IEffectExecutor() {
                @Override
                public Level getLevel() {
                    return level;
                }
            });
            ACTIVE.put(zone.id(), new ActiveSmoke(zone, runtime));
            RETRY_AFTER.remove(id);
            WARNED.remove(id);
        } catch (RuntimeException exception) {
            if (runtime != null) runtime.destroy(true);
            retry(id, exception);
        }
    }

    private static void retry(ResourceLocation id, RuntimeException failure) {
        RETRY_AFTER.put(id, ticks + LOAD_RETRY_TICKS);
        if (!WARNED.add(id)) return;
        if (failure == null) LOGGER.warn("Could not load raid extraction effect {}", id);
        else LOGGER.warn("Could not play raid extraction effect {}", id, failure);
    }

    /** Disconnect/server change: dispose all emitted objects and forget the previous server's zones. */
    public static void clear() {
        zones = List.of();
        world = null;
        destroyRuntimes();
        DEFINITIONS.clear();
        RETRY_AFTER.clear();
        WARNED.clear();
    }

    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager ->
                Minecraft.getInstance().execute(RaidEffects::onResourceReload));
    }

    /** Keeps configured zones, but replaces authored data and particles after a resource pack reload. */
    public static void onResourceReload() {
        destroyRuntimes();
        DEFINITIONS.clear();
        RETRY_AFTER.clear();
        WARNED.clear();
    }

    private static void destroyRuntimes() {
        for (ActiveSmoke smoke : ACTIVE.values()) smoke.runtime().destroy(true);
        ACTIVE.clear();
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    /** Useful for diagnosing a missing or externally cleared Photon effect. */
    public static FXRuntime activeRuntime(String zoneId) {
        ActiveSmoke smoke = ACTIVE.get(zoneId);
        return smoke == null ? null : smoke.runtime();
    }
}
