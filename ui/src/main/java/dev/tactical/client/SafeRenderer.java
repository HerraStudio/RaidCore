package dev.tactical.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.tactical.loot.SafeBlock;
import dev.tactical.loot.SafeBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.minecraft.server.packs.resources.ResourceManager;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class SafeRenderer implements BlockEntityRenderer<SafeBlockEntity> {
    public static final ModelResourceLocation DOOR = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath("tactical_inventory", "block/safe_door"));
    private record Animation(float x,float y,float z,float[] angles) {}
    private static volatile Animation animation = fallback();

    private static Animation fallback() {
        float[] angles = new float[65];
        for(int i=0;i<angles.length;i++) { float p=i/(float)(angles.length-1); angles[i]=60*(1-(1-p)*(1-p)*(1-p)); }
        return new Animation(.041891026f,.70672059f,.331483191f,angles);
    }

    public static void reloadAnimation(ResourceManager resources) {
        var path=ResourceLocation.fromNamespaceAndPath("tactical_inventory","models/block/safe_door_animation.json");
        try(var input=resources.open(path); var reader=new InputStreamReader(input,StandardCharsets.UTF_8)) {
            var data=JsonParser.parseReader(reader).getAsJsonObject();
            var values=data.getAsJsonArray("angles_degrees");
            float[] angles=new float[values.size()];
            if(angles.length<2 || data.get("duration_ticks").getAsInt()!=16) throw new IllegalArgumentException("Invalid duration/samples");
            for(int i=0;i<angles.length;i++) {
                angles[i]=values.get(i).getAsFloat();
                if(!Float.isFinite(angles[i]) || angles[i]<0 || angles[i]>60.01f || i>0 && angles[i]<angles[i-1])
                    throw new IllegalArgumentException("Invalid animation angle");
            }
            var pivot=data.getAsJsonArray("hinge");
            animation=new Animation(pivot.get(0).getAsFloat(),pivot.get(1).getAsFloat(),pivot.get(2).getAsFloat(),angles);
        } catch(Exception failure) {
            com.mojang.logging.LogUtils.getLogger().warn("Could not load Blender-authored safe animation",failure);
            animation=fallback();
        }
    }

    public static float animatedAngle(SafeBlockEntity safe,float partialTick) {
        float[] values=animation.angles();
        float frame=Math.max(0,Math.min(1,safe.doorProgress(partialTick)))*(values.length-1);
        int first=(int)frame, second=Math.min(values.length-1,first+1);
        return values[first]+(values[second]-values[first])*(frame-first);
    }

    @Override public void render(SafeBlockEntity safe, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int light, int overlay) {
        var mc = Minecraft.getInstance();
        var model = mc.getModelManager().getModel(DOOR);
        var movement = animation;
        var facing = safe.getBlockState().getValue(SafeBlock.FACING);
        pose.pushPose();
        try {
            pose.translate(.5, 0, .5);
            pose.mulPose(Axis.YP.rotationDegrees(180-facing.toYRot()));
            pose.translate(-.5, 0, -.5);
            pose.translate(movement.x(), movement.y(), movement.z());
            pose.mulPose(Axis.YP.rotationDegrees(animatedAngle(safe,partialTick)));
            pose.translate(-movement.x(), -movement.y(), -movement.z());
            mc.getBlockRenderer().getModelRenderer().renderModel(pose.last(),
                    buffers.getBuffer(RenderType.cutout()), safe.getBlockState(), model,
                    1, 1, 1, light, overlay, ModelData.EMPTY, RenderType.cutout());
        } finally { pose.popPose(); }
    }
}
