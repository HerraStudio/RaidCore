package dev.tactical.raid;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.LoggerFactory;

public final class RaidSavedData extends SavedData {
    public static final int SCHEMA_VERSION = 2;
    public RaidConfig config = new RaidConfig();
    public RaidMatch match;
    public int lastTick = Integer.MIN_VALUE;
    /** Current registration or unacknowledged result per player, including offline players. */
    public final Map<UUID, PlayerRaid> players = new HashMap<>();
    public final Map<UUID, PlayerRaid> lastResults = new HashMap<>();

    public static final class PlayerRaid {
        public final RaidSession session;
        public final RaidConfig.Location returnLocation;
        public final String spawnId;
        public final RaidConfig.Location spawn;
        public boolean returnPending;
        public PlayerRaid(RaidSession session, RaidConfig.Location returnLocation) {
            this(session, returnLocation, "", null);
        }
        public PlayerRaid(RaidSession session, RaidConfig.Location returnLocation, String spawnId, RaidConfig.Location spawn) {
            this.session = session; this.returnLocation = returnLocation;
            this.spawnId = spawnId; this.spawn = spawn;
        }
    }

    public static RaidSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(RaidSavedData::new,
                RaidSavedData::load), "tactical_inventory_raids");
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schemaVersion", SCHEMA_VERSION);
        tag.put("config", config.write());
        tag.put("players", writePlayers(players));
        tag.put("lastResults", writePlayers(lastResults));
        if (match != null) tag.put("match", writeMatch(match));
        else tag.remove("match");
        return tag;
    }

    private static void writeSession(CompoundTag item, RaidSession session) {
        item.putUUID("session", session.id);
        item.putString("map", session.map); item.putString("status", session.status().name());
        if (session.outcome() != null) item.putString("outcome", session.outcome().name());
        item.putString("zone", session.zone()); item.putInt("progress", session.extractionTicks());
        item.putInt("duration", session.requiredTicks()); item.putLong("elapsed", session.elapsedTicks());
        item.putInt("matchDurationSeconds", session.matchDurationTicks() / 20);
        item.putInt("kills", session.kills());
    }

    private static ListTag writePlayers(Map<UUID, PlayerRaid> records) {
        var list = new ListTag();
        records.forEach((playerId, record) -> {
            var item = new CompoundTag();
            item.putUUID("player", playerId);
            writeSession(item, record.session);
            item.putBoolean("returnPending", record.returnPending);
            if (record.returnLocation != null) item.put("return", record.returnLocation.write());
            if (record.spawn != null) item.put("spawn", record.spawn.write());
            item.putString("spawnId", record.spawnId);
            list.add(item);
        });
        return list;
    }

    private static CompoundTag writeMatch(RaidMatch match) {
        var tag = new CompoundTag();
        tag.putUUID("id", match.id); tag.putString("map", match.map); tag.putString("dimension", match.dimension);
        tag.putString("phase", match.phase().name()); tag.putInt("seconds", match.durationSeconds());
        tag.putLong("elapsed", match.elapsedTicks()); tag.putLong("startedAt", match.startedAt());
        tag.putLong("finishedAt", match.finishedAt());
        var members = new ListTag();
        match.participants().forEach((player, participant) -> {
            var member = new CompoundTag(); member.putUUID("player", player);
            member.putString("spawnId", participant.spawnId());
            writeSession(member, participant.session());
            members.add(member);
        });
        tag.put("members", members);
        return tag;
    }

    private static RaidSession readSession(CompoundTag item) {
        if (!item.hasUUID("session")) throw new IllegalArgumentException("Missing Raid session UUID");
        var status = RaidSession.Status.valueOf(item.getString("status"));
        var outcome = item.getString("outcome").isBlank() ? null : RaidSession.Outcome.valueOf(item.getString("outcome"));
        return RaidSession.restore(item.getUUID("session"), item.getString("map"), status, outcome,
                item.getString("zone"), item.getInt("progress"), item.getInt("duration"), item.getLong("elapsed"),
                item.getInt("kills"), item.getInt("matchDurationSeconds"));
    }

    public static RaidSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new RaidSavedData();
        data.config = RaidConfig.read(tag.getCompound("config"));
        loadPlayers(data, tag.getList("players", Tag.TAG_COMPOUND), data.players, false);
        loadPlayers(data, tag.getList("lastResults", Tag.TAG_COMPOUND), data.lastResults, true);
        if (tag.contains("match", Tag.TAG_COMPOUND)) {
            try { data.match = readMatch(data, tag.getCompound("match")); }
            catch (IllegalArgumentException error) {
                LoggerFactory.getLogger("Herra/Raid").warn("Invalid shared Raid data; recovering player records", error);
            }
        }
        // Existing restart policy is retained: do not resume an in-flight round or extraction.
        boolean wasWaiting = data.match == null || data.match.phase() == RaidMatch.Phase.WAITING;
        var iterator = data.players.entrySet().iterator();
        while (iterator.hasNext()) {
            var record = iterator.next().getValue();
            if (record.session.status() == RaidSession.Status.WAITING && wasWaiting) {
                iterator.remove(); data.setDirty();
            } else if (record.session.active()) {
                record.session.finish(RaidSession.Outcome.ABORTED);
                record.returnPending = true; data.setDirty();
            }
        }
        if (data.match != null) {
            data.match.abort();
            LoggerFactory.getLogger("Herra/Raid").info(
                    "Session recovered and cleaned: raidId={} phase={} reason=server restart", data.match.id, data.match.phase());
            data.match = null; data.setDirty();
        }
        return data;
    }

    private static RaidMatch readMatch(RaidSavedData data, CompoundTag tag) {
        if (!tag.hasUUID("id")) throw new IllegalArgumentException("Missing shared Raid UUID");
        var id = tag.getUUID("id");
        var members = new LinkedHashMap<UUID, RaidMatch.Participant>();
        var list = tag.getList("members", Tag.TAG_COMPOUND);
        if (list.size() > 64) throw new IllegalArgumentException("Too many shared Raid members");
        for (int i = 0; i < list.size(); i++) {
            var member = list.getCompound(i);
            if (!member.hasUUID("player")) throw new IllegalArgumentException("Missing member UUID");
            UUID player = member.getUUID("player");
            var record = data.players.get(player);
            if (record == null || !record.session.id.equals(id)) record = data.lastResults.get(player);
            var session = record != null && record.session.id.equals(id) ? record.session : readSession(member);
            if (members.put(player, new RaidMatch.Participant(member.getString("spawnId"), session)) != null)
                throw new IllegalArgumentException("Duplicate shared Raid member");
        }
        return RaidMatch.restore(id, tag.getString("map"), tag.getString("dimension"), tag.getInt("seconds"),
                RaidMatch.Phase.valueOf(tag.getString("phase")), tag.getLong("elapsed"),
                tag.getLong("startedAt"), tag.getLong("finishedAt"), members);
    }

    private static void loadPlayers(RaidSavedData data, ListTag list, Map<UUID, PlayerRaid> records, boolean resultsOnly) {
        for (int i = 0; i < list.size(); i++) {
            var item = list.getCompound(i);
            try {
                if (!item.hasUUID("player")) continue;
                var session = readSession(item);
                if (resultsOnly && session.active()) continue;
                var back = item.contains("return", Tag.TAG_COMPOUND) ? RaidConfig.Location.read(item.getCompound("return")) : data.config.lobby;
                var spawn = item.contains("spawn", Tag.TAG_COMPOUND) ? RaidConfig.Location.read(item.getCompound("spawn")) : null;
                var record = new PlayerRaid(session, back, item.getString("spawnId"), spawn);
                record.returnPending = item.getBoolean("returnPending");
                records.put(item.getUUID("player"), record);
            } catch (IllegalArgumentException error) {
                LoggerFactory.getLogger("Herra/Raid").warn("Skipping invalid player Raid record at index {}", i, error);
            }
        }
    }
}
