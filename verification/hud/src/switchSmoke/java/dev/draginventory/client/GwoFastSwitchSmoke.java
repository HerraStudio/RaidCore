package dev.draginventory.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.slf4j.LoggerFactory;

/** Opt-in, isolated native GWO runtime probe. No protected models or release assets are required. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class GwoFastSwitchSmoke {
    private enum Phase { SYNC, FIRST_DRAW, SWITCH_B, SHOT_B, SETTLE_B, RAPID_SWITCH, SHOT_C,
        SETTLE_C, RELOAD_LOCK, SERVER_CADENCE_WAIT, FINISH, DONE }

    private static final Path OUTPUT = Path.of("switch-smoke");
    private static final String PREFIX = "com.sgr792.gwo.";
    private static final AtomicInteger bullets = new AtomicInteger();
    private static final Map<String, Class<?>> TYPES = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Object> DEFINITIONS = new LinkedHashMap<>();
    private static final ResourceLocation[] IDS = {
            ResourceLocation.parse("dragswitchqa:rifle_a"), ResourceLocation.parse("dragswitchqa:rifle_b"),
            ResourceLocation.parse("dragswitchqa:rifle_c") };
    private static final StringBuilder frames = new StringBuilder(
            "frame,phase,phase_ms,slot,block_shoot,equip_progress,hand_clip,hand_time,hand_progress\n");
    private static boolean started, installed, stopped, switchWasBlocked, rapidRetargeted;
    private static UUID playerId;
    private static long deadline, phaseStarted, switchStarted, firstShotDelay, switchReadyMs, rapidReadyMs;
    private static int ticks, phaseTick, assertions, frameCount, bulletBaseline, acceleratedSamples;
    private static int reloadBulletBaseline, cadenceBulletBaseline;
    private static Object runtime, tasks, animations, draw, lastSequence;
    private static Method intent;
    private static float lastSampleTime;
    private static long lastSampleAt;
    private static Phase phase = Phase.SYNC;

    private GwoFastSwitchSmoke() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (stopped) return;
        try { advance(Minecraft.getInstance()); }
        catch (Throwable error) { fail(error); }
    }

    private static void advance(Minecraft mc) throws Exception {
        if (!started && mc.getOverlay() == null
                && mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) {
            mc.screen.onClose();
            return;
        }
        if (!started && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            started = true;
            deadline = now() + 180_000;
            Files.createDirectories(OUTPUT);
            mc.getWindow().setWindowed(1280, 720);
            mc.options.guiScale().set(2);
            mc.options.renderDistance().set(2);
            mc.options.simulationDistance().set(5);
            mc.options.framerateLimit().set(60);
            mc.options.pauseOnLostFocus = false;
            mc.resizeDisplay();
            var rules = new GameRules();
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            mc.createWorldOpenFlows().createFreshLevel("gwo-fast-switch-" + now(),
                    new LevelSettings("Native GWO switch QA", GameType.CREATIVE, false,
                            Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(1, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                            .value().createWorldDimensions(), mc.screen);
        }
        if (!started) return;
        check(now() <= deadline, "Native switch probe timed out in " + phase);
        if (mc.player == null || mc.level == null || mc.screen != null || mc.getOverlay() != null) return;
        if (!installed) {
            installed = true;
            installFixture(mc);
            change(Phase.SYNC);
        }
        ticks++;
        long age = now() - phaseStarted;
        switch (phase) {
            case SYNC -> {
                if (ticks - phaseTick < 20) return;
                for (int slot = 0; slot < 3; slot++) {
                    check(IDS[slot].equals(contentId(mc.player.getInventory().getItem(slot))),
                            "Actual server-created GWO gun synchronized in slot " + slot);
                    check(serverAmmo(mc, slot) == 30, "Initial real server ammo = 30 in slot " + slot);
                }
                verifyNativeTiming();
                change(Phase.FIRST_DRAW);
            }
            case FIRST_DRAW -> {
                if (!ready(mc, 0)) {
                    check(age < 2000, "Initial native draw must complete");
                    return;
                }
                check(acceleratedSamples > 0, "Native hand-action timeline advances at 2x speed");
                switchWasBlocked = false;
                switchStarted = now();
                key(mc, 1);
                change(Phase.SWITCH_B);
            }
            case SWITCH_B -> {
                switchWasBlocked |= blocked() || equip() < .999f;
                check(age < 1500, "Accelerated B switch must finish, including pose and fire lock");
                if (age < 150 || !ready(mc, 1)) return;
                switchReadyMs = now() - switchStarted;
                check(switchWasBlocked, "Switch retains the native handoff before becoming ready");
                check(switchReadyMs <= 1050, "B full readiness <= 1050 ms including 20 Hz rounding: " + switchReadyMs);
                assertServerSelected(mc, 1);
                beginShot(mc);
                change(Phase.SHOT_B);
            }
            case SHOT_B -> {
                check(age < 1500, "Real native left-attack shot must reach server after switch B");
                if (bullets.get() == bulletBaseline) return;
                mc.options.keyAttack.setDown(false);
                check(bullets.get() == bulletBaseline + 1, "Semi input creates one actual GWO BulletEntity");
                check(serverAmmo(mc, 1) == 29, "Native FirePayload consumes real server B ammunition");
                check(serverAmmo(mc, 0) == 30, "Outgoing A ammunition is unchanged");
                firstShotDelay = age;
                change(Phase.SETTLE_B);
            }
            case SETTLE_B -> {
                if (age < 350 || !ready(mc, 1)) return;
                rapidRetargeted = false;
                switchStarted = now();
                key(mc, 0);
                change(Phase.RAPID_SWITCH);
            }
            case RAPID_SWITCH -> {
                if (!rapidRetargeted && age >= 60) {
                    key(mc, 1);
                    key(mc, 2);
                    rapidRetargeted = true;
                }
                check(age < 1500, "Rapid native retarget must complete");
                if (!rapidRetargeted || age < 200 || !ready(mc, 2)) return;
                rapidReadyMs = now() - switchStarted;
                check(rapidReadyMs <= 1050, "Rapid retarget finishes accelerated native handoff: " + rapidReadyMs);
                assertServerSelected(mc, 2);
                check(serverAmmo(mc, 0) == 30 && serverAmmo(mc, 1) == 29 && serverAmmo(mc, 2) == 30,
                        "Rapid switch does not create shots on stale weapons");
                beginShot(mc);
                change(Phase.SHOT_C);
            }
            case SHOT_C -> {
                check(age < 1500, "Native selected C must fire after clipped draw");
                if (bullets.get() == bulletBaseline) return;
                mc.options.keyAttack.setDown(false);
                check(bullets.get() == bulletBaseline + 1, "Clipped draw still emits one native server bullet");
                check(serverAmmo(mc, 2) == 29, "Latest target C owns the real accepted shot");
                change(Phase.SETTLE_C);
            }
            case SETTLE_C -> {
                if (age < 350 || !ready(mc, 2)) return;
                Object reload = stat("client.runtime.RuntimeGunTasks", "reload", new Class<?>[] {long.class}, 600L);
                Object admitted = call(tasks, "start", new Class<?>[] {type("client.runtime.RuntimeGunTaskHandler$Task")}, reload);
                check((boolean) get(admitted, "startedNow"), "Native reload task is admitted");
                check(blocked(), "Reload retains native fire lock");
                check(!"ALLOW_FIRE".equals(intent(mc)), "Native fire-intent gate refuses fire during reload");
                reloadBulletBaseline = bullets.get();
                mc.options.keyAttack.setDown(true);
                change(Phase.RELOAD_LOCK);
            }
            case RELOAD_LOCK -> {
                if (age < 250) {
                    check(blocked(), "Reload lock is preserved throughout first 250 ms");
                    check(bullets.get() == reloadBulletBaseline, "No real bullet during reload lock");
                    return;
                }
                mc.options.keyAttack.setDown(false);
                check(bullets.get() == reloadBulletBaseline, "Blocked left attack remains unable to fire");
                check(serverAmmo(mc, 2) == 29, "Reload-blocked input consumes no server ammo");
                if (age < 450) check(blocked(), "600 ms reload was not accelerated by switch hooks");
                if (age < 700 || !ready(mc, 2)) return;
                cadenceBulletBaseline = bullets.get();
                // This separate server-only probe tests the unchanged native cooldown transaction.
                mc.getSingleplayerServer().submit(() -> {
                    try {
                        var player = serverPlayer(mc);
                        ItemStack stack = player.getMainHandItem();
                        Method shot = stack.getItem().getClass().getMethod("triggerShot", ServerPlayer.class, ItemStack.class, int.class);
                        check((boolean) shot.invoke(stack.getItem(), player, stack, 0), "First native server cadence shot accepted");
                        int after = ammo(stack);
                        check(!(boolean) shot.invoke(stack.getItem(), player, stack, 0), "Immediate repeat rejected by actual GWO cooldown");
                        check(ammo(stack) == after && after == 28, "Rejected immediate repeat consumes no ammo");
                    } catch (Exception e) { throw new RuntimeException(e); }
                }).join();
                check(bullets.get() == cadenceBulletBaseline + 1, "Native server cooldown permits exactly one immediate shot");
                change(Phase.SERVER_CADENCE_WAIT);
            }
            case SERVER_CADENCE_WAIT -> {
                if (age < 150) return;
                mc.getSingleplayerServer().submit(() -> {
                    try {
                        var player = serverPlayer(mc);
                        ItemStack stack = player.getMainHandItem();
                        Method shot = stack.getItem().getClass().getMethod("triggerShot", ServerPlayer.class, ItemStack.class, int.class);
                        check((boolean) shot.invoke(stack.getItem(), player, stack, 0), "Native server shot accepted after unchanged 74 ms cadence");
                        check(ammo(stack) == 27, "Post-cooldown shot consumes exactly one server round");
                    } catch (Exception e) { throw new RuntimeException(e); }
                }).join();
                check(bullets.get() == cadenceBulletBaseline + 2, "Two accepted server cadence shots, one rejected repeat");
                change(Phase.FINISH);
            }
            case FINISH -> {
                if (age < 200) return;
                check(ready(mc, 2), "No stale draw task or equip lock remains after all shots");
                assertServerSelected(mc, 2);
                Files.writeString(OUTPUT.resolve("frames.csv"), frames);
                String result = "GWO_FAST_SWITCH_SMOKE_PASS assertions=" + assertions
                        + " native_hand_timeline_samples=" + acceleratedSamples + " frames=" + frameCount
                        + " switch_ready_ms=" + switchReadyMs + " rapid_ready_ms=" + rapidReadyMs
                        + " first_native_shot_after_ready_ms=" + firstShotDelay + " server_bullets=" + bullets.get()
                        + " native_packet_ammo=true native_reload_lock=true native_cadence=true"
                        + " clipped_draw=true protected_graphics_verified=false";
                Files.writeString(OUTPUT.resolve("result.txt"), result + "\n");
                LoggerFactory.getLogger("GwoFastSwitchSmoke").info(result);
                change(Phase.DONE);
                stopped = true;
                mc.stop();
            }
            case DONE -> { }
        }
    }

    private static void installFixture(Minecraft mc) throws Exception {
        playerId = mc.player.getUUID();
        Class<?> definitionType = type("content.GunDefinition");
        Class<?> firearmType = type("content.FirearmDefinition");
        Map<ResourceLocation, Object> weapons = new LinkedHashMap<>();
        for (int slot = 0; slot < IDS.length; slot++) {
            JsonObject json = JsonParser.parseString(fixture(slot)).getAsJsonObject();
            Files.writeString(OUTPUT.resolve(IDS[slot].getPath() + ".json"), json.toString());
            Object definition = definitionType.getMethod("fromJson", JsonObject.class).invoke(null, json);
            DEFINITIONS.put(IDS[slot], definition);
            weapons.put(IDS[slot], firearmType.getConstructor(definitionType).newInstance(definition));
            check(get(definition, "gltfModel") == null, "Fixture does not access protected graphics");
        }
        stat("content.WeaponContentRegistry", "installBootstrapDefinitions", new Class<?>[] {Map.class}, weapons);
        runtime = singleton("client.runtime.RuntimeGunClient");
        tasks = get(runtime, "tasks");
        animations = get(runtime, "animations");
        draw = singleton("client.animation.GunDrawHolsterHandler");
        intent = type("client.ClientEvents$GameBus").getDeclaredMethod("clientIntentToFire", ItemStack.class, definitionType);
        intent.setAccessible(true);
        NeoForge.EVENT_BUS.addListener(GwoFastSwitchSmoke::onBullet);
        mc.getSingleplayerServer().submit(() -> {
            try {
                var player = serverPlayer(mc);
                player.getInventory().clearContent();
                for (int slot = 0; slot < 3; slot++) {
                    ItemStack gun = (ItemStack) stat("GwoMod", "weaponStack", new Class<?>[] {ResourceLocation.class}, IDS[slot]);
                    stat("item.GunData", "initialize", new Class<?>[] {ItemStack.class, ResourceLocation.class, definitionType},
                            gun, IDS[slot], DEFINITIONS.get(IDS[slot]));
                    stat("item.GunData", "setAmmo", new Class<?>[] {ItemStack.class, int.class}, gun, 30);
                    player.getInventory().setItem(slot, gun);
                }
                player.getInventory().selected = 0;
                player.inventoryMenu.broadcastChanges();
                player.serverLevel().setDayTime(6000);
            } catch (Exception e) { throw new RuntimeException(e); }
        }).join();
        mc.player.getInventory().selected = 0;
        mc.player.setXRot(-10);
        mc.player.setYRot(0);
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    private static String fixture(int slot) {
        String raise = slot == 2
                ? "\"raise\":{\"clip\":\"raise\",\"layer\":\"hand_action\",\"duration\":1.0,\"clip_start_time\":0.2,\"clip_end_time\":0.8}"
                : "\"raise\":{\"clip\":\"raise\",\"layer\":\"hand_action\",\"duration\":0.8},"
                  + "\"raise_first\":{\"clip\":\"raise_first\",\"layer\":\"hand_action\",\"duration\":0.9}";
        String variants = slot == 2 ? "" : ",\"variants\":[{\"state\":\"raise_first\",\"priority\":100,\"when\":{\"first_draw\":\"true\"}}]";
        return """
                {
                  "display_name":"Native GWO switch QA",
                  "weapon_type":"firearm","magazine_size":30,"damage":8,"range":96,
                  "fire_modes":["semi"],
                  "mechanics":{"rpm":811,"fire_interval_ms":74,"action_commit_ms":{"reload":700}},
                  "ballistics":{"muzzle_velocity":100,"gravity":0,"life_seconds":2.5},
                  "animation_controller":{"channels":{
                    %s,
                    "drop":{"clip":"drop","layer":"hand_action","duration":0.7},
                    "drop_empty":{"clip":"drop_empty","layer":"hand_action","duration":0.25},
                    "reload":{"clip":"reload","layer":"action","duration":1.2,"lock_fire":true},
                    "fire":{"clip":"fire","layer":"recoil","duration":0.08,"lock_fire":false},
                    "idle":{"clip":"idle","layer":"base","loop":true}
                  }},
                  "animation_machine":{"version":2,"actions":{
                    "raise":{"type":"finite","default_state":"raise"%s},
                    "drop":{"type":"finite","default_state":"drop"},
                    "reload":{"type":"finite","default_state":"reload"},
                    "fire":{"type":"finite","default_state":"fire","pre_fire_mode":"presentation"}
                  },"interrupts":[]},
                  "bullet_tracer":{"enabled":false},"first_person_arms":false
                }
                """.formatted(raise, variants);
    }

    private static void verifyNativeTiming() throws Exception {
        Object a = DEFINITIONS.get(IDS[0]);
        Object c = DEFINITIONS.get(IDS[2]);
        near(duration("raise", a), .8f, "Bare native resolver retains the canonical shared clip duration");
        try (var scope = FastSwitchActionScope.enter("raise")) {
            near(duration("raise", a), .4f, "Native ordinary raise resolver is 2x faster within draw action");
            near(duration("raise_first", a), .45f, "Native first raise resolver is 2x faster within draw action");
            near(duration("raise", c), .3f, "Clipped raise uses (.8 - .2) / 2 seconds within draw action");
        }
        try (var scope = FastSwitchActionScope.enter("drop")) {
            near(duration("drop", a), .35f, "Native drop resolver is 2x faster within holster action");
            near(duration("drop_empty", a), .125f, "Native empty drop resolver is 2x faster within holster action");
        }
        try (var scope = FastSwitchActionScope.enter("reload")) {
            near(duration("raise", a), .8f, "A reload using a shared raise clip remains at original speed");
        }
        near(duration("reload", a), 1.2f, "Reload duration remains unchanged");
        near(duration("fire", a), .08f, "Fire duration remains unchanged");
        check((int) get(a, "shotCooldownMs") == 74, "Original server shot cadence remains 74 ms");
        Object channel = call(get(a, "animationController"), "channel", new Class<?>[] {String.class}, "raise");
        near(((Number) get(channel, "speed")).floatValue(), 1f, "Original definition channel speed remains untouched");
    }

    @SubscribeEvent
    public static void frame(RenderGuiEvent.Post event) {
        if (!installed || stopped || Minecraft.getInstance().player == null) return;
        try {
            var mc = Minecraft.getInstance();
            Object sequence = call(animations, "channel", new Class<?>[] {String.class}, "hand_action");
            String clip = sequence == null ? "" : (String) get(sequence, "clipName");
            float time = sequence == null ? 0 : ((Number) get(sequence, "timeSeconds")).floatValue();
            float progress = sequence == null ? 0 : ((Number) get(sequence, "progress")).floatValue();
            long sampleNow = now();
            if (sequence != null && sequence == lastSequence && sampleNow - lastSampleAt >= 35
                    && sampleNow - lastSampleAt <= 150 && time >= lastSampleTime
                    && (clip.startsWith("raise") || clip.startsWith("drop")) && progress < .9f) {
                float speed = (time - lastSampleTime) * 1000f / (sampleNow - lastSampleAt);
                check(speed > 1.7f && speed < 2.35f, "Actual native hand-action sample playback is 2x: " + speed);
                acceleratedSamples++;
            }
            if (sequence != lastSequence || sampleNow - lastSampleAt >= 35) {
                lastSequence = sequence;
                lastSampleTime = time;
                lastSampleAt = sampleNow;
            }
            frames.append(frameCount++).append(',').append(phase).append(',').append(sampleNow - phaseStarted)
                    .append(',').append(mc.player.getInventory().selected).append(',').append(blocked())
                    .append(',').append(equip()).append(',').append(clip).append(',').append(time)
                    .append(',').append(progress).append('\n');
        } catch (Throwable error) { fail(error); }
    }

    private static void onBullet(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType())
                .equals(ResourceLocation.parse("gwo:bullet"))) return;
        try {
            Object shooter = get(event.getEntity(), "getShooter");
            if (shooter instanceof ServerPlayer player && player.getUUID().equals(playerId)) bullets.incrementAndGet();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void beginShot(Minecraft mc) throws Exception {
        check("ALLOW_FIRE".equals(intent(mc)), "Native fire-intent gate permits shot after completed switch");
        bulletBaseline = bullets.get();
        mc.options.keyAttack.setDown(true);
    }

    private static boolean ready(Minecraft mc, int slot) throws Exception {
        if (mc.player.getInventory().selected != slot || !IDS[slot].equals(contentId(mc.player.getMainHandItem()))) return false;
        ItemStack locked = (ItemStack) get(draw, "renderLockedStack");
        return IDS[slot].equals(contentId(locked)) && !blocked() && equip() >= .999f
                && "ALLOW_FIRE".equals(intent(mc));
    }

    private static String intent(Minecraft mc) throws Exception {
        Object definition = DEFINITIONS.get(contentId(mc.player.getMainHandItem()));
        return String.valueOf(intent.invoke(null, mc.player.getMainHandItem(), definition));
    }

    private static boolean blocked() throws Exception { return (boolean) get(tasks, "blockShoot"); }
    private static float equip() throws Exception {
        return ((Number) call(draw, "equipProgress", new Class<?>[] {float.class}, 1f)).floatValue();
    }
    private static float duration(String state, Object definition) throws Exception {
        return ((Number) stat("client.animation.AnimationDurationResolver", "duration",
                new Class<?>[] {String.class, type("content.GunDefinition"), float.class}, state, definition, .6f)).floatValue();
    }
    private static int ammo(ItemStack stack) throws Exception {
        return ((Number) stat("item.GunData", "ammo", new Class<?>[] {ItemStack.class}, stack)).intValue();
    }
    private static ResourceLocation contentId(ItemStack stack) throws Exception {
        return (ResourceLocation) stat("item.GunData", "contentId", new Class<?>[] {ItemStack.class}, stack);
    }
    private static ServerPlayer serverPlayer(Minecraft mc) { return mc.getSingleplayerServer().getPlayerList().getPlayer(playerId); }
    private static int serverAmmo(Minecraft mc, int slot) {
        return mc.getSingleplayerServer().submit(() -> {
            try { return ammo(serverPlayer(mc).getInventory().getItem(slot)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }).join();
    }
    private static void assertServerSelected(Minecraft mc, int slot) {
        int actual = mc.getSingleplayerServer().submit(() -> serverPlayer(mc).getInventory().selected).join();
        check(actual == slot, "Selected slot reaches actual server: " + actual + " == " + slot);
    }
    private static void key(Minecraft mc, int slot) {
        mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), 49 + slot, 0, 1, 0);
        mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), 49 + slot, 0, 0, 0);
        check(mc.player.getInventory().selected == slot, "Existing number-key path selects requested slot immediately");
    }
    private static Object singleton(String name) throws Exception {
        Field field = type(name).getField("INSTANCE");
        return field.get(null);
    }
    private static Class<?> type(String name) throws ClassNotFoundException {
        Class<?> cached = TYPES.get(name);
        if (cached == null) { cached = Class.forName(PREFIX + name); TYPES.put(name, cached); }
        return cached;
    }
    private static Object stat(String name, String method, Class<?>[] parameters, Object... args) throws Exception {
        return type(name).getMethod(method, parameters).invoke(null, args);
    }
    private static Object call(Object object, String method, Class<?>[] parameters, Object... args) throws Exception {
        return object.getClass().getMethod(method, parameters).invoke(object, args);
    }
    private static Object get(Object object, String method) throws Exception { return call(object, method, new Class<?>[0]); }
    private static long now() { return System.currentTimeMillis(); }
    private static void change(Phase next) { phase = next; phaseStarted = now(); phaseTick = ticks; }
    private static void near(float actual, float expected, String message) { check(Math.abs(actual - expected) < .002f, message + ": " + actual); }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void fail(Throwable error) {
        if (stopped) return;
        stopped = true;
        Minecraft.getInstance().options.keyAttack.setDown(false);
        LoggerFactory.getLogger("GwoFastSwitchSmoke").error("GWO_FAST_SWITCH_SMOKE_FAIL phase=" + phase + " assertions=" + assertions, error);
        try {
            Files.createDirectories(OUTPUT);
            Files.writeString(OUTPUT.resolve("frames.csv"), frames);
            Files.writeString(OUTPUT.resolve("result.txt"), "GWO_FAST_SWITCH_SMOKE_FAIL phase=" + phase + "\n" + error + "\n");
        } catch (Exception ignored) { }
        Minecraft.getInstance().stop();
    }
}
