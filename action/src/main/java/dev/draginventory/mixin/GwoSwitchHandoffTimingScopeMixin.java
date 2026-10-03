package dev.draginventory.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.draginventory.client.FastSwitchActionScope;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "com.sgr792.gwo.client.handoff.ClientWeaponHandoffHandler", remap = false)
public abstract class GwoSwitchHandoffTimingScopeMixin {
    @Coerce
    @WrapMethod(method = "drawPlan(Lnet/minecraft/world/item/ItemStack;Lcom/sgr792/gwo/content/GunDefinition;)Lcom/sgr792/gwo/client/animation/GunDrawHolsterHandler$DrawPlan;")
    private Object draginventory$drawScope(ItemStack stack, @Coerce Object definition, Operation<Object> original) {
        try (var scope = FastSwitchActionScope.enter("raise")) {
            return original.call(stack, definition);
        }
    }

    @WrapOperation(method = {"observeBeforeRender", "tick"}, at = @At(value = "INVOKE",
            target = "Lcom/sgr792/gwo/client/animation/AnimationDurationResolver;duration(Ljava/lang/String;Lcom/sgr792/gwo/content/GunDefinition;F)F"), require = 1)
    private float draginventory$holsterScope(String state, @Coerce Object definition, float fallback,
                                             Operation<Float> original) {
        try (var scope = FastSwitchActionScope.enter("drop")) {
            return original.call(state, definition, fallback);
        }
    }
}
