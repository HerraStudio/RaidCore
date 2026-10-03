package dev.draginventory.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

/** Uses the real world matrices, including aim zoom, view bob, aspect ratio and camera roll. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class TacticalMarkerHud {
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static Vec3 cameraPosition = Vec3.ZERO;
    private static boolean ready;
    private TacticalMarkerHud() {}

    static void invalidate() { ready = false; }

    @SubscribeEvent
    public static void capture(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        TacticalMarkerManager.maintain(Minecraft.getInstance(), Util.getMillis());
        VIEW_PROJECTION.set(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        cameraPosition = event.getCamera().getPosition();
        ready = true;
    }

    static Vec3[] centerRay(Camera camera) {
        if (ready) {
            Matrix4f inverse = new Matrix4f(VIEW_PROJECTION).invert();
            Vector4f near = inverse.transform(new Vector4f(0, 0, -1, 1));
            Vector4f far = inverse.transform(new Vector4f(0, 0, 0.9f, 1));
            near.div(near.w);
            far.div(far.w);
            Vec3 start = cameraPosition.add(near.x, near.y, near.z);
            Vec3 direction = new Vec3(far.x - near.x, far.y - near.y, far.z - near.z).normalize();
            if (Double.isFinite(direction.x) && direction.lengthSqr() > 0.9) return new Vec3[] { start, direction };
        }
        return new Vec3[] { camera.getPosition(), new Vec3(camera.getLookVector()) };
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        TacticalMarkerManager.maintain(mc, Util.getMillis());
        if (mc.level == null) { TacticalItemIcon.close(); return; }
        if (!ready || !TacticalMarkerManager.canInput(mc) || TacticalMarkerManager.hasNoMarkers()) return;
        GuiGraphics graphics = event.getGuiGraphics();
        int width = graphics.guiWidth(), height = graphics.guiHeight();
        long now = Util.getMillis();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        graphics.flush();
        float[] oldColor = RenderSystem.getShaderColor().clone();
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, 0, 300);
            for (TacticalMarker marker : TacticalMarkerManager.allMarkers()) { // v2.5.7：含外部联动标点
                Vec3 relative = marker.position(partial).subtract(cameraPosition);
                var point = TacticalMarkerLogic.project(VIEW_PROJECTION, relative.x, relative.y, relative.z, width, height);
                float alpha = point.edge() ? 1 : TacticalMarkerLogic.alpha(Math.hypot(point.x() - width / 2f, point.y() - height / 2f));
                var appearance = TacticalMarkerLogic.appearance(now - marker.createdAt());
                alpha *= appearance.opacity();
                // Minecraft treats a nearly-zero text alpha as opaque; skip those first frames.
                if (alpha < 0.02f) continue;
                int meters = TacticalMarkerLogic.distanceMeters(relative.x, relative.y, relative.z);
                draw(graphics, mc, marker, point, alpha, meters, appearance);
            }
        } finally {
            graphics.flush();
            graphics.pose().popPose();
            RenderSystem.setShaderColor(oldColor[0], oldColor[1], oldColor[2], oldColor[3]);
            RenderSystem.defaultBlendFunc();
            if (!blend) RenderSystem.disableBlend();
        }
    }

    private static void draw(GuiGraphics g, Minecraft mc, TacticalMarker marker,
                             TacticalMarkerLogic.ScreenPoint point, float alpha, int meters,
                             TacticalMarkerLogic.Appearance appearance) {
        int rgb = marker.type() == TacticalMarker.Type.ENEMY ? 0xFF4949 : 0xFFFFFF;
        int color = Math.round(alpha * 255) << 24 | rgb;
        g.pose().pushPose();
        g.pose().translate(point.x(), point.y() + appearance.offsetY(), 0);
        g.pose().scale(appearance.scale(), appearance.scale(), 1);
        String distance = meters + "m";
        g.drawString(mc.font, distance, -mc.font.width(distance) / 2, -21, color, true);
        if (marker.type() == TacticalMarker.Type.ITEM) {
            TacticalItemIcon.draw(g, mc, marker.item(), -8, -8, alpha);
        } else if (marker.type() == TacticalMarker.Type.ENEMY) {
            // Geometric exclamation mark remains crisp at every GUI scale.
            int shadow = Math.round(alpha * 160) << 24;
            g.fill(-2, -8, 3, 4, shadow);
            g.fill(-2, 5, 3, 9, shadow);
            g.fill(-1, -7, 2, 3, color);
            g.fill(-1, 6, 2, 8, color);
        } else {
            g.pose().pushPose();
            g.pose().mulPose(Axis.ZP.rotationDegrees(45));
            g.fill(-3, -3, 3, 3, color);
            g.pose().popPose();
        }
        if (point.edge()) {
            g.pose().pushPose();
            g.pose().mulPose(Axis.ZP.rotation(point.angle()));
            // Directional chevron outside the type icon, leaving both the item and distance readable.
            for (int i = 0; i < 5; i++) {
                g.fill(13 + i, -4 + i, 15 + i, -3 + i, color);
                g.fill(13 + i, 3 - i, 15 + i, 4 - i, color);
            }
            g.pose().popPose();
        }
        g.pose().popPose();
    }
}
