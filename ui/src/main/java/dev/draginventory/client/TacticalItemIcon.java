package dev.draginventory.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Composite the native item renderer so even opaque block/custom item models fade correctly. */
final class TacticalItemIcon {
    private static TextureTarget target;
    private TacticalItemIcon() {}

    static void close() {
        if (target != null) { target.destroyBuffers(); target = null; }
    }

    static void draw(GuiGraphics graphics, Minecraft mc, ItemStack stack, int x, int y, float alpha) {
        if (stack.isEmpty()) return;
        graphics.flush();
        int drawTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readTarget = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        float[] oldColor = RenderSystem.getShaderColor().clone();
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        RenderSystem.getModelViewStack().pushMatrix();
        try {
            if (target == null) {
                target = new TextureTarget(64, 64, true, Minecraft.ON_OSX);
                target.setClearColor(0, 0, 0, 0);
            }
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, 16, 16, 0, -1000, 1000), VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.getModelViewStack().identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            var iconGraphics = new GuiGraphics(mc, graphics.bufferSource());
            iconGraphics.renderItem(stack, 0, 0);
            iconGraphics.flush();
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderColor(oldColor[0], oldColor[1], oldColor[2], oldColor[3]);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        var pose = graphics.pose().last().pose();
        buffer.addVertex(pose, x, y, 0).setUv(0, 1).setColor(1f, 1f, 1f, alpha);
        buffer.addVertex(pose, x, y + 16, 0).setUv(0, 0).setColor(1f, 1f, 1f, alpha);
        buffer.addVertex(pose, x + 16, y + 16, 0).setUv(1, 0).setColor(1f, 1f, 1f, alpha);
        buffer.addVertex(pose, x + 16, y, 0).setUv(1, 1).setColor(1f, 1f, 1f, alpha);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }
}
