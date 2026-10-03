package dev.draginventory.client.wheel;

import dev.draginventory.client.ReferenceHotbar;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
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
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

/** Opt-in real-client and integrated-server fixture; excluded from release builds. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class WheelSmoke {
    private enum Phase {
        SETUP, OPENING, HOVER, ALT_WAIT, SCROLL, CLOSING,
        SCROLL_RELEASE_OPEN, SCROLL_RELEASE_STABLE, SCROLL_RELEASE_WAIT, SLOT_OPEN, SLOT_WAIT,
        RIGHT_OPEN, RIGHT_WAIT, ESC_OPEN, ESC_WAIT, CLOSE_OPEN, CLOSE_WAIT, REPLACE_OPEN, REPLACE_WAIT,
        CENTER_OPEN, CENTER_WAIT, CENTER_CLICK_OPEN, CENTER_CLICK_WAIT, EMPTY_CENTER_OPEN, EMPTY_CENTER_WAIT,
        FALLBACK_OPEN, FALLBACK_WAIT, SCALE_SETUP, SCALE_OPEN, SCALE_WAIT, DONE
    }

    private static final Path CAPTURE_DIRECTORY = Path.of("wheel-smoke");
    private static final List<String> captures = new ArrayList<>();
    private static boolean started, built, failed;
    private static boolean openingCaptured, openCaptured, hoverCaptured, scrollCaptured, closingCaptured, scaleCaptured;
    private static boolean openingInterpolated, hoverInterpolated, scrollInterpolated, closingInterpolated;
    private static long deadline, phaseStarted;
    private static int tick, phaseTick, testSlot = 4, animationFrames;
    private static float cameraYaw, cameraPitch;
    private static Phase phase = Phase.SETUP;
    private static TestingWheelScreen screen;
    private static final StringBuilder animationCsv = new StringBuilder(
            "frame,phase,phase_ms,alpha,scale,rotation,highlight0,highlight1,highlight2,highlight3,highlight4,pulse\n");

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (failed || phase == Phase.DONE) return;
        try {
            advance(Minecraft.getInstance());
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static void advance(Minecraft mc) throws Exception {
        if (!started && mc.getOverlay() == null
                && mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) {
            mc.screen.onClose();
            return;
        }
        if (!started && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            started = true;
            deadline = Util.getMillis() + 180_000;
            Files.createDirectories(CAPTURE_DIRECTORY);
            mc.getWindow().setWindowed(1280, 720);
            mc.options.guiScale().set(2);
            mc.options.renderDistance().set(2);
            mc.options.pauseOnLostFocus = false;
            mc.resizeDisplay();
            var rules = new GameRules();
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            mc.createWorldOpenFlows().createFreshLevel("wheel-ui-" + System.currentTimeMillis(),
                    new LevelSettings("Pocket wheel UI QA", GameType.SURVIVAL, false,
                            Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(1, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                            .value().createWorldDimensions(), mc.screen);
        }
        if (!started) return;
        check(Util.getMillis() <= deadline, "Wheel smoke timed out in " + phase);
        if (mc.level == null || mc.player == null || mc.getOverlay() != null) return;
        if (!built && mc.screen == null) {
            built = true;
            var playerId = mc.player.getUUID();
            var server = mc.getSingleplayerServer();
            server.submit(() -> {
                var player = server.getPlayerList().getPlayer(playerId);
                var inventory = player.getInventory();
                inventory.clearContent();
                inventory.setItem(0, new ItemStack(Items.IRON_SWORD));
                inventory.setItem(4, new ItemStack(Items.DIAMOND, 64));
                inventory.setItem(5, new ItemStack(Items.ENDER_PEARL, 12));
                inventory.setItem(6, new ItemStack(Items.BREAD, 32));
                inventory.setItem(7, new ItemStack(Items.IRON_PICKAXE));
                inventory.setItem(8, new ItemStack(Items.GOLDEN_APPLE, 16));
                inventory.selected = 4;
                player.inventoryMenu.broadcastChanges();
                player.serverLevel().setDayTime(6000);
            }).join();
            mc.player.getInventory().selected = 4;
            mc.player.setYRot(0);
            mc.player.setXRot(12);
            changePhase(Phase.SETUP);
        }
        if (!built) return;
        tick++;
        long age = Util.getMillis() - phaseStarted;
        int ticksInPhase = tick - phaseTick;
        switch (phase) {
            case SETUP -> {
                if (ticksInPhase < 30) return;
                check(mc.player.getInventory().getItem(4).getCount() == 64, "Server diamond stack sync");
                check(mc.player.getInventory().getItem(5).getCount() == 12, "Server pearl stack sync");
                check(mc.player.getInventory().getItem(6).getCount() == 32, "Server bread stack sync");
                check(mc.player.getInventory().getItem(8).getCount() == 16, "Server apple stack sync");
                mc.mouseHandler.grabMouse();
                cameraYaw = mc.player.getYRot();
                cameraPitch = mc.player.getXRot();
                // A press and release within one client tick must never open the wheel.
                // The pure gesture tests separately verify its 50 ms hold threshold.
                NeoForge.EVENT_BUS.post(new InputEvent.Key(GLFW.GLFW_KEY_LEFT_ALT,
                        GLFW.glfwGetKeyScancode(GLFW.GLFW_KEY_LEFT_ALT), GLFW.GLFW_PRESS, 0));
                NeoForge.EVENT_BUS.post(new InputEvent.Key(GLFW.GLFW_KEY_LEFT_ALT,
                        GLFW.glfwGetKeyScancode(GLFW.GLFW_KEY_LEFT_ALT), GLFW.GLFW_RELEASE, 0));
                check(mc.screen == null, "Same-tick quick Alt tap does not open pocket through input events");
                open(mc, 4);
                changePhase(Phase.OPENING);
            }
            case OPENING -> {
                check(mc.screen == screen && !screen.isPauseScreen(), "Pocket stays open without pausing");
                check(!mc.mouseHandler.isMouseGrabbed(), "Wheel releases native mouse capture");
                check(screen.getTitle().getString().equals("口袋"), "Pocket title has exactly two Chinese characters");
                check(Math.abs(WheelClient.animation().frame(Util.getMillis()).rotation()) < .0001f,
                        "Opening has no whole-wheel rotation");
                if (age >= 280 && openingCaptured && openCaptured) {
                    hover(mc, 8);
                    changePhase(Phase.HOVER);
                }
            }
            case HOVER -> {
                if (age >= 280 && hoverCaptured) {
                    releaseAlt(mc);
                    changePhase(Phase.ALT_WAIT);
                }
            }
            case ALT_WAIT -> {
                if (ticksInPhase < 3) return;
                check(mc.screen == null, "Pocket stays closed across ticks after Alt release");
                assertSelected(mc, 8);
                check(mc.mouseHandler.isMouseGrabbed(), "Alt release restores game mouse control");
                open(mc, 4);
                check(WheelClient.animation().hovered() == 0, "Pointer selects active slot before scrolling");
                screen.mouseScrolled(pointerGuiX(mc), pointerGuiY(mc), 0, -1);
                check(WheelClient.animation().hovered() == 1, "Wheel down selects the next sector, matching the hotbar");
                changePhase(Phase.SCROLL);
            }
            case SCROLL -> {
                check(mc.screen == screen, "Scroll keeps pocket open");
                check(WheelClient.animation().hovered() == 1,
                        "Stationary pointer preserves scroll selection while wheel rotates");
                if (age >= 280 && scrollCaptured) {
                    assertSelected(mc, 8, false);
                    screen.mouseScrolled(pointerGuiX(mc), pointerGuiY(mc), 0, -5);
                    check(WheelClient.animation().hovered() == 1, "Five wheel-down steps wrap to the same sector");
                    screen.mouseScrolled(pointerGuiX(mc), pointerGuiY(mc), 0, 1);
                    check(WheelClient.animation().hovered() == 0, "Wheel up selects the previous sector, matching the hotbar");
                    screen.mouseScrolled(pointerGuiX(mc), pointerGuiY(mc), 0, -1);
                    check(WheelClient.animation().hovered() == 1, "Wheel down restores the next sector");
                    clickLeft(mc, 5);
                    changePhase(Phase.CLOSING);
                }
            }
            case CLOSING -> {
                if (ticksInPhase >= 5 && closingCaptured) {
                    assertSelected(mc, 5);
                    check(Math.abs(mc.player.getYRot() - cameraYaw) < .01f
                            && Math.abs(mc.player.getXRot() - cameraPitch) < .01f,
                            "Pointer movement and capture restoration preserve camera orientation");
                    open(mc, 4);
                    changePhase(Phase.SCROLL_RELEASE_OPEN);
                }
            }
            case SCROLL_RELEASE_OPEN -> {
                if (age >= 240) {
                    screen.mouseScrolled(pointerGuiX(mc), pointerGuiY(mc), 0, -2);
                    check(WheelClient.animation().hovered() == 2, "Two wheel-down steps preview sector 2 before Alt confirmation");
                    changePhase(Phase.SCROLL_RELEASE_STABLE);
                }
            }
            case SCROLL_RELEASE_STABLE -> {
                check(WheelClient.animation().hovered() == 2,
                        "Stationary pointer retains scroll preview until Alt release");
                if (age >= 280) {
                    assertSelected(mc, 5, false);
                    releaseAlt(mc);
                    check(mc.player.getInventory().selected == 6,
                            "Alt release immediately commits scroll-selected sector without a left click");
                    changePhase(Phase.SCROLL_RELEASE_WAIT);
                }
            }
            case SCROLL_RELEASE_WAIT -> {
                if (ticksInPhase < 5) return;
                assertSelected(mc, 6);
                open(mc, testSlot);
                changePhase(Phase.SLOT_OPEN);
            }
            case SLOT_OPEN -> {
                if (age >= 240) {
                    hoverFar(mc, testSlot);
                    check(WheelClient.animation().hovered() == testSlot - WheelGeometry.FIRST_SLOT,
                            "Pointer outside the wheel selects direction sector for slot " + testSlot);
                    clickLeft(mc, testSlot);
                    changePhase(Phase.SLOT_WAIT);
                }
            }
            case SLOT_WAIT -> {
                if (ticksInPhase < 5) return;
                assertSelected(mc, testSlot);
                if (++testSlot <= 8) {
                    open(mc, testSlot);
                    changePhase(Phase.SLOT_OPEN);
                } else {
                    open(mc, 4);
                    changePhase(Phase.RIGHT_OPEN);
                }
            }
            case RIGHT_OPEN -> {
                if (age >= 240) {
                    screen.mouseClicked(pointerGuiX(mc), pointerGuiY(mc), 1);
                    check(mc.screen == null && !WheelClient.isWheelOpen(), "Right click closes and releases screen owner");
                    screen.held = false;
                    screen.keyReleased(GLFW.GLFW_KEY_LEFT_ALT, 0, 0);
                    screen.tick();
                    NeoForge.EVENT_BUS.post(new InputEvent.Key(GLFW.GLFW_KEY_LEFT_ALT,
                            GLFW.glfwGetKeyScancode(GLFW.GLFW_KEY_LEFT_ALT), GLFW.GLFW_RELEASE, 0));
                    check(mc.screen == null && !WheelClient.isWheelOpen(),
                            "Alt release after right-click cancellation neither reopens nor commits");
                    changePhase(Phase.RIGHT_WAIT);
                }
            }
            case RIGHT_WAIT -> {
                if (ticksInPhase < 5) return;
                assertSelected(mc, 8);
                open(mc, 5);
                changePhase(Phase.ESC_OPEN);
            }
            case ESC_OPEN -> {
                if (age >= 240) {
                    screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                    check(mc.screen == null, "Escape closes the wheel");
                    changePhase(Phase.ESC_WAIT);
                }
            }
            case ESC_WAIT -> {
                if (ticksInPhase < 5) return;
                assertSelected(mc, 8);
                open(mc, 5);
                changePhase(Phase.CLOSE_OPEN);
            }
            case CLOSE_OPEN -> {
                if (age >= 240) {
                    screen.onClose();
                    check(mc.screen == null, "onClose cancels wheel");
                    changePhase(Phase.CLOSE_WAIT);
                }
            }
            case CLOSE_WAIT -> {
                if (ticksInPhase < 5) return;
                assertSelected(mc, 8);
                open(mc, 6);
                changePhase(Phase.REPLACE_OPEN);
            }
            case REPLACE_OPEN -> {
                if (age >= 240) {
                    mc.setScreen(new ReplacementScreen());
                    changePhase(Phase.REPLACE_WAIT);
                }
            }
            case REPLACE_WAIT -> {
                if (ticksInPhase < 5) return;
                assertSelected(mc, 8, false);
                mc.setScreen(null);
                mc.mouseHandler.grabMouse();
                open(mc, 6);
                movePointer(mc, screen.width / 2d, screen.height / 2d);
                changePhase(Phase.CENTER_OPEN);
            }
            case CENTER_OPEN -> {
                if (age >= 240) {
                    check(WheelClient.animation().hovered() == 2, "Center retains previously selected sector");
                    checkPhysicalDeadzone(mc, 6);
                    movePointer(mc, screen.width / 2d, screen.height / 2d);
                    check(WheelClient.animation().hovered() == 2, "Returning to center retains the restored sector");
                    releaseAlt(mc);
                    check(mc.player.getInventory().selected == 6,
                            "Alt release inside deadzone commits the retained direction selection");
                    changePhase(Phase.CENTER_WAIT);
                }
            }
            case CENTER_WAIT -> {
                if (ticksInPhase < 5) return;
                assertSelected(mc, 6);
                open(mc, 5);
                movePointer(mc, screen.width / 2d, screen.height / 2d);
                changePhase(Phase.CENTER_CLICK_OPEN);
            }
            case CENTER_CLICK_OPEN -> {
                if (age >= 240) {
                    check(WheelClient.animation().hovered() == 1, "Center retains sector for left-click confirmation");
                    clickLeft(mc, 5);
                    changePhase(Phase.CENTER_CLICK_WAIT);
                }
            }
            case CENTER_CLICK_WAIT -> {
                if (ticksInPhase < 5) return;
                assertSelected(mc, 5);
                open(mc, -1);
                changePhase(Phase.EMPTY_CENTER_OPEN);
            }
            case EMPTY_CENTER_OPEN -> {
                if (age >= 240) {
                    check(WheelClient.animation().hovered() == -1, "Fresh opening has no direction selection in deadzone");
                    movePhysicalOffset(mc, 14.9, 0);
                    check(WheelClient.animation().hovered() == -1, "Deadzone preserves an initially empty selection");
                    screen.mouseClicked(pointerGuiX(mc), pointerGuiY(mc), 0);
                    check(mc.screen == screen, "Left click without a selected direction leaves pocket open");
                    releaseAlt(mc);
                    check(mc.player.getInventory().selected == 5,
                            "Alt release before any direction selection leaves equipped slot unchanged");
                    changePhase(Phase.EMPTY_CENTER_WAIT);
                }
            }
            case EMPTY_CENTER_WAIT -> {
                if (ticksInPhase < 5) return;
                assertSelected(mc, 5);
                open(mc, 6);
                changePhase(Phase.FALLBACK_OPEN);
            }
            case FALLBACK_OPEN -> {
                if (age >= 240) {
                    screen.held = false;
                    screen.tick();
                    check(mc.screen == null && !WheelClient.isWheelOpen(),
                            "Tick fallback closes the wheel when Alt release callback is absent");
                    check(mc.player.getInventory().selected == 6,
                            "Tick fallback confirms the highlighted sector");
                    changePhase(Phase.FALLBACK_WAIT);
                }
            }
            case FALLBACK_WAIT -> {
                if (ticksInPhase < 5) return;
                assertSelected(mc, 6);
                var server = mc.getSingleplayerServer();
                var playerId = mc.player.getUUID();
                server.submit(() -> {
                    var player = server.getPlayerList().getPlayer(playerId);
                    player.getInventory().setItem(7, ItemStack.EMPTY);
                    player.inventoryMenu.broadcastChanges();
                }).join();
                mc.options.guiScale().set(3);
                mc.resizeDisplay();
                changePhase(Phase.SCALE_SETUP);
            }
            case SCALE_SETUP -> {
                if (ticksInPhase < 5) return;
                check(mc.getWindow().getGuiScale() == 3, "Actual GUI scale changes to three");
                check(mc.player.getInventory().getItem(7).isEmpty(), "Empty inventory slot synchronizes");
                open(mc, 7);
                changePhase(Phase.SCALE_OPEN);
            }
            case SCALE_OPEN -> {
                if (age >= 280 && scaleCaptured) {
                    checkPhysicalDeadzone(mc, 7);
                    movePointer(mc, screen.width / 2d, screen.height / 2d);
                    clickLeft(mc, 7);
                    changePhase(Phase.SCALE_WAIT);
                }
            }
            case SCALE_WAIT -> {
                if (ticksInPhase < 5) return;
                assertSelected(mc, 7);
                checkExistingHotbar();
                check(captures.size() == 6, "All six visual fixtures captured");
                check(openingInterpolated && hoverInterpolated && scrollInterpolated && closingInterpolated,
                        "Real rendered frames show fade, hover, scroll spin and closing interpolation");
                check(animationFrames >= 15, "Wheel animation produced enough real rendered frames");
                Files.writeString(CAPTURE_DIRECTORY.resolve("animation-frames.csv"), animationCsv);
                LoggerFactory.getLogger("WheelSmoke").info(
                        "WHEEL_SMOKE_PASS: same-tick quick Alt tap does not open through input events; pocket title; "
                                + "opening has no spin; Alt release confirms hovered or scrolled sector and syncs server; "
                                + "Alt release tick fallback confirms; full-screen direction, 15px retained deadzone; "
                                + "five far-outside sectors 4..8 and screen corner left-click through actual vanilla server packet; "
                                + "center release and left click confirm retained selection; fresh empty-center release preserves equipped slot; "
                                + "wheel down selects next and wheel up selects previous, matching the hotbar; "
                                + "scroll cycles, wraps and spins with stationary pointer selection; right click cancels through Alt release; "
                                + "native mouse capture restored; camera preserved; Escape/onClose/replacement cancellation; "
                                + "server item/count and empty slot sync; GUI scales 2/3; existing five-slot HUD; "
                                + "{} bounded rendered animation frames; captures {}", animationFrames, captures);
                phase = Phase.DONE;
                mc.stop();
            }
            default -> { }
        }
    }

    private static void changePhase(Phase next) {
        phase = next;
        phaseStarted = Util.getMillis();
        phaseTick = tick;
    }

    private static void open(Minecraft mc, int index) {
        screen = new TestingWheelScreen();
        mc.setScreen(screen);
        if (index >= WheelGeometry.FIRST_SLOT) hover(mc, index);
        else movePointer(mc, screen.width / 2d, screen.height / 2d);
        check(mc.screen == screen, "Wheel initializes as actual Minecraft screen");
        check(WheelClient.isWheelOpen(), "Wheel controller owns the active screen");
    }

    private static void hover(Minecraft mc, int index) {
        var frame = WheelClient.animation().frame(Util.getMillis());
        double angle = WheelGeometry.angle(index - WheelGeometry.FIRST_SLOT) + frame.rotation();
        double radius = HotbarWheelRenderer.outerRadius(screen.width, screen.height) * .70 * frame.scale();
        movePointer(mc, HotbarWheelRenderer.centerX(screen.width) + Math.cos(angle) * radius,
                HotbarWheelRenderer.centerY(screen.height) + Math.sin(angle) * radius);
    }

    /** Exercise every direction close to the screen boundary, beyond the rendered wheel. */
    private static void hoverFar(Minecraft mc, int index) {
        double cx = HotbarWheelRenderer.centerX(screen.width);
        double cy = HotbarWheelRenderer.centerY(screen.height);
        if (index == 8) {
            check(Math.hypot(cx - 1, cy - 1) > HotbarWheelRenderer.outerRadius(screen.width, screen.height) + 3,
                    "Top-left corner is beyond the wheel");
            movePointer(mc, 1, 1);
            return;
        }
        var frame = WheelClient.animation().frame(Util.getMillis());
        double angle = WheelGeometry.angle(index - WheelGeometry.FIRST_SLOT) + frame.rotation();
        double dx = Math.cos(angle), dy = Math.sin(angle);
        double availableX = Math.abs(dx) < .00001 ? Double.POSITIVE_INFINITY : (cx - 1) / Math.abs(dx);
        double availableY = Math.abs(dy) < .00001 ? Double.POSITIVE_INFINITY : (cy - 1) / Math.abs(dy);
        double radius = .94 * Math.min(availableX, availableY);
        check(radius > HotbarWheelRenderer.outerRadius(screen.width, screen.height) + 3,
                "Far direction fixture is beyond the wheel for slot " + index);
        movePointer(mc, cx + dx * radius, cy + dy * radius);
    }

    private static void movePhysicalOffset(Minecraft mc, double dx, double dy) {
        movePointer(mc, screen.width / 2d + dx * screen.width / mc.getWindow().getWidth(),
                screen.height / 2d + dy * screen.height / mc.getWindow().getHeight());
    }

    private static void checkPhysicalDeadzone(Minecraft mc, int retainedSlot) {
        int retainedSector = retainedSlot - WheelGeometry.FIRST_SLOT;
        movePhysicalOffset(mc, 14.9, 0);
        check(WheelClient.animation().hovered() == retainedSector,
                "14.9 physical pixels retain direction at GUI scale " + mc.getWindow().getGuiScale());
        movePhysicalOffset(mc, 15, 0);
        check(WheelClient.animation().hovered() == 1,
                "Exactly 15 physical pixels select the right-hand direction at GUI scale " + mc.getWindow().getGuiScale());
        hover(mc, retainedSlot);
        check(WheelClient.animation().hovered() == retainedSector, "Direction selection restores retained test slot");
    }

    private static void movePointer(Minecraft mc, double guiX, double guiY) {
        double windowX = guiX * mc.getWindow().getScreenWidth() / screen.width;
        double windowY = guiY * mc.getWindow().getScreenHeight() / screen.height;
        GLFW.glfwSetCursorPos(mc.getWindow().getWindow(),
                windowX, windowY);
        // Deliver through Minecraft's actual cursor callback as well. Native cursor warps
        // can omit OS motion callbacks while another GLFW-based renderer is loaded.
        try {
            var callback = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
            callback.setAccessible(true);
            callback.invoke(mc.mouseHandler, mc.getWindow().getWindow(), windowX, windowY);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not deliver smoke-test cursor input", failure);
        }
        screen.mouseMoved(guiX, guiY);
    }

    private static double pointerGuiX(Minecraft mc) {
        return mc.mouseHandler.xpos() * screen.width / mc.getWindow().getScreenWidth();
    }

    private static double pointerGuiY(Minecraft mc) {
        return mc.mouseHandler.ypos() * screen.height / mc.getWindow().getScreenHeight();
    }

    private static void releaseAlt(Minecraft mc) {
        screen.held = false;
        screen.keyReleased(GLFW.GLFW_KEY_LEFT_ALT, 0, 0);
        screen.tick();
        check(mc.screen == null && !WheelClient.isWheelOpen(), "Alt release closes pocket screen and clears owner");
        check(mc.mouseHandler.isMouseGrabbed(), "Alt release restores mouse capture");
    }

    private static void clickLeft(Minecraft mc, int expected) {
        String context = " before=" + mc.player.getInventory().selected + " hover=" + WheelClient.animation().hovered()
                + " pointer=" + mc.mouseHandler.xpos() + "," + mc.mouseHandler.ypos()
                + " active=" + mc.isWindowActive() + " overlay=" + mc.getOverlay()
                + " screen=" + mc.screen + " size=" + screen.width + "x" + screen.height;
        LoggerFactory.getLogger("WheelSmoke").info("WHEEL_LEFT_CLICK expected={}{}", expected, context);
        screen.mouseClicked(pointerGuiX(mc), pointerGuiY(mc), 0);
        check(mc.screen == null, "Left click closes pocket");
        check(!WheelClient.isWheelOpen(), "Left click clears active controller owner");
        check(mc.player.getInventory().selected == expected, "Left click immediately commits client index " + expected
                + " actual=" + mc.player.getInventory().selected + context);
        check(mc.mouseHandler.isMouseGrabbed(), "Left click immediately restores mouse capture");
    }

    private static void assertSelected(Minecraft mc, int expected) {
        assertSelected(mc, expected, true);
    }

    private static void assertSelected(Minecraft mc, int expected, boolean grabbed) {
        check(mc.player.getInventory().selected == expected, "Client selected index " + expected);
        if (grabbed) check(mc.mouseHandler.isMouseGrabbed(), "Game mouse control restored");
        var server = mc.getSingleplayerServer();
        var playerId = mc.player.getUUID();
        int selected = server.submit(() -> server.getPlayerList().getPlayer(playerId).getInventory().selected).join();
        check(selected == expected, "Actual server selected index " + expected + ", got " + selected);
    }

    private static void checkExistingHotbar() throws ReflectiveOperationException {
        var bar = ReferenceHotbar.getOrCreateUI().ui.getRootElement().getChildren().getFirst();
        check(bar.getChildren().size() == 6, "Existing five shortcut slots plus offhand remain present");
        for (int i = 0; i < 5; i++) {
            var field = bar.getChildren().get(i).getClass().getDeclaredField("index");
            field.setAccessible(true);
            check(field.getInt(bar.getChildren().get(i)) == i + 4, "HUD keeps original shortcut inventory index");
        }
    }

    @SubscribeEvent
    public static void screenRendered(ScreenEvent.Render.Post event) {
        if (!built || failed || event.getScreen() != screen) return;
        try {
            long age = Util.getMillis() - phaseStarted;
            recordAnimation(age);
            String name = null;
            if (phase == Phase.OPENING && age >= 60 && !openingCaptured) {
                name = "wheel-opening.png"; openingCaptured = true;
            } else if (phase == Phase.OPENING && age >= 220 && !openCaptured) {
                name = "wheel-pocket-slot5.png"; openCaptured = true;
            } else if (phase == Phase.HOVER && age >= 45 && !hoverCaptured) {
                name = "wheel-hover-transition.png"; hoverCaptured = true;
            } else if (phase == Phase.SCROLL && age >= 60 && !scrollCaptured) {
                name = "wheel-scroll-spin.png"; scrollCaptured = true;
            } else if (phase == Phase.SCALE_OPEN && age >= 220 && !scaleCaptured) {
                name = "wheel-scale3-empty-slot8.png"; scaleCaptured = true;
            }
            if (name != null) {
                event.getGuiGraphics().flush();
                capture(name);
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void hudRendered(RenderGuiEvent.Post event) {
        if (!built || failed || phase != Phase.CLOSING || Minecraft.getInstance().screen != null) return;
        try {
            long age = Util.getMillis() - phaseStarted;
            recordAnimation(age);
            if (closingCaptured || age < 60) return;
            event.getGuiGraphics().flush();
            capture("wheel-closing.png");
            closingCaptured = true;
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static void recordAnimation(long age) {
        var frame = WheelClient.animation().frame(Util.getMillis());
        check(Float.isFinite(frame.alpha()) && frame.alpha() >= 0 && frame.alpha() <= 1,
                "Rendered wheel alpha stays in [0,1]");
        check(Float.isFinite(frame.scale()) && frame.scale() >= .859f && frame.scale() <= 1.001f,
                "Rendered wheel scale remains bounded");
        check(Float.isFinite(frame.rotation()) && Float.isFinite(frame.highlightAngle()),
                "Rendered wheel rotations remain finite");
        check(Float.isFinite(frame.selectionPulse()) && frame.selectionPulse() >= 0 && frame.selectionPulse() <= 1,
                "Rendered selection pulse stays in [0,1]");
        float[] highlights = frame.highlights();
        for (float highlight : highlights) {
            check(Float.isFinite(highlight) && highlight >= 0 && highlight <= 1,
                    "Rendered hover weights stay in [0,1]");
        }
        if (phase == Phase.OPENING) check(Math.abs(frame.rotation()) < .0001f, "Rendered entrance never spins");
        openingInterpolated |= phase == Phase.OPENING && frame.alpha() > .01f && frame.alpha() < .99f;
        hoverInterpolated |= phase == Phase.HOVER && highlights[4] > .01f && highlights[4] < .99f;
        scrollInterpolated |= phase == Phase.SCROLL && Math.abs(frame.rotation()) > .01f;
        closingInterpolated |= phase == Phase.CLOSING && frame.alpha() > .01f && frame.alpha() < .99f;
        animationCsv.append(animationFrames++).append(',').append(phase).append(',').append(age).append(',')
                .append(frame.alpha()).append(',').append(frame.scale()).append(',').append(frame.rotation());
        for (float highlight : highlights) animationCsv.append(',').append(highlight);
        animationCsv.append(',').append(frame.selectionPulse()).append('\n');
    }

    private static void capture(String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            image.writeToFile(CAPTURE_DIRECTORY.resolve(name));
        }
        captures.add(name);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static void fail(Throwable failure) {
        failed = true;
        LoggerFactory.getLogger("WheelSmoke").error("WHEEL_SMOKE_FAIL in {}", phase, failure);
        Minecraft.getInstance().stop();
    }

    /** Isolates wheel behavior from the physical OS Alt state in this automated fixture. */
    private static final class TestingWheelScreen extends HotbarWheelScreen {
        private boolean held = true;
        @Override protected boolean isTriggerHeld() { return held; }
    }

    private static final class ReplacementScreen extends Screen {
        private ReplacementScreen() { super(Component.literal("Wheel replacement cancellation test")); }
        @Override public boolean isPauseScreen() { return false; }
    }
}
