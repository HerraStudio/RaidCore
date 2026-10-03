package dev.draginventory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import dev.draginventory.client.GwoHudBridge;
import dev.draginventory.client.WeaponSwitchAnimation;
import org.slf4j.LoggerFactory;

/** Opt-in visual QA using real GWO definitions, separate from the normal release. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class WeaponSmoke {
    private static boolean started;
    private static int ticks;
    private static Future<?> setup;
    private static long deadline;
    private static String pendingCapture;
    private static boolean stopAfterCapture;
    private static Future<?> changedEquipment;
    private static java.util.concurrent.CompletableFuture<Void> reloaded;
    private static int reloadTicks;
    private static int expectedNextTick = -1;
    private static com.mojang.blaze3d.platform.InputConstants.Key originalSecondKey;
    private static ItemStack[] savedWeapons;
    private static int animationQaTicks;
    private static boolean recording;
    private static long recordingStart;
    private static int recordingStage;
    private static int recordedFrames;
    private static final StringBuilder frameTimes = new StringBuilder("frame,elapsed_ms,physical_slot,scale,offset_x,offset_y\n");
    private static org.joml.Matrix4f layerPose;
    private static int restoredFrames;
    private static int laserQaTicks;
    private static net.minecraft.client.KeyMapping laserBinding;
    private static com.mojang.blaze3d.platform.InputConstants.Key originalLaserKey;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        var mc = Minecraft.getInstance();
        if (!started && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            started = true;
            deadline = System.currentTimeMillis() + 180_000;
            mc.getWindow().setWindowed(1600, 900);
            mc.options.guiScale().set(2);
            mc.options.renderDistance().set(2);
            mc.options.simulationDistance().set(5);
            mc.options.mouseWheelSensitivity().set(1d);
            mc.options.discreteMouseScroll().set(true);
            mc.options.pauseOnLostFocus = false;
            mc.resizeDisplay();
            var rules = new GameRules();
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            var settings = new LevelSettings("Weapon HUD reference", GameType.CREATIVE, false,
                    Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
            // Language is set before launch; never race a resource reload against world creation.
            mc.createWorldOpenFlows().createFreshLevel("weapon-reference-" + System.currentTimeMillis(),
                    settings, new WorldOptions(1L, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
        }
        if (!started) return;
        if (System.currentTimeMillis() > deadline) throw new AssertionError("Weapon visual QA timed out");
        if (mc.player == null || mc.level == null || mc.screen != null || mc.getOverlay() != null) return;
        if (HudMotionSmoke.running()) return;
        if (pendingCapture != null) return;
        if (setup == null) {
            var uuid = mc.player.getUUID();
            setup = mc.getSingleplayerServer().submit(() -> {
                try {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                    Class<?> registry = Class.forName("com.sgr792.gwo.content.WeaponContentRegistry");
                    var definitions = (Map<?, ?>) registry.getMethod("definitions").invoke(null);
                    for (var entry : definitions.entrySet()) {
                        var type = entry.getValue().getClass();
                        LoggerFactory.getLogger("WeaponSmoke").info("QA weapon {} category={}", entry.getKey(),
                                type.getMethod("creativeCategory").invoke(entry.getValue()));
                    }
                    var firearm = definitions.entrySet().stream()
                            .filter(e -> e.getKey().toString().toLowerCase().contains("m4")).findFirst().orElseThrow();
                    var laserGun = weapon(firearm.getKey(), firearm.getValue());
                    installLaser(laserGun, firearm.getValue());
                    player.getInventory().setItem(0, laserGun);
                    var second = definitions.entrySet().stream()
                            .filter(e -> e.getKey().toString().equals("gwo:ak103")).findFirst().orElseThrow();
                    player.getInventory().setItem(1, weapon(second.getKey(), second.getValue()));
                    var pistol = definitions.entrySet().stream().filter(e -> category(e.getValue()).equals("pistol"))
                            .findFirst().orElseThrow(() -> new AssertionError("No actual GWO pistol in test pack"));
                    player.getInventory().setItem(2, weapon(pistol.getKey(), pistol.getValue()));
                    var melee = definitions.entrySet().stream().filter(e -> e.getKey().toString().equals("gwo_meleepack:crowbar"))
                            .findFirst().orElseThrow();
                    player.getInventory().setItem(3, weapon(melee.getKey(), melee.getValue()));
                    for (int i = 4; i < 9; i++) player.getInventory().setItem(i, new ItemStack(Items.DIAMOND));
                    player.getInventory().selected = 0;
                    player.inventoryMenu.broadcastChanges();
                    player.serverLevel().setDayTime(6000);
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            return;
        }
        if (!setup.isDone()) return;
        setup.get();
        ticks++;
        if (expectedNextTick >= 0) {
            if (mc.player.getInventory().selected != expectedNextTick)
                throw new AssertionError("Vanilla/GWO overwrote compact selection on the following tick");
            expectedNextTick = -1;
        }
        if (laserQaTicks > 0) {
            laserQa(mc);
            return;
        }
        if (animationQaTicks > 0) {
            animationQa(mc);
            return;
        }
        if (ticks >= 125 && ticks <= 129 && mc.player.getInventory().selected != ticks - 121)
            throw new AssertionError("Vanilla keys 5-9 must select their slots on the following tick");
        mc.player.setXRot(25);
        if (ticks == 60) capture(mc, "weapon-slot1-scale2.png");
        if (ticks == 61) key(mc, 1);
        if (ticks == 62) capture(mc, "weapon-transition.png");
        if (ticks == 81) capture(mc, "weapon-slot2.png");
        if (ticks == 82) key(mc, 2);
        if (ticks == 102) capture(mc, "weapon-slot3-pistol.png");
        if (ticks == 103) key(mc, 3);
        if (ticks == 123) capture(mc, "weapon-slot4-melee.png");
        if (ticks >= 124 && ticks <= 128) key(mc, ticks - 120);
        if (ticks == 129) {
            var method = mc.mouseHandler.getClass().getDeclaredMethod("onScroll", long.class, double.class, double.class);
            method.setAccessible(true);
            for (int i = 0; i < 12; i++) {
                int previous = mc.player.getInventory().selected;
                method.invoke(mc.mouseHandler, mc.getWindow().getWindow(), 0d, -1d);
                if (mc.player.getInventory().selected != WeaponSlots.next(previous, -1, WeaponSlots.FULL))
                    throw new AssertionError("Scroll must update the selected slot immediately");
            }
            key(mc, 0);
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
        }
        if (ticks == 149) {
            capture(mc, "weapon-slot1-scale3.png");
        }
        if (ticks == 150) {
            mc.getWindow().setWindowed(1280, 960);
            mc.options.guiScale().set(4);
            mc.resizeDisplay();
        }
        if (ticks == 169) {
            capture(mc, "weapon-slot1-scale4.png");
        }
        if (ticks == 170) {
            changedEquipment = mc.getSingleplayerServer().submit(() -> {
                try {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                    var registry = Class.forName("com.sgr792.gwo.content.WeaponContentRegistry");
                    var id = ResourceLocation.parse("gwo:karambit");
                    var definition = registry.getMethod("get", ResourceLocation.class).invoke(null, id);
                    player.getInventory().setItem(1, ItemStack.EMPTY);
                    player.getInventory().setItem(3, weapon(id, definition));
                    player.inventoryMenu.broadcastChanges();
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
        }
        if (ticks == 195) {
            if (!changedEquipment.isDone()) throw new AssertionError("Equipment update stalled");
            changedEquipment.get();
            if (!mc.player.getInventory().getItem(1).isEmpty()) throw new AssertionError("Empty slot not synced");
            capture(mc, "weapon-icons-updated.png");
            savedWeapons = new ItemStack[4];
            for (int slot = 0; slot < 4; slot++) savedWeapons[slot] = mc.player.getInventory().getItem(slot).copy();
        }
        if (ticks == 196) keyExpect(mc, 1, 2); // Key 2 skips the empty physical slot 2.
        if (ticks == 197) capture(mc, "weapon-compact-key2.png");
        if (ticks == 198) keyExpect(mc, 2, 3);
        if (ticks == 199) capture(mc, "weapon-compact-key3.png");
        if (ticks == 200) {
            for (int i = 0; i < 4; i++) {
                scrollExpect(mc, -1, 0);
                scrollExpect(mc, -1, 2);
                scrollExpect(mc, -1, 3);
            }
            for (int i = 0; i < 4; i++) {
                scrollExpect(mc, 1, 2);
                scrollExpect(mc, 1, 0);
                scrollExpect(mc, 1, 3);
            }
            keyExpect(mc, 0, 0);
        }
        if (ticks == 201) keyExpect(mc, 3, 0); // Unused key 4 must not select an empty or wrong slot.
        if (ticks == 202) {
            originalSecondKey = mc.options.keyHotbarSlots[1].getKey();
            mc.options.keyHotbarSlots[1].setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(71));
            net.minecraft.client.KeyMapping.resetMapping();
            pressExpect(mc, 71, 2); // Rebind the second logical weapon to G.
        }
        if (ticks == 204) keyExpect(mc, 0, 0);
        if (ticks == 220) capture(mc, "weapon-rebound-key.png");
        if (ticks == 221) {
            mc.options.keyHotbarSlots[1].setKey(originalSecondKey);
            net.minecraft.client.KeyMapping.resetMapping();
            keepWeapons(mc, 0b1000);
        }
        if (ticks == 241) {
            assertEquipment(mc, 0b1000);
            keyExpect(mc, 0, 3);
            for (int i = 0; i < 6; i++) {
                scrollExpect(mc, 1, 3);
                scrollExpect(mc, -1, 3);
            }
            keyExpect(mc, 1, 3);
        }
        if (ticks == 242) capture(mc, "weapon-single.png");
        if (ticks == 243) keepWeapons(mc, 0);
        if (ticks == 263) {
            assertEquipment(mc, 0);
            for (int i = 0; i < 4; i++) {
                keyExpect(mc, i, 3);
                scrollExpect(mc, -1, 3);
                scrollExpect(mc, 1, 3);
            }
        }
        if (ticks == 264) {
            // Restore the sparse inventory for a final resource reload and visual check.
            var uuid = mc.player.getUUID();
            changedEquipment = mc.getSingleplayerServer().submit(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                for (int slot = 0; slot < 4; slot++) player.getInventory().setItem(slot, savedWeapons[slot].copy());
                player.inventoryMenu.broadcastChanges();
            });
        }
        if (ticks == 284) {
            assertEquipment(mc, 0b1101);
            keyExpect(mc, 0, 0);
        }
        if (ticks == 285) reloaded = mc.reloadResourcePacks();
        if (ticks > 285 && reloaded != null && reloaded.isDone() && mc.getOverlay() == null && ++reloadTicks == 30) {
            reloaded.get();
            capture(mc, "weapon-icons-reloaded.png");
            animationQaTicks = 1;
        }
    }

    private static void animationQa(Minecraft mc) throws Exception {
        if (recording) return;
        int phase = animationQaTicks++;
        if (phase == 1) {
            boolean hookPresent = java.util.Arrays.stream(Class.forName("com.sgr792.gwo.client.hud.GunHudEventHandler").getDeclaredMethods())
                    .anyMatch(method -> method.getName().contains("draginventory$animateMainHud"));
            if (!hookPresent) throw new AssertionError("GWO native main HUD animation mixin did not apply");
            var uuid = mc.player.getUUID();
            changedEquipment = mc.getSingleplayerServer().submit(() -> {
                try {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                    var id = ResourceLocation.parse("gwo:ak103");
                    var definition = Class.forName("com.sgr792.gwo.content.WeaponContentRegistry")
                            .getMethod("get", ResourceLocation.class).invoke(null, id);
                    player.getInventory().setItem(1, weapon(id, definition));
                    player.inventoryMenu.broadcastChanges();
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            mc.options.framerateLimit().set(60);
        }
        if (phase == 20) {
            assertEquipment(mc, 0b1111);
            keyExpect(mc, 1, 1);
        }
        if (phase == 40) keyExpect(mc, 2, 2);
        if (phase == 60) keyExpect(mc, 0, 0);
        if (phase == 80) capture(mc, "weapon-main-rest.png");
        if (phase == 81) {
            java.nio.file.Files.createDirectories(Path.of("main-hud-animation"));
            recording = true;
        }
        if (phase == 82) {
            if (recordedFrames < 12 || restoredFrames < recordedFrames)
                throw new AssertionError("Insufficient animated frames or unbalanced native HUD pose");
            java.nio.file.Files.writeString(Path.of("main-hud-animation/frames.csv"), frameTimes);
            capture(mc, "weapon-main-settled.png");
            LoggerFactory.getLogger("WeaponSmoke").info("WEAPON_SMOKE_PASS: compact keys/scroll, GUI 2/3/4, reload; native main HUD animation hook applied, {} real-time frames, pose restored, rapid retarget selects latest weapon immediately", recordedFrames);
            laserQaTicks = 1;
        }
    }

    private static void installLaser(ItemStack gun, Object definition) throws ReflectiveOperationException {
        var data = Class.forName("com.sgr792.gwo.item.GunData");
        var tree = Class.forName("com.sgr792.gwo.modular.ModuleTree");
        var modulesDefinition = definition.getClass().getMethod("moduleTree").invoke(definition);
        var modules = (Map<?, ?>) modulesDefinition.getClass().getMethod("modules").invoke(modulesDefinition);
        var original = data.getMethod("modulesTag", ItemStack.class).invoke(null, gun);
        var units = tree.getMethod("read", net.minecraft.nbt.ListTag.class).invoke(null, original);
        var root = data.getMethod("rootNodeId", ItemStack.class).invoke(null, gun);
        var install = Class.forName("com.sgr792.gwo.modular.ModuleTreeEditor").getMethod("installReplacingSlot",
                java.util.List.class, definition.getClass(), Class.forName("com.sgr792.gwo.content.GunDefinition$ModuleDefinition"), String.class);
        for (var module : modules.values()) {
            if (!"laser".equals(module.getClass().getMethod("type").invoke(module))) continue;
            var updated = install.invoke(null, units, definition, module, root);
            var tag = tree.getMethod("write", java.util.List.class).invoke(null, updated);
            data.getMethod("setModulesTag", ItemStack.class, net.minecraft.nbt.ListTag.class).invoke(null, gun, tag);
            boolean installed = (boolean) Class.forName("com.sgr792.gwo.client.render.fx.LaserModuleRenderer")
                    .getMethod("hasInstalledLaser", ItemStack.class, definition.getClass()).invoke(null, gun, definition);
            if (!installed) continue;
            data.getMethod("recalculateProperties", ItemStack.class, definition.getClass()).invoke(null, gun, definition);
            data.getMethod("setLaserEnabled", ItemStack.class, boolean.class).invoke(null, gun, true);
            LoggerFactory.getLogger("WeaponSmoke").info("QA installed real laser module {}", module.getClass().getMethod("id").invoke(module));
            return;
        }
        throw new AssertionError("No compatible real laser module could be installed");
    }

    private static void laserQa(Minecraft mc) throws Exception {
        int phase = laserQaTicks++;
        if (phase == 1) {
            boolean hookPresent = java.util.Arrays.stream(Class.forName("com.sgr792.gwo.client.hud.GunHudEventHandler").getDeclaredMethods())
                    .anyMatch(method -> method.getName().contains("draginventory$renderControls"));
            if (!hookPresent) throw new AssertionError("GWO control HUD mixin did not apply");
            var field = Class.forName("com.sgr792.gwo.client.input.ClientInputEventHandler").getDeclaredField("TOGGLE_LASER");
            field.setAccessible(true);
            laserBinding = (net.minecraft.client.KeyMapping) field.get(null);
            originalLaserKey = laserBinding.getKey();
            laserBinding.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(89));
            net.minecraft.client.KeyMapping.resetMapping();
            mc.getWindow().setWindowed(1600, 900);
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
            keyExpect(mc, 0, 0);
        }
        if (phase == 20) {
            assertControlState(mc, true, true, "Y");
            capture(mc, "laser-on-y.png");
        }
        if (phase == 21 || phase == 41) {
            mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), 89, 0, 1, 0);
            mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), 89, 0, 0, 0);
        }
        if (phase == 40) {
            assertControlState(mc, true, false, "Y");
            capture(mc, "laser-off-y.png");
        }
        if (phase == 60) {
            assertControlState(mc, true, true, "Y");
            laserBinding.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(75));
            net.minecraft.client.KeyMapping.resetMapping();
        }
        if (phase == 65) {
            assertControlState(mc, true, true, "K");
            capture(mc, "laser-rebound-k.png");
        }
        if (phase == 66) keyExpect(mc, 1, 1);
        if (phase == 85) {
            assertControlState(mc, false, false, "K");
            capture(mc, "laser-uninstalled.png");
            laserBinding.setKey(originalLaserKey);
            net.minecraft.client.KeyMapping.resetMapping();
            LoggerFactory.getLogger("WeaponSmoke").info("LASER_CONTROL_SMOKE_PASS: real attachment, Y input toggles on/off, rebound K, uninstalled gun hidden, HD key textures, resource reload and GUI 2/3/4");
            HudMotionSmoke.start();
        }
    }

    private static void assertControlState(Minecraft mc, boolean installed, boolean enabled, String key) throws Exception {
        var gun = mc.player.getMainHandItem();
        var view = Class.forName("com.sgr792.gwo.modular.GunViews").getMethod("view", ItemStack.class).invoke(null, gun);
        var reader = Class.forName("dev.draginventory.client.GwoControlHud").getDeclaredMethod("state", Object.class, ItemStack.class);
        reader.setAccessible(true);
        var state = reader.invoke(null, view, gun);
        if (state == null) throw new AssertionError("Control HUD reflection failed");
        for (var entry : Map.of("laserInstalled", installed, "laserEnabled", enabled, "laserKey", key).entrySet()) {
            var getter = state.getClass().getDeclaredMethod(entry.getKey());
            getter.setAccessible(true);
            if (!entry.getValue().equals(getter.invoke(state))) throw new AssertionError("Control state mismatch: " + entry);
        }
        var cache = Class.forName("dev.draginventory.client.HudKeyIcons").getDeclaredField("CACHE");
        cache.setAccessible(true);
        if (((Map<?, ?>) cache.get(null)).isEmpty()) throw new AssertionError("HD keycaps were not rendered");
    }

    @SubscribeEvent
    public static void startAnimationFrame(RenderGuiEvent.Pre event) {
        if (!recording) return;
        var mc = Minecraft.getInstance();
        if (recordingStart == 0) {
            recordingStart = System.currentTimeMillis();
            keyExpect(mc, 1, 1);
        }
        long elapsed = System.currentTimeMillis() - recordingStart;
        if (elapsed >= 350 && recordingStage == 0) {
            keyExpect(mc, 0, 0);
            recordingStage = 1;
        }
        if (elapsed >= 700 && recordingStage == 1) {
            keyExpect(mc, 1, 1);
            keyExpect(mc, 2, 2);
            recordingStage = 2;
        }
    }

    @SubscribeEvent
    public static void beforeNativeHud(RenderGuiLayerEvent.Pre event) {
        if (recording && GwoHudBridge.isGunHud(event.getName()))
            layerPose = new org.joml.Matrix4f(event.getGuiGraphics().pose().last().pose());
    }

    @SubscribeEvent
    public static void afterNativeHud(RenderGuiLayerEvent.Post event) {
        if (!recording || !GwoHudBridge.isGunHud(event.getName())) return;
        if (layerPose == null || !layerPose.equals(event.getGuiGraphics().pose().last().pose(), 0.00001f))
            throw new AssertionError("Main HUD transform leaked into other HUD layers");
        restoredFrames++;
    }

    private static String category(Object definition) {
        try { return (String) definition.getClass().getMethod("creativeCategory").invoke(definition); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    private static ItemStack weapon(Object id, Object definition) throws ReflectiveOperationException {
        var stack = (ItemStack) Class.forName("com.sgr792.gwo.GwoMod")
                .getMethod("weaponStack", ResourceLocation.class).invoke(null, id);
        var data = Class.forName("com.sgr792.gwo.item.GunData");
        data.getMethod("initialize", ItemStack.class, ResourceLocation.class, definition.getClass())
                .invoke(null, stack, id, definition);
        data.getMethod("setAmmo", ItemStack.class, int.class).invoke(null, stack, 30);
        return stack;
    }

    private static void key(Minecraft mc, int slot) {
        mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), 49 + slot, 0, 1, 0);
        if (slot < 4 && mc.player.getInventory().selected != slot)
            throw new AssertionError("Weapon number key must select immediately: " + slot);
        mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), 49 + slot, 0, 0, 0);
    }

    private static void keyExpect(Minecraft mc, int binding, int physicalSlot) {
        pressExpect(mc, 49 + binding, physicalSlot);
    }

    private static void pressExpect(Minecraft mc, int keyCode, int physicalSlot) {
        mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), keyCode, 0, 1, 0);
        if (mc.player.getInventory().selected != physicalSlot)
            throw new AssertionError("Compact key " + keyCode + " selected " + mc.player.getInventory().selected + ", expected " + physicalSlot);
        mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), keyCode, 0, 0, 0);
        expectedNextTick = physicalSlot;
    }

    private static void scrollExpect(Minecraft mc, double amount, int physicalSlot) throws Exception {
        var method = mc.mouseHandler.getClass().getDeclaredMethod("onScroll", long.class, double.class, double.class);
        method.setAccessible(true);
        method.invoke(mc.mouseHandler, mc.getWindow().getWindow(), 0d, amount);
        if (mc.player.getInventory().selected != physicalSlot)
            throw new AssertionError("Scroll failed to skip empty slots; expected " + physicalSlot
                    + " actual=" + mc.player.getInventory().selected + " mask="
                    + dev.draginventory.client.WeaponScroll.occupied(mc.player.getInventory())
                    + " overlay=" + mc.getOverlay() + " sensitivity=" + mc.options.mouseWheelSensitivity().get());
        expectedNextTick = physicalSlot;
    }

    private static void keepWeapons(Minecraft mc, int mask) {
        var uuid = mc.player.getUUID();
        changedEquipment = mc.getSingleplayerServer().submit(() -> {
            var player = mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
            for (int slot = 0; slot < 4; slot++) if ((mask & (1 << slot)) == 0) player.getInventory().setItem(slot, ItemStack.EMPTY);
            player.inventoryMenu.broadcastChanges();
        });
    }

    private static void assertEquipment(Minecraft mc, int mask) throws Exception {
        if (!changedEquipment.isDone()) throw new AssertionError("Server equipment update stalled");
        changedEquipment.get();
        if (dev.draginventory.client.WeaponScroll.occupied(mc.player.getInventory()) != mask)
            throw new AssertionError("Sparse equipment update not synced");
    }

    private static void capture(Minecraft mc, String name) throws Exception {
        pendingCapture = name;
    }

    @SubscribeEvent
    public static void captureFrame(RenderGuiEvent.Post event) throws Exception {
        if (recording) recordAnimation(event);
        if (pendingCapture == null) return;
        var mc = Minecraft.getInstance();
        event.getGuiGraphics().flush();
        var iconType = Class.forName("dev.draginventory.client.WeaponSlotIcons");
        var cacheField = iconType.getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        var icons = (Map<?, ?>) cacheField.get(null);
        for (int slot : WeaponSlots.candidates(mc.player.getInventory().selected,
                dev.draginventory.client.WeaponScroll.occupied(mc.player.getInventory()))) {
            var stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            var sources = dev.draginventory.client.GwoHudBridge.icons(stack);
            if (sources == null) throw new AssertionError("No icon source for " + stack);
            var hud = (java.util.Optional<?>) icons.get(sources.hud());
            var item = (java.util.Optional<?>) icons.get(sources.item());
            var icon = hud != null && hud.isPresent() ? hud.orElseThrow()
                    : item != null && item.isPresent() ? item.orElseThrow() : null;
            if (icon == null) throw new AssertionError("Actual weapon texture missing: " + sources);
            var textureMethod = icon.getClass().getDeclaredMethod("texture");
            textureMethod.setAccessible(true);
            var texture = (net.minecraft.client.renderer.texture.DynamicTexture) mc.getTextureManager()
                    .getTexture((ResourceLocation) textureMethod.invoke(icon));
            var pixels = texture.getPixels();
            boolean visible = false;
            for (int y = 0; y < pixels.getHeight(); y++) for (int x = 0; x < pixels.getWidth(); x++) {
                int pixel = pixels.getPixelRGBA(x, y);
                if ((pixel & 0xFFFFFF) != 0xFFFFFF) throw new AssertionError("Weapon icon is not white");
                visible |= (pixel >>> 24) > 128;
            }
            if (!visible) throw new AssertionError("Weapon icon is transparent");
        }
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(Path.of(pendingCapture));
        }
        pendingCapture = null;
        if (stopAfterCapture) mc.stop();
    }

    private static void recordAnimation(RenderGuiEvent.Post event) throws Exception {
        var mc = Minecraft.getInstance();
        event.getGuiGraphics().flush();
        long elapsed = System.currentTimeMillis() - recordingStart;
        var motion = WeaponSwitchAnimation.mainPose(System.currentTimeMillis());
        try (var frame = Screenshot.takeScreenshot(mc.getMainRenderTarget());
                var crop = new com.mojang.blaze3d.platform.NativeImage(400, 200, false)) {
            for (int y = 0; y < 200; y++) for (int x = 0; x < 400; x++)
                crop.setPixelRGBA(x, y, frame.getPixelRGBA(frame.getWidth() - 400 + x, frame.getHeight() - 200 + y));
            crop.writeToFile(Path.of("main-hud-animation/frame-%03d.png".formatted(recordedFrames)));
        }
        frameTimes.append(recordedFrames++).append(',').append(elapsed).append(',')
                .append(mc.player.getInventory().selected).append(',').append(motion.scale()).append(',')
                .append(motion.x()).append(',').append(motion.y()).append('\n');
        if (elapsed >= 1080) recording = false;
    }
}
