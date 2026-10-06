package dev.herrastudio.raidcore.integration;

import dev.herrastudio.raidcore.RaidCore;
import dev.tactical.raid.RaidConfig;
import dev.tactical.raid.RaidManager;
import dev.tactical.raid.RaidMatch;
import dev.tactical.raid.RaidSavedData;
import dev.tactical.raid.RaidSession;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;
import static dev.herrastudio.raidcore.integration.RaidCoreSmokeChecks.require;

/** Drives two real multiplayer clients through four rounds on an isolated dedicated server. */
@EventBusSubscriber(modid = RaidCore.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class RaidCoreSharedServerSmoke {
    private static int stage, stageTick, ticks;
    private static UUID alphaId, betaId, firstId, secondId, thirdId, fourthId;
    private static RaidMatch observed;
    private static long baselineElapsed;
    private static int baselineTick;

    @SubscribeEvent public static void started(ServerStartedEvent event) throws Exception {
        if (!Boolean.getBoolean("raidcore.sharedSmoke")) return;
        require(Path.of(".").toRealPath().equals(Path.of(System.getProperty("raidcore.smoke.directory")).toRealPath()),
                "Shared server harness directory is not isolated");
        var server = event.getServer();
        RaidCoreSmokeChecks.verify();
        require(server.getCommands().getDispatcher().getRoot().getChild("raid") != null, "Raid command root missing");
        var level = server.overworld();
        for (int x = -3; x <= 55; x++) for (int z = -3; z <= 3; z++)
            level.setBlockAndUpdate(new BlockPos(x, 64, z), Blocks.STONE.defaultBlockState());
        level.setDefaultSpawnPos(new BlockPos(0, 65, 0), 0);
        var data = RaidSavedData.get(server);
        data.players.clear(); data.lastResults.clear(); data.match = null;
        var config = new RaidConfig();
        config.lobby = point(0);
        config.spawns.put("alpha", point(16)); config.spawns.put("beta", point(32));
        config.extractions.put("exit", new RaidConfig.Extraction("exit", point(48), 1, 1, RaidConfig.DEFAULT_FX));
        data.config = config; data.setDirty();
        require(RaidManager.readinessError(server) == null, "Shared fixture configuration is not ready");
        log("SHARED_RAID_SERVER_READY: port=25589, two spawn slots, waiting minimum=2");
    }

    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!Boolean.getBoolean("raidcore.sharedSmoke") || !(event.getEntity() instanceof ServerPlayer player)) return;
        player.teleportTo(player.server.overworld(), 0, 65, 0, 0, 0);
        player.setHealth(player.getMaxHealth());
        var raidRoot = player.server.getCommands().getDispatcher().getRoot().getChild("raid");
        require(raidRoot.canUse(player.createCommandSourceStack()) && raidRoot.getChild("join").canUse(player.createCommandSourceStack()),
                "Non-operator cannot use Raid join");
        require(!raidRoot.getChild("start").canUse(player.createCommandSourceStack()), "Non-operator can use admin start");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean("raidcore.sharedSmoke")) return;
        var server = event.getServer();
        var data = RaidSavedData.get(server);
        ticks++;
        require(ticks < 4800, "Shared dedicated sequence timed out at stage " + stage);
        var alpha = named(server, "RaidAlpha");
        var beta = named(server, "RaidBeta");
        if (stage == 0 && alpha != null && beta != null && data.match != null && data.match.participants().size() == 2) {
            alphaId = alpha.getUUID(); betaId = beta.getUUID(); firstId = data.match.id;
            require(data.match.phase() == RaidMatch.Phase.WAITING && data.match.elapsedTicks() == 0,
                    "Registration did not share an unstarted round");
            require(RaidManager.current(alpha).session.id.equals(RaidManager.current(beta).session.id), "Two players have different round IDs");
            require(!RaidManager.acknowledge(beta, firstId), "Active/queued participant accepted a result acknowledgement");
            stage = 1; stageTick = ticks;
        } else if (stage == 1 && ticks - stageTick >= 30) {
            require(data.match.elapsedTicks() == 0, "Waiting advanced the shared clock");
            require(command(server, "raid start") == 2, "Common start command failed");
            observed = data.match;
            baselineElapsed = observed.elapsedTicks(); baselineTick = server.getTickCount();
            stage = 2; stageTick = ticks;
        } else if (stage == 2 && ticks - stageTick >= 40) {
            require(observed.elapsedTicks() - baselineElapsed == server.getTickCount() - baselineTick,
                    "Shared clock advanced once per participant rather than once per server tick");
            long before = observed.elapsedTicks(); RaidManager.tick(server);
            require(observed.elapsedTicks() == before, "Duplicate tick advanced the clock");
            var a = RaidManager.snapshot(alpha); var b = RaidManager.snapshot(beta);
            require(a.getLong("matchRemainingTicks") == b.getLong("matchRemainingTicks"), "Clients received different shared clock values");
            var alphaSpawn = RaidManager.current(alpha).spawn;
            var betaSpawn = RaidManager.current(beta).spawn;
            require(!RaidManager.current(alpha).spawnId.equals(RaidManager.current(beta).spawnId)
                    && alpha.distanceToSqr(alphaSpawn.x(), alphaSpawn.y(), alphaSpawn.z()) < 4
                    && beta.distanceToSqr(betaSpawn.x(), betaSpawn.y(), betaSpawn.z()) < 4,
                    "Distinct spawn allocation failed");
            var restored = RaidSavedData.load(data.save(new CompoundTag(), server.registryAccess()), server.registryAccess());
            require(restored.match == null && restored.players.get(alphaId).session.id.equals(firstId)
                    && restored.players.get(betaId).session.id.equals(firstId)
                    && restored.players.get(alphaId).session.outcome() == RaidSession.Outcome.ABORTED
                    && restored.players.get(betaId).returnPending, "Shared restart/NBT recovery did not preserve IDs and abort unfinished members");
            alpha.teleportTo(server.overworld(), 48, 65, 0, 0, 0);
            stage = 3; stageTick = ticks;
            log("SHARED_RAID_CLOCK_PASS: two real player connections, common ID/time, frozen waiting, distinct spawns, one tick and schema recovery");
        } else if (stage == 3 && ticks - stageTick >= 60 && RaidManager.current(alpha) == null) {
            require(data.match != null && data.match.id.equals(firstId) && data.match.phase() == RaidMatch.Phase.IN_RAID,
                    "First extraction ended the whole round");
            require(data.lastResults.get(alphaId).session.outcome() == RaidSession.Outcome.EXTRACTED, "First extraction result lost after acknowledgement");
            require(!RaidManager.join(alpha) && data.match.contains(alphaId) && RaidManager.current(alpha) == null,
                    "Acknowledged/extracted player rejoined the same active round");
            require(RaidManager.current(beta).session.inRaid() && !RaidManager.acknowledge(beta, firstId),
                    "Remaining player stopped participating or accepted another player's completion");
            beta.invulnerableTime = 0;
            require(beta.hurt(beta.damageSources().genericKill(), Float.MAX_VALUE), "Accepted death fixture failed");
            stage = 4; stageTick = ticks;
        } else if (stage == 4 && data.match == null && RaidManager.current(alpha) == null && RaidManager.current(beta) == null) {
            require(inLobby(alpha) && inLobby(beta), "Extraction/death did not return players to lobby");
            require(data.lastResults.get(betaId).session.outcome() == RaidSession.Outcome.DEAD, "Dead participant result was lost");
            require(command(server, "raid start @a") == 2, "Batch registration and shared restart failed");
            secondId = data.match.id;
            require(!secondId.equals(firstId) && data.match.remainingTicks() == 36_000, "New round inherited previous ID or clock");
            stage = 5; stageTick = ticks;
            log("SHARED_RAID_EXTRACTION_DEATH_PASS: real extraction and death/respawn/ack, remaining participant, reentry rejection, lobby return and new batch round");
        } else if (stage == 5 && ticks - stageTick >= 35) {
            data.config.matchDurationSeconds = 2;
            require(data.match.durationTicks() == 36_000, "Changing duration modified the current round");
            require(command(server, "raid stop") == 1, "Whole-round stop command failed");
            stage = 6; stageTick = ticks;
        } else if (stage == 6 && data.match == null && RaidManager.current(alpha) == null && RaidManager.current(beta) == null) {
            require(command(server, "raid start @a") == 2, "Shared short timeout round could not start");
            observed = data.match; thirdId = observed.id;
            require(!thirdId.equals(secondId) && observed.remainingTicks() == 40, "Future-only short duration was not captured");
            stage = 7; stageTick = ticks;
        } else if (stage == 7 && data.match == null && RaidManager.current(alpha) == null && RaidManager.current(beta) == null) {
            require(observed.phase() == RaidMatch.Phase.FINISHED && observed.elapsedTicks() == 40, "Shared timeout did not occur exactly at tick 40");
            var a = data.lastResults.get(alphaId).session; var b = data.lastResults.get(betaId).session;
            require(a.id.equals(thirdId) && b.id.equals(thirdId) && a.outcome() == RaidSession.Outcome.TIMED_OUT
                    && b.outcome() == RaidSession.Outcome.TIMED_OUT && a.elapsedTicks() == 40 && b.elapsedTicks() == 40,
                    "Timeout members did not retain the same outcome, ID and final duration");
            data.config.matchDurationSeconds = 1800;
            require(command(server, "raid start @a") == 2, "Fourth round could not start");
            fourthId = data.match.id; require(!fourthId.equals(thirdId), "Fourth round reused an ID");
            stage = 8; stageTick = ticks;
            log("SHARED_RAID_TIMEOUT_PASS: both real clients expired at tick 40, acknowledged once, no stale duration in next round");
        } else if (stage == 8 && beta == null) {
            var record = data.players.get(betaId);
            require(record != null && record.session.id.equals(fourthId) && record.session.outcome() == RaidSession.Outcome.ABORTED,
                    "Disconnected member was not committed as aborted");
            require(data.match != null && data.match.activePlayers() == 1 && RaidManager.current(alpha).session.inRaid(),
                    "Disconnect ended the remaining player's round");
            require(RaidManager.finish(alpha, RaidSession.Outcome.ABORTED, true), "Final member could not finish");
            stage = 9; stageTick = ticks;
        } else if (stage == 9 && data.match == null && data.players.get(alphaId) == null) {
            require(data.players.get(betaId).returnPending, "Offline player's pending lobby return was lost");
            log("SHARED_RAID_DEDICATED_PASS: four rounds, two real multiplayer clients, common clock, schema/legacy safety, extraction/death, stop, timeout, reentry and disconnect cleanup");
            stage = 10; stageTick = ticks;
        } else if (stage == 10 && ticks - stageTick >= 25) server.halt(false);
    }

    private static int command(MinecraftServer server, String command) throws Exception {
        return server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
    }
    private static ServerPlayer named(MinecraftServer server, String name) {
        return server.getPlayerList().getPlayers().stream().filter(p -> p.getGameProfile().getName().equals(name)).findFirst().orElse(null);
    }
    private static RaidConfig.Location point(double x) { return new RaidConfig.Location("minecraft:overworld", x, 65, 0, 0, 0); }
    private static boolean inLobby(ServerPlayer player) { return player.isAlive() && player.distanceToSqr(0, 65, 0) < 9; }
    private static void log(String text) { LoggerFactory.getLogger("SharedRaidSmoke").info(text); }
}
