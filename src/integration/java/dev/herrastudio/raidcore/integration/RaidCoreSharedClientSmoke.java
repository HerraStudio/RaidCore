package dev.herrastudio.raidcore.integration;

import dev.draginventory.client.map.MapMatchTimer;
import dev.herrastudio.raidcore.RaidCore;
import dev.tactical.raid.RaidPackets;
import dev.tactical.raid.client.RaidClient;
import dev.tactical.raid.client.RaidSettlementScreen;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.LoggerFactory;
import static dev.herrastudio.raidcore.integration.RaidCoreSmokeChecks.require;

@EventBusSubscriber(modid = RaidCore.MOD_ID, value = Dist.CLIENT)
public final class RaidCoreSharedClientSmoke {
    private static final String ROLE = System.getProperty("raidcore.sharedSmoke.role", "alpha");
    private static final ArrayList<UUID> rounds = new ArrayList<>();
    private static final HashSet<Integer> captured = new HashSet<>();
    private static CompoundTag latest = new CompoundTag();
    private static boolean started, joined, attemptedAck, attemptedReentry, stopped;
    private static long began;
    private static int ticks, settledTicks, fourthTicks;
    private static String capture;
    private static UUID lastAck;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean("raidcore.sharedSmoke") || stopped) return;
        var mc = Minecraft.getInstance();
        if (!started && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            require(mc.gameDirectory.toPath().toRealPath().equals(Path.of(System.getProperty("raidcore.smoke.directory")).toRealPath()),
                    "Shared client directory is not isolated");
            started = true; began = System.currentTimeMillis();
            mc.getWindow().setWindowed(1100, 700); mc.options.guiScale().set(2);
            mc.options.renderDistance().set(2); mc.options.pauseOnLostFocus = false;
            mc.options.enableVsync().set(false); mc.options.framerateLimit().set(60); mc.resizeDisplay();
            var receiver = RaidPackets.snapshotReceiver;
            RaidPackets.snapshotReceiver = data -> { latest = data.copy(); receiver.accept(data); };
            var address = "127.0.0.1:25589";
            ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(address),
                    new ServerData("Shared Raid native smoke", address, ServerData.Type.LAN), false, null);
        }
        if (!started) return;
        require(!(mc.screen instanceof DisconnectedScreen), "Shared dedicated server disconnected before client completion: " + ROLE);
        require(System.currentTimeMillis() - began < 240_000, "Shared client sequence timed out: " + ROLE + " " + latest);
        if (mc.player == null || mc.level == null) return;
        ticks++;
        if (!joined && ticks >= 15 && mc.player.isAlive()) {
            require(mc.getConnection() != null && mc.getConnection().hasChannel(RaidPackets.Snapshot.TYPE), "Raid snapshot channel missing");
            mc.player.connection.sendCommand("raid join"); joined = true;
        }
        if (latest.hasUUID("session")) {
            UUID id = latest.getUUID("session");
            if (!rounds.contains(id)) { rounds.add(id); settledTicks = 0; }
        }
        String status = latest.getString("status");
        if ("WAITING".equals(status)) {
            require(MapMatchTimer.remainingSeconds() == -1, "Waiting roster started a client match clock");
            if (latest.getInt("matchPlayers") == 2 && mc.screen == null && rounds.size() == 1)
                capture = "shared-" + ROLE + "-waiting.png";
        }
        if (("ACTIVE".equals(status) || "EXTRACTING".equals(status)) && "IN_RAID".equals(latest.getString("matchPhase"))) {
            require(latest.getInt("matchPlayers") == 2 && latest.getUUID("session").equals(latest.getUUID("match")),
                    "Client does not share the server round ID and roster");
            require(latest.getLong("matchTotalTicks") == (rounds.size() == 3 ? 40 : 36_000), "Round duration was not captured at start");
            if (mc.screen == null && rounds.size() <= 2 && captured.add(rounds.size())) {
                require(MapMatchTimer.remainingSeconds() >= 1790, "Fresh shared clock is unexpectedly short");
                capture = "shared-" + ROLE + "-round" + rounds.size() + ".png";
            }
            if ("beta".equals(ROLE) && rounds.size() == 1 && !attemptedAck) {
                PacketDistributor.sendToServer(new RaidPackets.Acknowledge(latest.getUUID("session")));
                attemptedAck = true;
            }
            if ("beta".equals(ROLE) && rounds.size() == 4 && ++fourthTicks >= 20) {
                RaidClient.clear();
                require(MapMatchTimer.remainingSeconds() == -1 && !MapMatchTimer.hasProvider(), "Disconnect did not clear client timer");
                log("SHARED_RAID_CLIENT_BETA_PASS: real multiplayer snapshots, common IDs, waiting, four rounds, settlement/respawn and disconnect cleanup");
                stopped = true; mc.stop(); return;
            }
        }
        if (mc.screen instanceof DeathScreen && !mc.player.isAlive()) mc.player.respawn();
        if ("SETTLED".equals(status) && mc.screen instanceof RaidSettlementScreen) {
            require(MapMatchTimer.remainingSeconds() == -1, "Settled participant retained a match clock");
            UUID id = latest.getUUID("session");
            if (!id.equals(lastAck)) {
                settledTicks++;
                if (settledTicks == 5) capture = "shared-" + ROLE + "-result" + rounds.size() + ".png";
                if (settledTicks >= 12) { lastAck = id; settledTicks = 0; mc.screen.onClose(); }
            }
        }
        if ("alpha".equals(ROLE) && rounds.size() == 1 && "IDLE".equals(status)
                && "IN_RAID".equals(latest.getString("matchPhase")) && !attemptedReentry) {
            mc.player.connection.sendCommand("raid join"); attemptedReentry = true;
        }
        if ("alpha".equals(ROLE) && rounds.size() == 4 && "IDLE".equals(status)
                && "NONE".equals(latest.getString("matchPhase"))) {
            require(attemptedReentry, "Reentry packet was not exercised");
            log("SHARED_RAID_CLIENT_ALPHA_PASS: real multiplayer snapshots, common IDs, waiting, four rounds, results/ack and active-round reentry request");
            stopped = true; mc.stop();
        }
    }

    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) throws Exception {
        if (capture == null) return;
        var mc = Minecraft.getInstance();
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(mc.gameDirectory.toPath().resolve(capture));
        }
        capture = null;
    }
    private static void log(String text) { LoggerFactory.getLogger("SharedRaidSmoke").info(text); }
}
