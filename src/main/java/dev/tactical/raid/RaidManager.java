package dev.tactical.raid;

import java.util.ArrayList;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class RaidManager {
    public static BiConsumer<ServerPlayer, CompoundTag> snapshotSender = (player, tag) -> { };
    public static BiConsumer<ServerPlayer, CompoundTag> zonesSender = (player, tag) -> { };
    private RaidManager() { }

    public static RaidSavedData.PlayerRaid current(ServerPlayer player) {
        return RaidSavedData.get(player.server).players.get(player.getUUID());
    }

    public static RaidConfig.Location location(ServerPlayer player) {
        return new RaidConfig.Location(player.level().dimension().location().toString(), player.getX(), player.getY(),
                player.getZ(), player.getYRot(), player.getXRot());
    }

    public static ServerLevel level(MinecraftServer server, String dimension) {
        var id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    public static String readinessError(MinecraftServer server) {
        var config = RaidSavedData.get(server).config;
        if (config.lobby == null || level(server, config.lobby.dimension()) == null) return "请先配置有效大厅：/raid lobby";
        if (config.spawns.isEmpty()) return "请先配置出生点：/raid spawn add <名称>";
        if (config.extractions.isEmpty()) return "请先配置撤离点：/raid extract add <名称> <半径>";
        for (var spawn : config.spawns.values()) {
            if (level(server, spawn.dimension()) == null) return "出生点维度不存在：" + spawn.dimension();
            if (config.extractions.values().stream().noneMatch(zone -> zone.location().dimension().equals(spawn.dimension())
                    && level(server, zone.location().dimension()) != null)) return "出生点所在维度缺少撤离点：" + spawn.dimension();
        }
        return null;
    }

    public static boolean join(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) {
            player.sendSystemMessage(Component.literal("当前状态无法加入对局。")); return false;
        }
        var data = RaidSavedData.get(player.server);
        var previous = data.players.get(player.getUUID());
        if (previous != null && previous.session.active()) {
            player.sendSystemMessage(Component.literal("你已经在对局中。")); sendSnapshot(player); return false;
        }
        if (previous != null && previous.returnPending && !returnToLobby(player, previous)) {
            player.sendSystemMessage(Component.literal("尚未完成返回大厅，无法再次入场。")); return false;
        }
        String error = readinessError(player.server);
        if (error != null) { player.sendSystemMessage(Component.literal(error)); return false; }
        var spawns = new ArrayList<>(data.config.spawns.values());
        var spawn = spawns.get(Math.floorMod(data.config.nextSpawn, spawns.size()));
        data.config.nextSpawn = Math.floorMod(data.config.nextSpawn + 1, spawns.size());
        var record = new RaidSavedData.PlayerRaid(new RaidSession(UUID.randomUUID(), data.config.mapName), data.config.lobby);
        if (previous != null) data.lastResults.put(player.getUUID(), previous);
        data.players.put(player.getUUID(), record); data.setDirty();
        player.closeContainer();
        teleport(player, spawn);
        player.sendSystemMessage(Component.literal("已进入对局：" + data.config.mapName));
        sendSnapshot(player); sendZones(player);
        return true;
    }

    public static void tick(MinecraftServer server) {
        var data = RaidSavedData.get(server);
        boolean dirty = false;
        for (var player : server.getPlayerList().getPlayers()) {
            var record = data.players.get(player.getUUID());
            if (record == null) continue;
            var session = record.session;
            if (!session.active()) {
                if (record.returnPending && player.isAlive() && returnToLobby(player, record)) {
                    dirty = true; sendSnapshot(player); sendZones(player);
                }
                continue;
            }
            if (player.isSpectator()) {
                finish(player, RaidSession.Outcome.ABORTED, true);
                continue;
            }
            if (!player.isAlive()) { finish(player, RaidSession.Outcome.DEAD, false); continue; }
            var before = session.status(); var beforeZone = session.zone();
            RaidConfig.Extraction selected = null;
            if (!player.isSpectator()) {
                for (var zone : data.config.extractions.values()) {
                    if (zone.location().dimension().equals(player.level().dimension().location().toString())
                            && zone.bounds().contains(player.getX(), player.getY(), player.getZ())) {
                        selected = zone; break;
                    }
                }
            }
            boolean extracted = session.tick(selected == null ? null : selected.id(), selected == null ? 0 : selected.seconds() * 20);
            dirty = true;
            if (extracted) {
                record.returnPending = true;
                returnToLobby(player, record);
                sendSnapshot(player); sendZones(player);
            } else if (before != session.status() || !beforeZone.equals(session.zone()) || server.getTickCount() % 5 == 0) {
                sendSnapshot(player);
            }
        }
        if (dirty) data.setDirty();
    }

    /** Death does not teleport or modify equipment; returning occurs after the normal respawn. */
    public static boolean finish(ServerPlayer player, RaidSession.Outcome outcome, boolean returnNow) {
        var record = current(player);
        if (record == null) return false;
        if (!record.session.finish(outcome)) {
            // A second death before acknowledging a result must still respawn in the lobby.
            // The previously committed result remains unchanged.
            if (outcome == RaidSession.Outcome.DEAD) {
                record.returnPending = true;
                RaidSavedData.get(player.server).setDirty();
                sendSnapshot(player);
            }
            return false;
        }
        record.returnPending = true;
        RaidSavedData.get(player.server).setDirty();
        if (returnNow && player.isAlive()) returnToLobby(player, record);
        sendSnapshot(player);
        return true;
    }

    public static boolean acknowledge(ServerPlayer player, UUID sessionId) {
        var data = RaidSavedData.get(player.server); var record = data.players.get(player.getUUID());
        if (record == null || !record.session.id.equals(sessionId) || record.session.active()
                || record.returnPending || !player.isAlive()) return false;
        data.lastResults.put(player.getUUID(), record);
        data.players.remove(player.getUUID()); data.setDirty(); sendSnapshot(player);
        return true;
    }

    public static void login(ServerPlayer player) {
        var record = current(player);
        if (record != null && record.session.active()) {
            record.session.finish(RaidSession.Outcome.ABORTED); record.returnPending = true;
            RaidSavedData.get(player.server).setDirty();
        }
        if (record != null && record.returnPending && player.isAlive()) returnToLobby(player, record);
        sendSnapshot(player); sendZones(player);
    }

    public static void logout(ServerPlayer player) {
        // Commit before the player entity disappears. Rejoining cannot resume a partial countdown.
        var record = current(player);
        if (record != null && record.session.finish(RaidSession.Outcome.ABORTED)) {
            record.returnPending = true; RaidSavedData.get(player.server).setDirty();
        }
    }

    public static void respawn(ServerPlayer player) {
        var record = current(player);
        if (record != null && (record.returnPending || !record.session.active()) && player.isAlive())
            returnToLobby(player, record);
        sendSnapshot(player); sendZones(player);
    }

    public static void stop(MinecraftServer server) {
        var data = RaidSavedData.get(server);
        for (var record : data.players.values()) {
            if (record.session.finish(RaidSession.Outcome.ABORTED)) { record.returnPending = true; data.setDirty(); }
        }
    }

    public static void countKill(ServerPlayer player) {
        var record = current(player);
        if (record != null && record.session.active()) { record.session.countKill(); RaidSavedData.get(player.server).setDirty(); }
    }

    public static boolean returnToLobby(ServerPlayer player, RaidSavedData.PlayerRaid record) {
        if (!player.isAlive()) return false;
        var back = record.returnLocation;
        if (back == null || level(player.server, back.dimension()) == null) {
            var overworld = player.server.overworld(); var spawn = overworld.getSharedSpawnPos();
            back = new RaidConfig.Location(Level.OVERWORLD.location().toString(), spawn.getX() + .5,
                    spawn.getY(), spawn.getZ() + .5, overworld.getSharedSpawnAngle(), 0);
        }
        player.closeContainer();
        if (!teleport(player, back)) return false;
        record.returnPending = false; RaidSavedData.get(player.server).setDirty();
        return true;
    }

    private static boolean teleport(ServerPlayer player, RaidConfig.Location location) {
        var target = level(player.server, location.dimension());
        if (target == null) return false;
        player.teleportTo(target, location.x(), location.y(), location.z(), location.yaw(), location.pitch());
        player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0;
        return true;
    }

    public static CompoundTag snapshot(ServerPlayer player) {
        var tag = new CompoundTag(); var record = current(player);
        if (record == null) { tag.putString("status", "IDLE"); return tag; }
        var session = record.session;
        tag.putUUID("session", session.id); tag.putString("status", session.status().name());
        tag.putString("map", session.map); tag.putString("zone", session.zone());
        tag.putString("outcome", session.outcome() == null ? "" : session.outcome().name());
        tag.putInt("remainingTicks", session.remainingTicks()); tag.putInt("totalTicks", session.requiredTicks());
        tag.putLong("elapsedTicks", session.elapsedTicks()); tag.putInt("kills", session.kills());
        tag.putBoolean("returnPending", record.returnPending);
        return tag;
    }

    public static CompoundTag zones(ServerPlayer player) {
        var tag = new CompoundTag(); var list = new ListTag();
        for (var zone : RaidSavedData.get(player.server).config.extractions.values()) {
            if (zone.location().dimension().equals(player.level().dimension().location().toString())) list.add(zone.write());
        }
        tag.put("zones", list); return tag;
    }

    public static void sendSnapshot(ServerPlayer player) { snapshotSender.accept(player, snapshot(player)); }
    public static void sendZones(ServerPlayer player) { zonesSender.accept(player, zones(player)); }
    public static void configurationChanged(MinecraftServer server) {
        var data = RaidSavedData.get(server); data.setDirty();
        for (var player : server.getPlayerList().getPlayers()) { sendZones(player); sendSnapshot(player); }
    }
}
