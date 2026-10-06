package dev.tactical.raid.client;

import dev.draginventory.client.map.MapMatchTimer;
import dev.tactical.raid.RaidPackets;
import dev.tactical.raid.RaidSession;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class RaidClient {
    private static final RaidHud HUD = new RaidHud();
    private static final RaidMatchTimer MATCH_TIMER = new RaidMatchTimer();
    private static CompoundTag pendingResult;
    private static UUID awaitingAcknowledgement;
    private static RaidSettlementScreen displayedScreen;

    public static void acceptSnapshot(CompoundTag data) {
        HUD.accept(data);
        String status = data.getString("status");
        if (data.hasUUID("session") && ("ACTIVE".equals(status) || "EXTRACTING".equals(status))) {
            if (!MATCH_TIMER.isActive()) MapMatchTimer.stop();
            long remaining = data.contains("matchRemainingTicks") ? data.getLong("matchRemainingTicks")
                    : RaidSession.DEFAULT_MATCH_DURATION_SECONDS * 20L - Math.max(0, data.getLong("elapsedTicks"));
            MATCH_TIMER.sync(remaining);
            MapMatchTimer.registerRaidProvider(MATCH_TIMER);
        } else if (MATCH_TIMER.isActive()) {
            MATCH_TIMER.clear();
            MapMatchTimer.registerRaidProvider(null);
            MapMatchTimer.stop();
        }
        if (data.hasUUID("ackRejectedSession")
                && data.getUUID("ackRejectedSession").equals(awaitingAcknowledgement)) awaitingAcknowledgement = null;
        if ("SETTLED".equals(data.getString("status")) && data.hasUUID("session")) {
            pendingResult = data.copy();
        } else {
            pendingResult = null;
            awaitingAcknowledgement = null;
            displayedScreen = null;
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof RaidSettlementScreen) mc.setScreen(null);
        }
    }
    public static void acceptZones(CompoundTag data) {
        var zones = new ArrayList<RaidEffects.SmokeZone>();
        var list = data.getList("zones", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            var zone = list.getCompound(index);
            zones.add(new RaidEffects.SmokeZone(zone.getString("id"), zone.getString("dimension"),
                    zone.getDouble("x"), zone.getDouble("y"), zone.getDouble("z"), zone.getString("fx")));
        }
        RaidEffects.setZones(zones);
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        RaidEffects.tick();
        if (pendingResult == null || mc.player == null || !mc.player.isAlive()
                || mc.level == null || pendingResult.getBoolean("returnPending")
                || mc.screen instanceof DeathScreen || mc.screen instanceof ReceivingLevelScreen) return;
        var session = pendingResult.getUUID("session");
        if (session.equals(awaitingAcknowledgement) || displayedScreen != null && mc.screen == displayedScreen) return;
        var result = pendingResult;
        displayedScreen = new RaidSettlementScreen(result, () -> {
            awaitingAcknowledgement = session;
            if (mc.getConnection() != null) PacketDistributor.sendToServer(new RaidPackets.Acknowledge(session));
        });
        mc.setScreen(displayedScreen);
    }
    @SubscribeEvent public static void render(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.isAlive() && !mc.options.hideGui)
            HUD.render(event.getGuiGraphics(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    public static void clear() {
        HUD.clear();
        MATCH_TIMER.clear();
        MapMatchTimer.registerRaidProvider(null);
        MapMatchTimer.stop();
        RaidEffects.clear();
        pendingResult = null;
        awaitingAcknowledgement = null;
        displayedScreen = null;
    }
    @EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                RaidPackets.snapshotReceiver = RaidClient::acceptSnapshot;
                RaidPackets.zonesReceiver = RaidClient::acceptZones;
            });
        }
    }
    private RaidClient() {}
}
