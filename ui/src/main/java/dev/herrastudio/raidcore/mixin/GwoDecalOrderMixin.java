package dev.herrastudio.raidcore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** A surface decal is one paint layer; its mesh cells must never be distance-sorted. */
@Mixin(targets = "com.sgr792.gwo.client.render.GwoRenderTypes", remap = false)
public abstract class GwoDecalOrderMixin {
    // GWO 2.12.87's BULLET_DECAL factory; retain its shader, atlas, light and COLOR_WRITE state.
    @ModifyArg(method = "lambda$static$6", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/RenderType;create(Ljava/lang/String;Lcom/mojang/blaze3d/vertex/VertexFormat;Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;IZZLnet/minecraft/client/renderer/RenderType$CompositeState;)Lnet/minecraft/client/renderer/RenderType$CompositeRenderType;"), index = 5)
    private static boolean raidcore$keepDecalsWhole(boolean sortOnUpload) {
        return false;
    }
}
