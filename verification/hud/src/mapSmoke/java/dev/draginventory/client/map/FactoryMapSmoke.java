package dev.draginventory.client.map;

import dev.draginventory.client.TacticalMarker;
import dev.draginventory.client.TacticalMarkerManager;
import java.nio.file.Path;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

/**
 * Opt-in isolated UI fixture. The factory is a generated QA scene, not a shipped map.
 * Adapted to the v2.5.3 layout: the map opens with the full HUD (v2.5.1+ default
 * for clean_on_open=false; H toggles clean mode, pressed twice in this run to
 * exercise both states), the legend + marker-management popover slides out
 * from the LEFT edge (8px margin, anchored to the first 18px toolbar button on
 * the right rail), ping types switch with keys 1/2/3 and are placed by
 * right-click, and floors follow the player automatically (v2.5.2 removed the
 * manual PgUp/PgDn/Home floor keys with the manual-floor feature itself).
 * Toolbar geometry is asserted at guiScale 2 and 4; closing still releases the
 * per-screen tile pool.
 */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class FactoryMapSmoke {
    private static boolean started, built;
    private static long deadline, last;
    private static int tick;
    private static String capture;
    private static FactoryMapSession state;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        var mc = Minecraft.getInstance();
        if (!started && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            started = true; deadline = Util.getMillis() + 180000;
            mc.getWindow().setWindowed(1600, 900);
            mc.options.guiScale().set(2); mc.options.renderDistance().set(8);
            mc.options.pauseOnLostFocus = false; mc.resizeDisplay();
            var rules = new GameRules(); rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            mc.createWorldOpenFlows().createFreshLevel("map-ui-" + System.currentTimeMillis(),
                    new LevelSettings("Factory map UI QA", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(1, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
        }
        if (!started) return;
        if (Util.getMillis() > deadline) throw new AssertionError("Map smoke timed out");
        if (mc.level == null || mc.player == null || mc.getOverlay() != null) return;
        if (!built && mc.screen == null) {
            built = true; var uuid = mc.player.getUUID();
            mc.getSingleplayerServer().submit(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                var level = player.serverLevel(); player.teleportTo(0, -60, 0);
                for (int x = -95; x < 96; x++) for (int z = -80; z < 81; z++) {
                    BlockState ground = (Math.abs(x) < 6 || Math.abs(z) < 6 || Math.abs(x) > 86 || Math.abs(z) > 72)
                            ? Blocks.GRAY_CONCRETE.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
                    level.setBlock(new BlockPos(x, -61, z), ground, 2);
                    if ((x == 0 && z % 7 < 3) || (z == 0 && x % 7 < 3))
                        level.setBlock(new BlockPos(x, -61, z), Blocks.YELLOW_CONCRETE.defaultBlockState(), 2);
                }
                for (int bx : new int[] {-76, -35, 16, 57}) for (int bz : new int[] {-61, 19}) {
                    for (int x = bx; x < bx + 27; x++) for (int z = bz; z < bz + 36; z++) {
                        level.setBlock(new BlockPos(x, -61, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                        for (int y = -60; y < -51; y++) {
                            boolean wall = x == bx || x == bx + 26 || z == bz || z == bz + 35;
                            boolean door = z == bz + 35 && x > bx + 10 && x < bx + 16 && y < -56;
                            if (wall && !door || y == -52)
                                level.setBlock(new BlockPos(x, y, z), (y == -52 && x % 4 == 0 ? Blocks.POLISHED_ANDESITE : Blocks.LIGHT_GRAY_CONCRETE).defaultBlockState(), 2);
                        }
                    }
                }
                for (int x = -80; x < -12; x += 10) for (int z = -17; z < -7; z++) for (int a = 0; a < 7; a++)
                    for (int y = -60; y < -57; y++) level.setBlock(new BlockPos(x + a, y, z), Blocks.CYAN_TERRACOTTA.defaultBlockState(), 2);
            }).join();
        }
        if (!built || Util.getMillis() - last < 50) return;
        last = Util.getMillis(); tick++;
        if (tick == 80) {
            // Opens with the full HUD (v2.5.1+ default: clean_on_open=false).
            mc.setScreen(FactoryMapScreen.create());
            var field = FactoryMapScreen.class.getDeclaredField("session"); field.setAccessible(true);
            state = (FactoryMapSession) field.get(mc.screen);
            state.zoomAt(400, 225, 2, 0, 0, 800, 450);
            TacticalMarkerManager.placeMapLocation(new Vec3(-24, -51, -38));
            TacticalMarkerManager.placeMapLocation(new Vec3(32, -51, 38));
            // Fixture-only enemy and item symbols exercise all marker glyph colours.
            var mapField = TacticalMarkerManager.class.getDeclaredField("MARKERS"); mapField.setAccessible(true);
            @SuppressWarnings("unchecked") var markers = (java.util.Map<Object, TacticalMarker>) mapField.get(null);
            markers.put("qa-enemy", new TacticalMarker(TacticalMarker.Type.ENEMY, new Vec3(28, -51, -44), null, Util.getMillis()));
            markers.put("qa-item", new TacticalMarker(TacticalMarker.Type.ITEM, new Vec3(-28, -57, -12), null, Util.getMillis()));
        }
        if (!(mc.screen instanceof FactoryMapScreen screen)) return;
        if (tick == 125) {
            check(!cleanMode(screen), "opens in full HUD (v2.5.1+ default)");
            check(TacticalMarkerManager.snapshot(1).size() == 4, "fixture markers");
            capture = "factory_map_hud.png";
        }
        if (tick == 130) {
            double before = state.zoom();
            screen.mouseScrolled(400, 240, 0, 1);
            check(state.zoom() > before, "zoom");
            var beforeWorld = state.transform(0, 0, 800, 450).screenToWorld(400, 240);
            screen.mouseClicked(400, 240, 0); screen.mouseDragged(415, 250, 0, 15, 10); screen.mouseReleased(415, 250, 0);
            var after = state.transform(0, 0, 800, 450).screenToWorld(415, 250);
            check(Math.abs(after.x() - beforeWorld.x()) < .001 && Math.abs(after.z() - beforeWorld.z()) < .001, "pan anchor");
            // The old left-panel area is plain map body in both modes: a right-click
            // there places a ping (no panel intercepts it since v2.5.0 removed the panel).
            int count = TacticalMarkerManager.snapshot(1).size();
            screen.mouseClicked(70, 110, 1);
            check(TacticalMarkerManager.snapshot(1).size() == count + 1, "map body ping");
            screen.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0);
            check(state.activeLayer() != null, "automatic layer discovery");
        }
        if (tick == 150) {
            screen.keyPressed(GLFW.GLFW_KEY_H, 0, 0);
            check(cleanMode(screen), "H toggles clean mode");
            capture = "factory_map_clean.png";
        }
        if (tick == 152) {
            screen.keyPressed(GLFW.GLFW_KEY_H, 0, 0);
            check(!cleanMode(screen), "H toggles back to full HUD");
        }
        if (tick == 155) {
            // First 18px toolbar button (canvasW-26, 34): legend + marker management popover.
            screen.mouseClicked(800 - 26 + 9, 34 + 9, 0);
            check(popoverOpen(screen), "legend button opens popover");
            capture = "factory_map_legend.png";
        }
        if (tick == 160) {
            // Keys 1/2/3 switch the ping type; right-click places it (FIFO caps at 5).
            placePing(screen, GLFW.GLFW_KEY_2, "enemy", TacticalMarker.Type.ENEMY, 400, 300);
            placePing(screen, GLFW.GLFW_KEY_3, "item", TacticalMarker.Type.ITEM, 420, 310);
            placePing(screen, GLFW.GLFW_KEY_1, "location", TacticalMarker.Type.LOCATION, 440, 320);
            capture = "factory_map_pings.png";
        }
        if (tick == 165) {
            screen.mouseClicked(800 - 26 + 9, 34 + 9, 0);
            check(!popoverOpen(screen), "legend button closes popover");
            // v2.5.2: manual floor keys (PgUp/PgDn/Home) are gone with the manual-floor
            // feature; the floor follows the player and stays on the fixture floor.
            int floor = state.selectedFloorY();
            screen.keyPressed(GLFW.GLFW_KEY_PAGE_UP, 0, 0);
            screen.keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0);
            check(state.selectedFloorY() == floor, "auto floor ignores removed manual keys");
            capture = "factory_map_floor.png";
        }
        if (tick == 170) {
            // Auto floor keeps following the player across ticks (no manual lock state).
            check(state.activeLayer() != null, "auto floor stays active");
        }
        if (tick == 200) {
            mc.options.guiScale().set(4); mc.resizeDisplay();
        }
        if (tick == 220) {
            // Rescaled controls still receive the same logical click: the 18px center
            // toolbar button (canvasW-26, y=78) recenters the session after a pan.
            float s = Math.min(1f, Math.min(screen.width / 640f, screen.height / 360f));
            int cw = Math.round(screen.width / s), ch = Math.round(screen.height / s);
            state.panPixels(120, 90);
            screen.mouseClicked((cw - 26 + 9) * s, (78 + 9) * s, 0);
            var center = state.transform(0, 0, cw, ch).screenToWorld(cw / 2.0, ch / 2.0);
            check(Math.abs(center.x() - mc.player.getX()) < .01
                    && Math.abs(center.z() - mc.player.getZ()) < .01, "scaled center hit");
            capture = "factory_map_scale4.png";
        }
        if (tick == 225) {
            // Closing releases the per-screen tile pool (terrain.close()); reopening
            // must rebuild cleanly. Config flushing is owned by MapConfig, not asserted here.
            screen.onClose(); mc.setScreen(FactoryMapScreen.create());
        }
        if (tick == 255) {
            check(mc.screen instanceof FactoryMapScreen, "reopen after tile release");
            screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); check(mc.screen == null, "escape closes");
            LoggerFactory.getLogger("FactoryMapSmoke").info("FACTORY_MAP_SMOKE_PASS: auto-layer, dynamic tiles, zoom, drag, clean mode, H toggle, legend popover, ping types 1/2/3, auto floor keys-removed, scale4 toolbar, reopen");
            mc.stop();
        }
    }

    /** Press a digit key, assert MARKER_PING_TYPE, then right-click a ping and assert the newest marker type. */
    private static void placePing(FactoryMapScreen screen, int key, String expectedId,
            TacticalMarker.Type expectedType, double x, double y) {
        screen.keyPressed(key, 0, 0);
        check(expectedId.equals(MapConfig.MARKER_PING_TYPE.get()), "key selects " + expectedId + " ping type");
        screen.mouseClicked(x, y, 1);
        var markers = TacticalMarkerManager.snapshot(1);
        check(markers.size() <= 5, "marker fifo cap");
        check(!markers.isEmpty() && markers.get(markers.size() - 1).type() == expectedType,
                expectedId + " ping placed");
    }

    /** Reflection read of the private cleanMode flag (same pattern as the session field). */
    private static boolean cleanMode(FactoryMapScreen screen) throws ReflectiveOperationException {
        var field = FactoryMapScreen.class.getDeclaredField("cleanMode");
        field.setAccessible(true);
        return field.getBoolean(screen);
    }

    /** Reflection read of the private popoverOpen flag. */
    private static boolean popoverOpen(FactoryMapScreen screen) throws ReflectiveOperationException {
        var field = FactoryMapScreen.class.getDeclaredField("popoverOpen");
        field.setAccessible(true);
        return field.getBoolean(screen);
    }

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    @SubscribeEvent public static void rendered(ScreenEvent.Render.Post event) throws Exception {
        if (capture == null || !(event.getScreen() instanceof FactoryMapScreen)) return;
        event.getGuiGraphics().flush();
        try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) { image.writeToFile(Path.of(capture)); }
        capture = null;
    }
}
