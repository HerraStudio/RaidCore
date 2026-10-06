package dev.tactical.raid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RaidManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Herra/Raid");
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
        if (config.spawns.size() < config.minimumPlayers)
            return "出生点不足，至少需要 " + config.minimumPlayers + " 个：/raid spawn add <名称>";
        if (config.extractions.isEmpty()) return "请先配置撤离点：/raid extract add <名称> <半径>";
        var spawns = new ArrayList<>(config.spawns.values());
        String dimension = spawns.getFirst().dimension();
        if (level(server, dimension) == null) return "出生点维度不存在：" + dimension;
        for (int i = 0; i < spawns.size(); i++) {
            if (!spawns.get(i).dimension().equals(dimension)) return "当前仅支持单地图对局，出生点必须在同一维度。";
            for (int j = 0; j < i; j++)
                if (overlapping(spawns.get(i), spawns.get(j))) return "出生点重叠，请为玩家配置不同的位置。";
        }
        if (config.extractions.values().stream().noneMatch(zone -> zone.location().dimension().equals(dimension)))
            return "出生点所在维度缺少撤离点：" + dimension;
        return null;
    }

    private static boolean overlapping(RaidConfig.Location a, RaidConfig.Location b) {
        double dx = a.x() - b.x(), dz = a.z() - b.z();
        return a.dimension().equals(b.dimension()) && dx * dx + dz * dz < 1 && Math.abs(a.y() - b.y()) < 2;
    }

    /** Registers in the waiting roster. Teleport and round time begin at the common start. */
    public static boolean join(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) return reject(player, "当前状态无法加入对局。");
        var data = RaidSavedData.get(player.server);
        var match = data.match;
        if (match != null && match.contains(player.getUUID())) {
            sendSnapshot(player);
            return reject(player, match.phase() == RaidMatch.Phase.WAITING
                    ? "你已经在本局等待名单中。" : "你已参与过本局，请等待整局结束后再加入下一局。");
        }
        if (match != null && match.phase() != RaidMatch.Phase.WAITING)
            return reject(player, "当前对局正在进行或结束中，请等待下一局。");
        var previous = data.players.get(player.getUUID());
        if (previous != null && previous.session.active()) return reject(player, "你已经登记在对局中。");
        if (previous != null && previous.returnPending && !returnToLobby(player, previous))
            return reject(player, "尚未完成返回大厅，无法再次加入。");
        String error = readinessError(player.server);
        if (error != null) return reject(player, error);
        var config = data.config;
        String dimension = config.spawns.values().iterator().next().dimension();
        if (match != null && !match.dimension.equals(dimension))
            return reject(player, "等待中的对局已绑定另一地图，请先 /raid stop 再修改地图。");
        String spawnId = null;
        var ids = new ArrayList<>(config.spawns.keySet());
        for (int offset = 0; offset < ids.size(); offset++) {
            String candidate = ids.get(Math.floorMod(config.nextSpawn + offset, ids.size()));
            if (match == null || match.participants().values().stream().noneMatch(p -> p.spawnId().equals(candidate))) {
                spawnId = candidate;
                config.nextSpawn = Math.floorMod(config.nextSpawn + offset + 1, ids.size());
                break;
            }
        }
        if (spawnId == null) return reject(player, "本局出生点已全部分配，等待下一局或增加出生点。");
        if (match == null) {
            match = new RaidMatch(UUID.randomUUID(), config.mapName, dimension, config.matchDurationSeconds);
            data.match = match;
            log(match, null, "Session created", "waiting");
        }
        var session = match.join(player.getUUID(), spawnId);
        if (session == null) return reject(player, "无法加入当前等待名单。");
        if (previous != null) data.lastResults.put(player.getUUID(), previous);
        data.players.put(player.getUUID(), new RaidSavedData.PlayerRaid(session, config.lobby, spawnId, config.spawns.get(spawnId)));
        data.setDirty();
        player.closeContainer();
        log(match, player.getUUID(), "Player joined", spawnId);
        player.sendSystemMessage(Component.literal("已加入 " + match.map + " 等待名单（" + match.participants().size()
                + " 人）；管理员 /raid start 统一开局。"));
        broadcastSnapshots(player.server);
        sendZones(player);
        return true;
    }

    public static String startError(MinecraftServer server) {
        var data = RaidSavedData.get(server);
        var match = data.match;
        if (match == null) return "没有等待中的对局，请先 /raid join 或 /raid start <玩家选择器>。";
        if (match.phase() != RaidMatch.Phase.WAITING) return "当前对局不能重复开局。";
        if (match.participants().size() < data.config.minimumPlayers)
            return "至少需要 " + data.config.minimumPlayers + " 人，当前等待 " + match.participants().size() + " 人。";
        var assigned = new ArrayList<RaidConfig.Location>();
        for (var id : match.participants().keySet()) {
            var player = server.getPlayerList().getPlayer(id);
            var record = data.players.get(id);
            if (player == null || !player.isAlive() || player.isSpectator()) return "等待成员不在线或无法参与：" + id;
            if (record == null || record.session != match.participants().get(id).session()
                    || record.session.status() != RaidSession.Status.WAITING
                    || record.spawn == null || !record.spawn.dimension().equals(match.dimension)
                    || level(server, record.spawn.dimension()) == null) return "成员出生点或对局记录无效：" + id;
            if (assigned.stream().anyMatch(spawn -> overlapping(spawn, record.spawn))) return "已分配的出生点重叠，请重新登记。";
            assigned.add(record.spawn);
        }
        if (level(server, match.dimension) == null) return "对局地图维度未加载：" + match.dimension;
        if (data.config.extractions.values().stream().noneMatch(zone -> zone.location().dimension().equals(match.dimension)))
            return "对局地图缺少撤离点。";
        return null;
    }

    public static boolean start(MinecraftServer server) {
        var data = RaidSavedData.get(server);
        var match = data.match;
        String error = startError(server);
        if (error != null) return false;
        if (!match.beginStart(data.config.minimumPlayers)) return false;
        log(match, null, "Raid starting", "roster locked");
        var originalLocations = new LinkedHashMap<ServerPlayer, RaidConfig.Location>();
        try {
            for (var id : match.participants().keySet()) {
                var player = server.getPlayerList().getPlayer(id);
                originalLocations.put(player, location(player));
                player.closeContainer();
                if (!teleport(player, data.players.get(id).spawn)) throw new IllegalStateException("Spawn teleport failed: " + id);
            }
            if (!match.completeStart()) throw new IllegalStateException("Shared start transition failed");
        } catch (RuntimeException failure) {
            originalLocations.forEach((player, back) -> teleport(player, back));
            match.cancelStart();
            data.setDirty();
            LOGGER.error("Raid start rolled back: raidId={} phase={}", match.id, match.phase(), failure);
            broadcastSnapshots(server);
            return false;
        }
        data.setDirty();
        log(match, null, "Raid started", "players=" + match.participants().size());
        for (var id : match.participants().keySet()) {
            var player = server.getPlayerList().getPlayer(id);
            player.sendSystemMessage(Component.literal("对局已统一开始：" + match.map));
            sendZones(player);
        }
        broadcastSnapshots(server);
        return true;
    }

    public static boolean leave(ServerPlayer player) {
        var data = RaidSavedData.get(player.server);
        var match = data.match;
        if (match != null && match.phase() == RaidMatch.Phase.WAITING && match.leaveWaiting(player.getUUID())) {
            var record = data.players.get(player.getUUID());
            if (record != null && record.session.id.equals(match.id)) data.players.remove(player.getUUID());
            log(match, player.getUUID(), "Player left waiting", "cancelled");
            if (match.participants().isEmpty()) {
                log(match, null, "Session cleaned", "empty waiting roster");
                data.match = null;
            }
            data.setDirty();
            broadcastSnapshots(player.server);
            return true;
        }
        return finish(player, RaidSession.Outcome.ABORTED, true);
    }

    public static void tick(MinecraftServer server) {
        var data = RaidSavedData.get(server);
        // Also protects debug/native callers from advancing a round twice in the same tick.
        if (data.lastTick == server.getTickCount()) return;
        data.lastTick = server.getTickCount();
        retryReturns(server);
        var match = data.match;
        if (match == null) return;
        if (match.phase() == RaidMatch.Phase.WAITING) {
            for (var id : new ArrayList<>(match.participants().keySet())) {
                var player = server.getPlayerList().getPlayer(id);
                if (player != null && (!player.isAlive() || player.isSpectator())) leave(player);
            }
            return;
        }
        if (match.phase() == RaidMatch.Phase.IN_RAID) {
            var pendingTimeout = new ArrayList<UUID>();
            for (var entry : match.participants().entrySet())
                if (entry.getValue().session().inRaid()) pendingTimeout.add(entry.getKey());
            if (match.tick()) {
                for (var id : pendingTimeout) settled(server, match, id, "timeout");
            } else {
                for (var entry : match.participants().entrySet()) {
                    var id = entry.getKey();
                    var session = entry.getValue().session();
                    if (!session.inRaid()) continue;
                    var player = server.getPlayerList().getPlayer(id);
                    if (player == null) {
                        if (match.finishParticipant(id, RaidSession.Outcome.ABORTED)) settled(server, match, id, "disconnected");
                        continue;
                    }
                    if (!player.isAlive()) { finish(player, RaidSession.Outcome.DEAD, false); continue; }
                    if (player.isSpectator() || !player.level().dimension().location().toString().equals(match.dimension)) {
                        finish(player, RaidSession.Outcome.ABORTED, true);
                        continue;
                    }
                    var before = session.status();
                    String beforeZone = session.zone();
                    RaidConfig.Extraction selected = null;
                    for (var zone : data.config.extractions.values()) {
                        if (zone.location().dimension().equals(match.dimension)
                                && zone.bounds().contains(player.getX(), player.getY(), player.getZ())) {
                            selected = zone; break;
                        }
                    }
                    boolean extracted = session.tickInMatch(selected == null ? null : selected.id(),
                            selected == null ? 0 : selected.seconds() * 20, match.elapsedTicks());
                    if (extracted) {
                        match.reconcileFinished();
                        settled(server, match, id, "extracted");
                    } else if (before != session.status() || !beforeZone.equals(session.zone()) || server.getTickCount() % 5 == 0) {
                        sendSnapshot(player);
                    }
                }
            }
            data.setDirty();
        }
        cleanupFinished(server);
    }

    private static void retryReturns(MinecraftServer server) {
        var data = RaidSavedData.get(server);
        for (var player : server.getPlayerList().getPlayers()) {
            var record = data.players.get(player.getUUID());
            if (record != null && !record.session.active() && record.returnPending && player.isAlive()
                    && returnToLobby(player, record)) {
                sendSnapshot(player); sendZones(player);
            }
        }
    }

    private static void settled(MinecraftServer server, RaidMatch match, UUID playerId, String reason) {
        var data = RaidSavedData.get(server);
        var record = data.players.get(playerId);
        if (record == null || !record.session.id.equals(match.id)) return;
        record.returnPending = true;
        var player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            if (player.isAlive()) returnToLobby(player, record);
            sendZones(player);
        }
        data.setDirty();
        log(match, playerId, "Player settled", record.session.outcome() + ":" + reason);
        broadcastSnapshots(server);
    }

    private static void cleanupFinished(MinecraftServer server) {
        var data = RaidSavedData.get(server);
        var match = data.match;
        if (match == null || match.phase() != RaidMatch.Phase.FINISHED) return;
        for (var id : match.participants().keySet()) {
            var record = data.players.get(id);
            if (server.getPlayerList().getPlayer(id) != null && record != null
                    && record.session.id.equals(match.id) && record.returnPending) return;
        }
        log(match, null, "Session finished", "elapsedTicks=" + match.elapsedTicks());
        data.match = null;
        data.setDirty();
        log(match, null, "Session cleaned", "results retained; round slot released");
        broadcastSnapshots(server);
    }

    /** Death commits once; normal respawn performs the return to lobby. */
    public static boolean finish(ServerPlayer player, RaidSession.Outcome outcome, boolean returnNow) {
        var data = RaidSavedData.get(player.server);
        var record = current(player);
        if (record == null) return false;
        if (record.session.status() == RaidSession.Status.WAITING) return leave(player);
        var match = data.match;
        boolean shared = match != null && match.id.equals(record.session.id) && match.contains(player.getUUID());
        boolean changed = shared ? match.finishParticipant(player.getUUID(), outcome) : record.session.finish(outcome);
        if (!changed) {
            if (outcome == RaidSession.Outcome.DEAD) {
                record.returnPending = true;
                data.setDirty(); sendSnapshot(player);
            }
            return false;
        }
        record.returnPending = true;
        data.setDirty();
        if (returnNow && player.isAlive()) returnToLobby(player, record);
        if (shared) log(match, player.getUUID(), "Player settled", outcome.name());
        broadcastSnapshots(player.server);
        return true;
    }

    public static boolean acknowledge(ServerPlayer player, UUID sessionId) {
        var data = RaidSavedData.get(player.server);
        var record = data.players.get(player.getUUID());
        if (record == null || !record.session.id.equals(sessionId) || record.session.active()
                || record.returnPending || !player.isAlive()) return false;
        data.lastResults.put(player.getUUID(), record);
        data.players.remove(player.getUUID());
        data.setDirty();
        // The shared roster retains the terminal participant until the whole round ends.
        sendSnapshot(player);
        return true;
    }

    public static void login(ServerPlayer player) {
        var record = current(player);
        if (record != null && record.session.status() == RaidSession.Status.WAITING) leave(player);
        else if (record != null && record.session.inRaid()) finish(player, RaidSession.Outcome.ABORTED, true);
        record = current(player);
        if (record != null && record.returnPending && player.isAlive()) returnToLobby(player, record);
        sendSnapshot(player); sendZones(player);
    }

    public static void logout(ServerPlayer player) {
        var data = RaidSavedData.get(player.server);
        var record = current(player);
        if (record == null) return;
        if (record.session.status() == RaidSession.Status.WAITING) { leave(player); return; }
        var match = data.match;
        boolean shared = match != null && match.id.equals(record.session.id) && match.contains(player.getUUID());
        boolean changed = shared ? match.finishParticipant(player.getUUID(), RaidSession.Outcome.ABORTED)
                : record.session.finish(RaidSession.Outcome.ABORTED);
        if (changed) {
            record.returnPending = true;
            data.setDirty();
            if (shared) log(match, player.getUUID(), "Player settled", "ABORTED:logout");
            broadcastSnapshots(player.server);
        }
    }

    public static void respawn(ServerPlayer player) {
        var record = current(player);
        if (record != null && record.returnPending && player.isAlive()) returnToLobby(player, record);
        sendSnapshot(player); sendZones(player);
        cleanupFinished(player.server);
    }

    public static boolean abortMatch(MinecraftServer server) {
        var data = RaidSavedData.get(server);
        var match = data.match;
        if (match == null) return false;
        if (match.phase() == RaidMatch.Phase.WAITING) {
            for (var id : match.participants().keySet()) {
                var record = data.players.get(id);
                if (record != null && record.session.id.equals(match.id)) data.players.remove(id);
            }
            data.match = null;
            log(match, null, "Session cleaned", "waiting cancelled");
        } else {
            var unfinished = match.participants().entrySet().stream()
                    .filter(e -> e.getValue().session().active()).map(java.util.Map.Entry::getKey).toList();
            match.abort();
            for (var id : unfinished) settled(server, match, id, "admin stop");
            cleanupFinished(server);
        }
        data.setDirty(); broadcastSnapshots(server);
        return true;
    }

    public static void stop(MinecraftServer server) {
        abortMatch(server);
        var data = RaidSavedData.get(server);
        for (var record : data.players.values()) {
            if (record.session.finish(RaidSession.Outcome.ABORTED)) {
                record.returnPending = true; data.setDirty();
            }
        }
    }

    public static void countKill(ServerPlayer player) {
        var record = current(player);
        var match = RaidSavedData.get(player.server).match;
        if (record != null && record.session.inRaid() && match != null && match.phase() == RaidMatch.Phase.IN_RAID
                && match.id.equals(record.session.id)) {
            record.session.countKill(); RaidSavedData.get(player.server).setDirty();
        }
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
        record.returnPending = false;
        RaidSavedData.get(player.server).setDirty();
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
        var tag = new CompoundTag();
        var data = RaidSavedData.get(player.server);
        var match = data.match;
        tag.putString("matchPhase", match == null ? "NONE" : match.phase().name());
        tag.putInt("minimumPlayers", data.config.minimumPlayers);
        var members = new ListTag();
        if (match != null) {
            tag.putUUID("match", match.id);
            tag.putString("matchDimension", match.dimension);
            tag.putInt("matchPlayers", match.participants().size());
            tag.putInt("matchActivePlayers", match.activePlayers());
            tag.putLong("matchElapsedTicks", match.elapsedTicks());
            for (var entry : match.participants().entrySet()) {
                var member = new CompoundTag();
                member.putUUID("player", entry.getKey());
                var online = player.server.getPlayerList().getPlayer(entry.getKey());
                member.putString("name", online == null ? entry.getKey().toString() : online.getGameProfile().getName());
                member.putString("status", entry.getValue().session().status().name());
                member.putString("outcome", entry.getValue().session().outcome() == null ? "" : entry.getValue().session().outcome().name());
                member.putString("spawn", entry.getValue().spawnId());
                members.add(member);
            }
        }
        tag.put("members", members);
        var record = current(player);
        if (record == null) { tag.putString("status", "IDLE"); return tag; }
        var session = record.session;
        tag.putUUID("session", session.id); tag.putString("status", session.status().name());
        tag.putString("map", session.map); tag.putString("zone", session.zone());
        tag.putString("outcome", session.outcome() == null ? "" : session.outcome().name());
        tag.putInt("remainingTicks", session.remainingTicks()); tag.putInt("totalTicks", session.requiredTicks());
        boolean shared = match != null && match.id.equals(session.id);
        tag.putLong("matchRemainingTicks", shared ? match.remainingTicks() : session.remainingMatchTicks());
        tag.putLong("matchTotalTicks", shared ? match.durationTicks() : session.matchDurationTicks());
        tag.putLong("elapsedTicks", session.elapsedTicks()); tag.putInt("kills", session.kills());
        tag.putBoolean("returnPending", record.returnPending);
        return tag;
    }

    public static CompoundTag zones(ServerPlayer player) {
        var tag = new CompoundTag(); var list = new ListTag();
        for (var zone : RaidSavedData.get(player.server).config.extractions.values())
            if (zone.location().dimension().equals(player.level().dimension().location().toString())) list.add(zone.write());
        tag.put("zones", list); return tag;
    }

    public static void sendSnapshot(ServerPlayer player) { snapshotSender.accept(player, snapshot(player)); }
    public static void sendZones(ServerPlayer player) { zonesSender.accept(player, zones(player)); }
    public static void broadcastSnapshots(MinecraftServer server) {
        for (var player : server.getPlayerList().getPlayers()) sendSnapshot(player);
    }
    public static void configurationChanged(MinecraftServer server) {
        RaidSavedData.get(server).setDirty();
        for (var player : server.getPlayerList().getPlayers()) { sendZones(player); sendSnapshot(player); }
    }

    private static boolean reject(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
        return false;
    }
    private static void log(RaidMatch match, UUID player, String event, String reason) {
        LOGGER.info("{}: raidId={} playerUuid={} phase={} reason={}", event, match.id, player, match.phase(), reason);
    }
}
