package dev.herrastudio.raidcore.integration;

import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import dev.draginventory.FastSwitchConfig;
import dev.draginventory.PlayerStamina;
import dev.draginventory.client.compass.CompassConfig;
import dev.draginventory.client.map.FactoryMapKeyBindings;
import dev.draginventory.client.map.FactoryMapScreen;
import dev.draginventory.client.map.MapConfig;
import dev.draginventory.client.wheel.WheelKeyBindings;
import dev.herrastudio.raidcore.RaidCore;
import dev.herrastudio.tacticalactions.*;
import dev.tactical.Packets;
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
}
