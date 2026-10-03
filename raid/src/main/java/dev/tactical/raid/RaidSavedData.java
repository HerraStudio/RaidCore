package dev.tactical.raid;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

public final class RaidSavedData extends SavedData {
    public RaidConfig config = new RaidConfig();
    /** At most one current or unacknowledged result per player, including offline players. */
    public final Map<UUID, PlayerRaid> players = new HashMap<>();
    /** Acknowledging the UI does not erase the last server-side settlement record. */
    public final Map<UUID, PlayerRaid> lastResults = new HashMap<>();

    public static final class PlayerRaid {
        public final RaidSession session;
        public final RaidConfig.Location returnLocation;
        public boolean returnPending;
        public PlayerRaid(RaidSession session, RaidConfig.Location returnLocation) {
            this.session = session; this.returnLocation = returnLocation;
        }
    }

    public static RaidSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(RaidSavedData::new,
                RaidSavedData::load), "tactical_inventory_raids");
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("config", config.write());
        tag.put("players", writePlayers(players));
        tag.put("lastResults", writePlayers(lastResults));
        return tag;
    }

    private static ListTag writePlayers(Map<UUID, PlayerRaid> records) {
        var list = new ListTag();
        records.forEach((playerId, record) -> {
            var item = new CompoundTag(); var session = record.session;
            item.putUUID("player", playerId); item.putUUID("session", session.id);
            item.putString("map", session.map); item.putString("status", session.status().name());
            if (session.outcome() != null) item.putString("outcome", session.outcome().name());
            item.putString("zone", session.zone()); item.putInt("progress", session.extractionTicks());
            item.putInt("duration", session.requiredTicks()); item.putLong("elapsed", session.elapsedTicks());
            item.putInt("kills", session.kills()); item.putBoolean("returnPending", record.returnPending);
            if (record.returnLocation != null) item.put("return", record.returnLocation.write());
            list.add(item);
        });
        return list;
    }

    public static RaidSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new RaidSavedData(); data.config = RaidConfig.read(tag.getCompound("config"));
        loadPlayers(data, tag.getList("players", Tag.TAG_COMPOUND), data.players, true);
        loadPlayers(data, tag.getList("lastResults", Tag.TAG_COMPOUND), data.lastResults, false);
        return data;
    }

    private static void loadPlayers(RaidSavedData data, ListTag list, Map<UUID, PlayerRaid> records, boolean interruptActive) {
        for (int i = 0; i < list.size(); i++) {
            var item = list.getCompound(i);
            try {
                if (!item.hasUUID("player") || !item.hasUUID("session")) continue;
                var status = RaidSession.Status.valueOf(item.getString("status"));
                var outcome = item.getString("outcome").isBlank() ? null : RaidSession.Outcome.valueOf(item.getString("outcome"));
                var session = RaidSession.restore(item.getUUID("session"), item.getString("map"), status, outcome,
                        item.getString("zone"), item.getInt("progress"), item.getInt("duration"), item.getLong("elapsed"), item.getInt("kills"));
                var back = item.contains("return", Tag.TAG_COMPOUND) ? RaidConfig.Location.read(item.getCompound("return")) : data.config.lobby;
                var record = new PlayerRaid(session, back); record.returnPending = item.getBoolean("returnPending");
                // An unclean shutdown cannot carry an extraction countdown into a new server run.
                if (session.active() && interruptActive) {
                    session.finish(RaidSession.Outcome.ABORTED); record.returnPending = true; data.setDirty();
                }
                if (!interruptActive && session.active()) continue;
                records.put(item.getUUID("player"), record);
            } catch (IllegalArgumentException ignored) { }
        }
    }
}
