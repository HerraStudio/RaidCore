package dev.tactical.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sgr792.gwo.client.GltfModelCache;
import com.sgr792.gwo.client.animation.AnimationPose;
import com.sgr792.gwo.client.animation.GunAnimationCache;
import com.sgr792.gwo.client.render.GwoRenderPass;
import com.sgr792.gwo.client.render.gpu.BufferedGltfModelRenderer;
import com.sgr792.gwo.client.render.gpu.GltfCpuTriangleRenderer;
import com.sgr792.gwo.client.render.gpu.GltfRenderMaterial;
import com.sgr792.gwo.compat.IrisCompat;
import dev.tactical.loot.InspectableLootItem;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

/** GWO's actual GLB cache, embedded clip sampler and GPU renderer, shared by all inspectable loot. */
public final class LootItemRenderer extends BlockEntityWithoutLevelRenderer {
    public static final LootItemRenderer INSTANCE=new LootItemRenderer();
    private static final Map<InspectableLootItem.Model,GltfModelCache.BakedGltfModel> MODELS=new HashMap<>();
    public static long gpuDraws,cpuDraws;
    private LootItemRenderer() { super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels()); }
    public static void reload() { MODELS.clear(); GltfModelCache.prepare(InspectableLootItem.GOLD_BAR.model()); LootInspectClient.reset(); }
    public static GltfModelCache.BakedGltfModel model(InspectableLootItem.Model spec) {
        return MODELS.computeIfAbsent(spec,key->GltfModelCache.get(key.model()));
    }
    public static float clipLength(InspectableLootItem.Model spec,String name,float fallback) {
        var clip=model(spec).embeddedAnimations().exactClip(name); return clip==null?fallback:clip.length();
    }
    public static AnimationPose pose(InspectableLootItem.Model spec,String clip,float seconds) {
        var model=model(spec); return new AnimationPose(model,model.embeddedAnimations().exactClip(clip),seconds);
    }
    public static void draw(InspectableLootItem.Model spec,AnimationPose animation,PoseStack pose,MultiBufferSource buffers,int light,int overlay,GwoRenderPass pass) {
        draw(spec,animation,pose,buffers,light,overlay,pass,false);
    }
    public static Matrix4f handedPose(Matrix4f matrix,boolean left) {
        return left?new Matrix4f().scaling(-1,1,1).mul(matrix).scale(-1,1,1):matrix;
    }
    public static void draw(InspectableLootItem.Model spec,AnimationPose animation,PoseStack pose,MultiBufferSource buffers,int light,int overlay,GwoRenderPass pass,boolean left) {
        var model=model(spec); if(model.isEmpty()) return;
        boolean shaderMaterial=IrisCompat.isShaderPackInUse() && pass.shaderPackShaderAllowed();
        var diffuse=spec.texture(shaderMaterial?"color":"vanilla_color");
        var material=new GltfRenderMaterial(diffuse,spec.texture("color_n"),spec.texture("color_s"),
                spec.texture("roughness"),spec.texture("metallic"),null,1f,.25f,1f,0f);
        var old=BufferedGltfModelRenderer.renderPass(); BufferedGltfModelRenderer.setRenderPass(pass);
        boolean drawn=false;
        try(var ignored=BufferedGltfModelRenderer.drawSource("tactical_loot")) {
            drawn=BufferedGltfModelRenderer.renderBoneMatrices(model,material,pose.last().pose(),light,
                    (BufferedGltfModelRenderer.BoneNameMatrixProvider)name->handedPose(animation.global(name),left),
                    (BufferedGltfModelRenderer.BoneNameVisibility)name->true);
        } finally { BufferedGltfModelRenderer.setRenderPass(old); }
        if(drawn) {gpuDraws++;return;}
        // Same GWO triangle fallback for contexts/shader packs where its GPU path cannot draw.
        cpuDraws++;
        for(var mesh:model.nodeMeshes()) {
            pose.pushPose();
            Matrix4f animated=new Matrix4f(animation.global(mesh.nodeName())).mul(new Matrix4f(mesh.globalTransform()).invert());
            pose.mulPose(handedPose(animated,left));
            GltfCpuTriangleRenderer.render(mesh.triangles(),diffuse,pose,buffers,light,overlay);
            pose.popPose();
        }
    }
    @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        if(!(stack.getItem() instanceof InspectableLootItem item)) return;
        pose.pushPose(); pose.translate(.5,.5,.5);
        draw(item.model(),AnimationPose.bindPose(model(item.model())),pose,buffers,light,overlay,
                context==ItemDisplayContext.GUI?GwoRenderPass.GUI_ITEM:GwoRenderPass.WORLD_ITEM);
        pose.popPose();
    }
}
