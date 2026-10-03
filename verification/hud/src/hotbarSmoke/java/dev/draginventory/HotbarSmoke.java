package dev.draginventory;

import dev.draginventory.client.ReferenceHotbar;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.LoggerFactory;

/** Opt-in integration fixture, never included in a normal release build. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class HotbarSmoke {
    private static boolean started;
    private static int ticks;
    private static int firstGuiWidth;
    private static float sprintStart;
    private static float sprintEnd;
    private static float redBeforeJump;
    private static int exhaustedAt = -1;
    private static double exhaustedY;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        var mc = Minecraft.getInstance();
        if (!started && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            started = true;
            mc.getWindow().setWindowed(1280, 720);
            mc.options.guiScale().set(2);
            mc.options.mainHand().set(HumanoidArm.RIGHT);
            mc.options.attackIndicator().set(AttackIndicatorStatus.CROSSHAIR);
            mc.options.renderDistance().set(2);
            mc.options.pauseOnLostFocus = false;
            mc.resizeDisplay();
            var rules = new GameRules();
            rules.getRule(GameRules.RULE_NATURAL_REGENERATION).set(false, null);
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            var settings = new LevelSettings("Hotbar UI check", GameType.SURVIVAL, false,
                    Difficulty.NORMAL, true, rules, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("hotbar-smoke-" + System.currentTimeMillis(),
                    settings, new WorldOptions(1L, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
        }
        if (!started || mc.player == null || mc.level == null || mc.screen != null) return;
        ticks++;
        if (ticks > 60) mc.player.resetAttackStrengthTicker();
        if (ticks == 20) {
            var server = mc.getSingleplayerServer();
            var playerId = mc.player.getUUID();
            server.submit(() -> {
                var player = server.getPlayerList().getPlayer(playerId);
                if (player.getMaxHealth() != 100 || player.getHealth() != 100)
                    throw new AssertionError("New player must spawn at 100/100 HP: "
                            + player.getHealth() + "/" + player.getMaxHealth());
                player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20);
                player.setHealth(12);
                NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
                if (player.getMaxHealth() != 100 || Math.abs(player.getHealth() - 60) > 0.01)
                    throw new AssertionError("Legacy 12/20 HP must migrate to 60/100");
                NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
                if (Math.abs(player.getHealth() - 60) > 0.01)
                    throw new AssertionError("Re-login must not refill health");
                player.setHealth(64);
                player.getFoodData().setFoodLevel(14);
                player.getFoodData().setSaturation(0);
            }).join();
            mc.player.experienceLevel = 12;
            mc.player.experienceProgress = 0.65f;
            var inventory = mc.player.getInventory();
            inventory.setItem(2, new ItemStack(Items.IRON_PICKAXE));
            var sword = new ItemStack(Items.IRON_SWORD);
            sword.setDamageValue(80);
            inventory.setItem(3, sword);
            inventory.setItem(4, new ItemStack(Items.CHEST, 32));
            inventory.setItem(5, new ItemStack(Items.DIAMOND, 5));
            inventory.setItem(6, new ItemStack(Items.ENDER_PEARL, 2));
            inventory.offhand.set(0, new ItemStack(Items.SHIELD));
            inventory.selected = 4;
        }
        if (ticks == 60) {
            if (mc.player.getMaxHealth() != 100 || mc.player.getHealth() != 64
                    || mc.player.getFoodData().getFoodLevel() != 14)
                throw new AssertionError("Server health/food did not synchronize to client");
            if (mc.player.getData(PlayerStamina.STATE).value() != 100)
                throw new AssertionError("New player must have full stamina");
            checkLayout(mc);
            capture(mc, "hotbar-scale2.png");
            firstGuiWidth = mc.getWindow().getGuiScaledWidth();
            mc.options.guiScale().set(3);
            mc.options.mainHand().set(HumanoidArm.LEFT);
            mc.options.attackIndicator().set(AttackIndicatorStatus.HOTBAR);
            mc.options.broadcastOptions();
            mc.resizeDisplay();
            mc.player.getInventory().selected = 8;
        }
        if (ticks == 70) mc.options.keyJump.setDown(true);
        if (ticks == 71) mc.options.keyJump.setDown(false);
        if (ticks == 80) {
            float stamina = mc.player.getData(PlayerStamina.STATE).value();
            if (Math.abs(stamina - 90) > 0.01)
                throw new AssertionError("Actual jump must cost 10 stamina on server and sync: " + stamina);
            capture(mc, "stamina-jump.png");
        }
        if (ticks == 90) {
            sprintStart = mc.player.getData(PlayerStamina.STATE).value();
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(true);
        }
        if (ticks == 220) {
            sprintEnd = mc.player.getData(PlayerStamina.STATE).value();
            if (sprintEnd <= 0 || sprintEnd > 20 || sprintStart - sprintEnd < 60)
                throw new AssertionError("Actual sprint must drain stamina into low range: " + sprintStart + " -> " + sprintEnd);
            if (!mc.player.isSprinting()) throw new AssertionError("Existing sprint must continue through red stamina");
            assertServerSprint(mc, true);
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
        }
        if (ticks == 225) capture(mc, "stamina-low.png");
        if (ticks == 226) {
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(true);
        }
        if (ticks == 230) {
            if (mc.player.isSprinting()) throw new AssertionError("Red stamina must block a new sprint");
            assertServerSprint(mc, false);
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            redBeforeJump = mc.player.getData(PlayerStamina.STATE).value();
            if (redBeforeJump <= 0) throw new AssertionError("Need positive red stamina for jump check");
        }
        if (ticks == 231) mc.options.keyJump.setDown(true);
        if (ticks == 232) mc.options.keyJump.setDown(false);
        if (ticks == 240) {
            float stamina = mc.player.getData(PlayerStamina.STATE).value();
            if (mc.player.onGround() || Math.abs(stamina - Math.max(0, redBeforeJump - 10)) > 0.01)
                throw new AssertionError("Red stamina must still allow a real jump: " + redBeforeJump + " -> " + stamina);
        }
        if (ticks == 260) {
            var playerId = mc.player.getUUID();
            mc.getSingleplayerServer().submit(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                player.setSprinting(false);
                player.setData(PlayerStamina.STATE, new StaminaState(20));
                player.setSprinting(true);
                if (player.isSprinting()) throw new AssertionError("Server must reject a sprint start at 20 stamina");
                player.setData(PlayerStamina.STATE, new StaminaState(21));
                player.setSprinting(true);
                if (!player.isSprinting()) throw new AssertionError("Server must allow a sprint start above 20");
                player.setData(PlayerStamina.STATE, new StaminaState(10));
                player.setSprinting(true);
                if (!player.isSprinting()) throw new AssertionError("Server must preserve an existing red sprint");
                player.setData(PlayerStamina.STATE, new StaminaState(0));
                player.setSprinting(true);
                if (player.isSprinting()) throw new AssertionError("Empty stamina must interrupt server sprint");
                var motion = player.getDeltaMovement();
                int jumps = player.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP));
                player.jumpFromGround();
                if (!motion.equals(player.getDeltaMovement()) || jumps != player.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP)))
                    throw new AssertionError("Rejected server jump must not change motion or jump stats");
                player.setData(PlayerStamina.STATE, new StaminaState(25));
            }).join();
        }
        if (ticks == 270) {
            if (mc.player.getData(PlayerStamina.STATE).value() <= 20)
                throw new AssertionError("Server stamina setup did not synchronize");
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(true);
        }
        if (ticks == 280 && !mc.player.isSprinting()) throw new AssertionError("Sprint must start with 25 stamina");
        if (ticks > 280 && exhaustedAt < 0 && mc.player.getData(PlayerStamina.STATE).value() == 0) exhaustedAt = ticks;
        if (ticks == 360 && exhaustedAt < 0) throw new AssertionError("Continuous sprint must reach zero stamina");
        if (exhaustedAt > 0 && ticks == exhaustedAt + 4) {
            if (mc.player.isSprinting()) throw new AssertionError("Exhaustion must stop actual client sprint");
            assertServerSprint(mc, false);
            capture(mc, "stamina-empty.png");
            exhaustedY = mc.player.getY();
            mc.options.keyJump.setDown(true);
        }
        if (exhaustedAt > 0 && ticks == exhaustedAt + 10) {
            if (!mc.player.onGround() || Math.abs(mc.player.getY() - exhaustedY) > 0.01)
                throw new AssertionError("Empty stamina must block actual jump input");
            mc.options.keyJump.setDown(false);
        }
        if (exhaustedAt > 0 && ticks == exhaustedAt + 30) {
            if (mc.player.getData(PlayerStamina.STATE).value() != 0 || mc.player.isSprinting())
                throw new AssertionError("Recovery must wait about two seconds; held sprint must stay blocked");
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
        }
        if (exhaustedAt > 0 && ticks == exhaustedAt + 52) {
            float recovered = mc.player.getData(PlayerStamina.STATE).value();
            if (recovered <= 0 || recovered >= 20)
                throw new AssertionError("Stamina must recover naturally after two seconds: " + recovered);
            capture(mc, "stamina-recovering.png");
        }
        if (exhaustedAt > 0 && ticks == exhaustedAt + 76) {
            if (mc.player.getData(PlayerStamina.STATE).value() <= 20)
                throw new AssertionError("Stamina must recover above the red zone");
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(true);
        }
        if (exhaustedAt > 0 && ticks == exhaustedAt + 82) {
            if (!mc.player.isSprinting()) throw new AssertionError("Recovered stamina must permit sprint again");
            assertServerSprint(mc, true);
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
        }
        if (exhaustedAt > 0 && ticks == exhaustedAt + 100) {
            checkLayout(mc);
            if (firstGuiWidth == mc.getWindow().getGuiScaledWidth())
                throw new AssertionError("GUI scale did not actually change");
            capture(mc, "hotbar-scale3.png");
            boolean transformed = Arrays.stream(AbstractContainerScreen.class.getDeclaredFields())
                    .anyMatch(f -> f.getName().contains("draginventory$gesture"));
            if (!transformed) throw new AssertionError("Existing inventory mixin missing");
            var server = mc.getSingleplayerServer();
            var playerId = mc.player.getUUID();
            server.submit(() -> {
                var old = server.getPlayerList().getPlayer(playerId);
                var replacement = server.getPlayerList().respawn(old, false, Entity.RemovalReason.KILLED);
                replacement.connection.player = replacement;
                if (replacement.getMaxHealth() != 100 || replacement.getHealth() != 100)
                    throw new AssertionError("Respawn must restore 100/100 HP");
                if (replacement.getData(PlayerStamina.STATE).value() != 100)
                    throw new AssertionError("Respawn must restore full stamina");
            }).join();
        }
        if (exhaustedAt > 0 && ticks == exhaustedAt + 140) {
            if (mc.player.getMaxHealth() != 100 || mc.player.getHealth() != 100)
                throw new AssertionError("Respawn health did not synchronize to client");
            if (mc.player.getData(PlayerStamina.STATE).value() != 100)
                throw new AssertionError("Respawn stamina did not synchronize to client");
            capture(mc, "status-bars-full.png");
            mc.getWindow().setWindowed(1280, 960);
            mc.options.guiScale().set(4);
            mc.resizeDisplay();
        }
        if (exhaustedAt > 0 && ticks == exhaustedAt + 160) {
            if (mc.getWindow().getGuiScale() != 4 || mc.getWindow().getGuiScaledWidth() != 320)
                throw new AssertionError("Expected actual GUI scale 4 at minimum GUI width");
            checkLayout(mc);
            capture(mc, "hotbar-scale4.png");
            LoggerFactory.getLogger("HotbarSmoke").info(
                    "HOTBAR_SMOKE_PASS: red blocks new sprint but preserves active sprint and jump; zero stops sprint and blocks jump on client/server; two-second recovery delay and sprint unlock; HUD scales 2/3/4; health migration and respawn; attachment sync and existing mixin verified");
            mc.stop();
        }
    }

    private static void assertServerSprint(Minecraft minecraft, boolean expected) {
        var server = minecraft.getSingleplayerServer();
        var playerId = minecraft.player.getUUID();
        server.submit(() -> {
            if (server.getPlayerList().getPlayer(playerId).isSprinting() != expected)
                throw new AssertionError("Server sprint state must be " + expected);
        }).join();
    }

    private static void checkLayout(Minecraft mc) throws ReflectiveOperationException {
        var ui = ReferenceHotbar.getOrCreateUI();
        var bar = ui.ui.getRootElement().getChildren().getFirst();
        if (bar.getChildren().size() != 6) throw new AssertionError("Expected five slots and offhand");
        if (Math.abs(bar.getSizeWidth() - 120) > 1) throw new AssertionError("Expected 120px hotbar");
        float expected = (mc.getWindow().getGuiScaledWidth() - ReferenceHotbar.BAR_WIDTH) / 2f;
        if (Math.abs(bar.getPositionX() - expected) > 1) throw new AssertionError("Bar not centered");
        if (Math.abs(bar.getPositionY() - (mc.getWindow().getGuiScaledHeight() - 27)) > 1)
            throw new AssertionError("Bar not bottom aligned");
        for (int visibleSlot = 0; visibleSlot < 5; visibleSlot++) {
            var slot = bar.getChildren().get(visibleSlot);
            float expectedX = bar.getPositionX() + visibleSlot * 24;
            if (Math.abs(slot.getPositionX() - expectedX) > 1 || Math.abs(slot.getSizeWidth() - 24) > 1)
                throw new AssertionError("Incorrect slot geometry at " + visibleSlot);
            var indexField = slot.getClass().getDeclaredField("index");
            indexField.setAccessible(true);
            if (indexField.getInt(slot) != visibleSlot + 4)
                throw new AssertionError("Visible slot must retain original inventory index");
        }
        var health = ui.ui.getRootElement().getChildren().get(1);
        var stamina = ui.ui.getRootElement().getChildren().get(2);
        float available = (mc.getWindow().getGuiScaledWidth() - 120) / 2f - 45;
        float healthBottom = available < 82 ? 44 : 8;
        if (Math.abs(health.getPositionX() - 8) > 1
                || Math.abs(health.getPositionY() + health.getSizeHeight()
                        - (mc.getWindow().getGuiScaledHeight() - healthBottom)) > 1)
            throw new AssertionError("Health portrait must stay at bottom left");
        if (Math.abs(stamina.getPositionX() - bar.getPositionX()) > 1
                || Math.abs(stamina.getSizeWidth() - 120) > 1
                || Math.abs(stamina.getSizeHeight() - 7) > 1
                || Math.abs(stamina.getPositionY() + stamina.getSizeHeight() - (bar.getPositionY() - 4)) > 1)
            throw new AssertionError("Stamina must align above the five hotbar slots");
        if (healthBottom == 8 && health.getPositionX() + health.getSizeWidth() > bar.getPositionX() - 29)
            throw new AssertionError("Health must not overlap hotbar or offhand");
    }

    private static void capture(Minecraft mc, String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(Path.of(name));
        }
    }
}
