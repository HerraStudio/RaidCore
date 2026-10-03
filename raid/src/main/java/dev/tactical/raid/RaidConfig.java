package dev.tactical.raid;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class RaidConfig {
    public static final String DEFAULT_FX = "tactical_inventory:evacuation_smoke";
    public String mapName = "战术行动";
    public Location lobby;
    public final Map<String, Location> spawns = new LinkedHashMap<>();
    public final Map<String, Extraction> extractions = new LinkedHashMap<>();
    public int nextSpawn;

    public record Location(String dimension, double x, double y, double z, float yaw, float pitch) {
        public Location {
            if (dimension == null || dimension.isBlank() || !Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("Invalid raid location");
            }
        }
        public CompoundTag write() {
            var tag = new CompoundTag();
            tag.putString("dimension", dimension);
            tag.putDouble("x", x); tag.putDouble("y", y); tag.putDouble("z", z);
            tag.putFloat("yaw", yaw); tag.putFloat("pitch", pitch);
            return tag;
        }
        public static Location read(CompoundTag tag) {
            return new Location(tag.getString("dimension"), tag.getDouble("x"), tag.getDouble("y"),
                    tag.getDouble("z"), tag.getFloat("yaw"), tag.getFloat("pitch"));
        }
    }

    public record Extraction(String id, Location location, double radius, int seconds, String fx) {
        public Extraction {
            if (id == null || id.isBlank() || location == null || !Double.isFinite(radius)
                    || radius < .5 || radius > 128 || seconds < 1 || seconds > 3600) {
                throw new IllegalArgumentException("Invalid extraction zone");
            }
        }
        public RaidBounds bounds() { return RaidBounds.square(location.x(), location.y(), location.z(), radius); }
        public CompoundTag write() {
            var tag = location.write();
            tag.putString("id", id); tag.putString("name", id);
            tag.putDouble("radius", radius); tag.putInt("seconds", seconds); tag.putString("fx", fx);
            var bounds = bounds();
            tag.putDouble("minX", bounds.minX()); tag.putDouble("minY", bounds.minY()); tag.putDouble("minZ", bounds.minZ());
            tag.putDouble("maxX", bounds.maxX()); tag.putDouble("maxY", bounds.maxY()); tag.putDouble("maxZ", bounds.maxZ());
            return tag;
        }
        public static Extraction read(CompoundTag tag) {
            return new Extraction(tag.getString("id"), Location.read(tag), tag.getDouble("radius"),
                    tag.getInt("seconds"), tag.contains("fx") ? tag.getString("fx") : DEFAULT_FX);
        }
    }

    public CompoundTag write() {
        var tag = new CompoundTag();
        tag.putString("name", mapName); tag.putInt("nextSpawn", nextSpawn);
        if (lobby != null) tag.put("lobby", lobby.write());
        var spawnTags = new ListTag();
        spawns.forEach((id, location) -> { var item = location.write(); item.putString("id", id); spawnTags.add(item); });
        tag.put("spawns", spawnTags);
        var extractionTags = new ListTag(); extractions.values().forEach(zone -> extractionTags.add(zone.write()));
        tag.put("extractions", extractionTags);
        return tag;
    }

    public static RaidConfig read(CompoundTag tag) {
        var config = new RaidConfig();
        if (!tag.getString("name").isBlank()) config.mapName = tag.getString("name");
        config.nextSpawn = Math.max(0, tag.getInt("nextSpawn"));
        if (tag.contains("lobby", Tag.TAG_COMPOUND)) {
            try { config.lobby = Location.read(tag.getCompound("lobby")); } catch (IllegalArgumentException ignored) { }
        }
        var spawnTags = tag.getList("spawns", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(64, spawnTags.size()); i++) {
            var item = spawnTags.getCompound(i);
            try { if (!item.getString("id").isBlank()) config.spawns.put(item.getString("id"), Location.read(item)); }
            catch (IllegalArgumentException ignored) { }
        }
        var zoneTags = tag.getList("extractions", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(64, zoneTags.size()); i++) {
            try { var zone = Extraction.read(zoneTags.getCompound(i)); config.extractions.put(zone.id(), zone); }
            catch (IllegalArgumentException ignored) { }
        }
        return config;
    }
}
