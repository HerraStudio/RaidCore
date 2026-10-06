package dev.herrastudio.raidcore.integration;

import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import dev.draginventory.FastSwitchConfig;
import dev.draginventory.PlayerStamina;
import dev.draginventory.client.compass.CompassConfig;
import dev.draginventory.client.map.FactoryMapKeyBindings;
import dev.draginventory.client.map.FactoryMapScreen;
import dev.draginventory.client.map.MapConfig;
import dev.draginventory.client.map.MapMatchTimer;
import dev.draginventory.client.wheel.WheelKeyBindings;
import dev.herrastudio.raidcore.RaidCore;
import dev.herrastudio.tacticalactions.*;
import dev.tactical.Packets;
import dev.tactical.raid.RaidConfig;
import dev.tactical.raid.RaidManager;
import dev.tactical.raid.RaidSavedData;
import dev.tactical.raid.RaidSession;
import dev.tactical.raid.client.RaidClient;
import dev.tactical.raid.client.RaidSettlementScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Function;

import static dev.herrastudio.raidcore.integration.RaidCoreSmokeChecks.require;

/** Runs after the retained gold/inventory integration sequence in the same client. */
@EventBusSubscriber(modid = RaidCore.MOD_ID, value = Dist.CLIENT)
public final class RaidCoreClientSmoke {
    private static int phase, ticks;
    private static Vec3 baseline;
    private static String capture;

    private RaidCoreClientSmoke() {}

    public static void verifyBootstrap(Minecraft mc) throws Exception {
        require(Boolean.getBoolean("raidcore.smoke"), "Harness was not explicitly enabled");
        require(mc.gameDirectory.toPath().toRealPath().equals(Path.of(System.getProperty("raidcore.smoke.directory")).toRealPath()),
                "Harness directory is not isolated");
        RaidCoreSmokeChecks.verify();
        require(CompassConfig.SPEC.isLoaded() && MapConfig.SPEC.isLoaded() && FastSwitchConfig.SPEC.isLoaded()
                && PeekClientConfig.SPEC.isLoaded(), "One of the four legacy client configs did not load");
        for (String file : new String[]{"draginventory-compass-client.toml", "draginventory-map-client.toml",
                "draginventory-switch-client.toml", "tacticalactions-peek-client.toml"}) {
            require(java.nio.file.Files.isRegularFile(mc.gameDirectory.toPath().resolve("config").resolve(file)), "Missing config: " + file);
        }
        require(Arrays.asList(mc.options.keyMappings).containsAll(java.util.List.of(FactoryMapKeyBindings.OPEN_MAP,
                WheelKeyBindings.OPEN_WHEEL, TacticalActionsKeyMappings.LEAN_LEFT, TacticalActionsKeyMappings.LEAN_RIGHT,
                TacticalActionsKeyMappings.PRONE)), "A merged key mapping was not registered");
        require(Arrays.stream(AbstractContainerScreen.class.getDeclaredFields())
                .anyMatch(field -> field.getName().contains("draginventory$gesture")), "Drag screen mixin missing");
        require(mc.player.getMaxHealth() == 100 && value(p -> p.getMaxHealth() == 100), "Merged health subscriber missing");
        require(mc.player.getData(PlayerStamina.STATE).value() > 0, "Stamina attachment did not sync");
        require(mc.getConnection().hasChannel(Packets.Action.TYPE) && mc.getConnection().hasChannel(PeekNetwork.Request.TYPE),
                "Inventory or peek payload channel missing");
        require(PlayerAnimationAccess.getPlayerAnimationLayer(mc.player, RaidCoreSmokeChecks.id("tacticalactions:peek")) != null,
                "PAL peek factory did not register");
        require(PlayerAnimationAccess.getPlayerAnimationLayer(mc.player, TacticalActionsClient.LAYER) != null,
                "PAL prone factory did not register");
        require(value(p -> p.server.getCommands().getDispatcher().getRoot().getChild("raid") != null
                && p.server.getCommands().getDispatcher().getRoot().getChild("tacticalitems") != null), "Server commands missing");
        log("RAIDCORE_BOOTSTRAP_PASS: one mod, legacy registries, all four configs, keys, three Mixin families, health/stamina, payloads and PAL factories");
    }

    public static void begin(Minecraft mc) {
        baseline = value(ServerPlayer::position);
        PeekClientConfig.TOGGLE_INPUT.set(false);
        TacticalActionsKeyMappings.LEAN_LEFT.setDown(true);
        phase = 1;
        ticks = 0;
        capture = "raidcore-hud.png";
    }

    public static boolean tick(Minecraft mc) {
        ticks++;
        if (phase == 1 && ticks >= 15) {
            require(PeekStates.current(mc.player) > .8F && value(p -> PeekStates.current(p) > .8F), "Left peek did not reach client/server");
            require(value(p -> p.position().distanceToSqr(baseline) < .0001), "Peek moved the actual player");
            TacticalActionsKeyMappings.LEAN_LEFT.setDown(false);
            TacticalActionsKeyMappings.LEAN_RIGHT.setDown(true);
            phase = 2; ticks = 0;
        } else if (phase == 2 && ticks >= 15) {
            require(PeekStates.current(mc.player) < -.8F && value(p -> PeekStates.current(p) < -.8F), "Right peek did not reach client/server");
            TacticalActionsKeyMappings.LEAN_LEFT.setDown(true);
            phase = 3; ticks = 0;
        } else if (phase == 3 && ticks >= 34) {
            require(Math.abs(PeekStates.current(mc.player)) < .01F && value(p -> Math.abs(PeekStates.current(p)) < .01F), "Both-key peek reset failed");
            TacticalActionsKeyMappings.LEAN_LEFT.setDown(false);
            TacticalActionsKeyMappings.LEAN_RIGHT.setDown(false);
            log("RAIDCORE_PEEK_SYNC_PASS: actual key input, left/right/both, legacy payloads, fixed player position");
            KeyMapping.click(FactoryMapKeyBindings.OPEN_MAP.getKey());
            phase = 4; ticks = 0;
        } else if (phase == 4 && ticks >= 12) {
            require(mc.screen instanceof FactoryMapScreen, "Map key did not open the merged map");
            capture = "raidcore-map.png";
            phase = 5; ticks = 0;
        } else if (phase == 5 && ticks >= 8) {
            mc.screen.onClose();
            log("RAIDCORE_MAP_PASS: original M binding opened and rendered the v2.6.1 map");
            beginRaidClock();
            phase = 6; ticks = 0;
        } else if (phase == 6 && ticks >= 8) {
            require(MapMatchTimer.hasProvider() && MapMatchTimer.remainingSeconds() >= 1798
                    && MapMatchTimer.remainingSeconds() <= 1800, "Thirty-minute server Raid timer did not reach minimap");
            capture = "raidcore-raid-timer.png";
            value(p -> {
                var session = RaidManager.current(p).session;
                long elapsed = session.elapsedTicks();
                try {
                    require(p.server.getCommands().getDispatcher().execute("raid duration 1",
                            p.createCommandSourceStack().withPermission(4)) == 1, "Duration command failed");
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) {
                    throw new IllegalStateException(error);
                }
                require(RaidSavedData.get(p.server).config.matchDurationSeconds == 60
                        && session.matchDurationTicks() == 36_000 && session.elapsedTicks() == elapsed,
                        "Changing default duration restarted an active Raid");
                return true;
            });
            phase = 7; ticks = 0;
        } else if (phase == 7 && ticks >= 8) {
            require(value(p -> RaidManager.finish(p, RaidSession.Outcome.ABORTED, true)), "Raid abort failed");
            phase = 8; ticks = 0;
        } else if (phase == 8 && ticks >= 8) {
            require(mc.screen instanceof RaidSettlementScreen, "Raid abort settlement not displayed");
            require(MapMatchTimer.remainingSeconds() == -1 && !MapMatchTimer.hasProvider(), "Settled Raid left a stale map timer");
            require(value(RaidCoreClientSmoke::inLobby), "Raid abort did not return to lobby");
            mc.screen.onClose();
            phase = 9; ticks = 0;
        } else if (phase == 9 && ticks >= 8) {
            require(value(p -> RaidManager.current(p) == null), "Settlement acknowledgement did not clear the server record");
            value(p -> {
                RaidSavedData.get(p.server).config.matchDurationSeconds = 2;
                require(RaidManager.join(p), "Short timeout Raid could not start");
                require(RaidManager.start(p.server), "Short timeout shared start failed");
                return true;
            });
            phase = 10; ticks = 0;
        } else if (phase == 10 && ticks >= 55) {
            require(value(p -> {
                var record = RaidManager.current(p);
                return record.session.outcome() == RaidSession.Outcome.TIMED_OUT
                        && record.session.elapsedTicks() == 40 && record.session.remainingMatchTicks() == 0
                        && inLobby(p);
            }), "Server timeout did not settle once at the configured tick and return to lobby");
            require(mc.screen instanceof RaidSettlementScreen && MapMatchTimer.remainingSeconds() == -1,
                    "Timeout did not clear client clock and display settlement");
            capture = "raidcore-raid-timeout.png";
            phase = 11; ticks = 0;
        } else if (phase == 11 && ticks >= 8) {
            mc.screen.onClose();
            phase = 12; ticks = 0;
        } else if (phase == 12 && ticks >= 8) {
            value(p -> {
                require(RaidManager.current(p) == null, "Timeout acknowledgement failed");
                RaidSavedData.get(p.server).config.matchDurationSeconds = 1800;
                require(RaidManager.join(p), "Could not join another Raid after timeout");
                require(RaidManager.start(p.server), "Next shared Raid could not start");
                require(RaidManager.current(p).session.remainingMatchTicks() == 36_000, "Next Raid inherited an expired clock");
                return true;
            });
            phase = 13; ticks = 0;
        } else if (phase == 13 && ticks >= 8) {
            require(MapMatchTimer.remainingSeconds() >= 1798, "Next Raid did not start with a fresh client clock");
            value(p -> { RaidManager.logout(p); return true; });
            require(value(p -> RaidManager.current(p).session.outcome() == RaidSession.Outcome.ABORTED), "Logout did not settle Raid");
            RaidClient.clear();
            require(MapMatchTimer.remainingSeconds() == -1 && !MapMatchTimer.hasProvider(), "Disconnect did not clear Raid clock");
            log("RAIDCORE_RAID_TIMER_CLIENT_PASS: real snapshots, thirty-minute minimap timer, future-only duration change, abort/ack, timed expiry/lobby/settlement, rejoin and logout cleanup");
            log("RAIDCORE_CLIENT_SMOKE_PASS");
            return true;
        }
        require(ticks < 120, "Additional client sequence stalled at phase " + phase);
        return false;
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) throws Exception {
        if (capture == null) return;
        try (var png = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            png.writeToFile(Path.of(capture));
        }
        capture = null;
    }

    private static <T> T value(Function<ServerPlayer, T> action) {
        var mc = Minecraft.getInstance();
        var id = mc.player.getUUID();
        return mc.getSingleplayerServer().submit(() -> action.apply(mc.getSingleplayerServer().getPlayerList().getPlayer(id))).join();
    }
    private static void log(String message) { LoggerFactory.getLogger("RaidCoreSmoke").info(message); }

    private static void beginRaidClock() {
        require(MapMatchTimer.remainingSeconds() == -1, "A lobby timer started without a Raid");
        value(p -> {
            var config = new RaidConfig();
            config.minimumPlayers = 1; // Existing single-client smoke remains a deliberate debug fixture.
            config.lobby = RaidManager.location(p);
            var lobby = config.lobby;
            config.spawns.put("timer", new RaidConfig.Location(lobby.dimension(), lobby.x() + 12,
                    lobby.y(), lobby.z(), lobby.yaw(), lobby.pitch()));
            config.extractions.put("timer-exit", new RaidConfig.Extraction("timer-exit",
                    new RaidConfig.Location(lobby.dimension(), lobby.x() - 32, lobby.y(), lobby.z(), 0, 0),
                    1, 20, RaidConfig.DEFAULT_FX));
            var data = RaidSavedData.get(p.server);
            data.config = config;
            require(RaidManager.current(p) == null && RaidManager.join(p), "Initial Raid could not start");
            require(RaidManager.start(p.server), "Initial shared Raid could not start");
            var snapshot = RaidManager.snapshot(p);
            require(snapshot.getLong("matchRemainingTicks") == 36_000 && snapshot.getLong("matchTotalTicks") == 36_000,
                    "Initial server Raid snapshot is not thirty minutes");
            return true;
        });
    }

    private static boolean inLobby(ServerPlayer player) {
        var record = RaidManager.current(player);
        var lobby = RaidSavedData.get(player.server).config.lobby;
        return !record.returnPending && player.level().dimension().location().toString().equals(lobby.dimension())
                && Math.abs(player.getX() - lobby.x()) < .01 && Math.abs(player.getZ() - lobby.z()) < .01;
    }
}
