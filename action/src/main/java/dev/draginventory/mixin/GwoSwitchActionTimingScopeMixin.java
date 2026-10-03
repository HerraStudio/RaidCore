package dev.draginventory.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.draginventory.client.FastSwitchActionScope;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "com.sgr792.gwo.client.ClientActionTiming", remap = false)
public abstract class GwoSwitchActionTimingScopeMixin {
    @Coerce
    @WrapMethod(method = "plannedTask(Ljava/lang/String;Ljava/lang/String;Lcom/sgr792/gwo/content/GunDefinition;Lcom/sgr792/gwo/client/animation/AnimationStateSelector$Context;IZZFLnet/minecraft/world/item/ItemStack;F)Lcom/sgr792/gwo/client/runtime/RuntimeGunTaskHandler$Task;")
    private static Object draginventory$planScope(String action, String state, @Coerce Object definition,
                                                  @Coerce Object context, int priority, boolean blockShoot,
                                                  boolean blockSprint, float transition, ItemStack gun,
                                                  float fallback, Operation<Object> original) {
        try (var scope = FastSwitchActionScope.enter(action)) {
            return original.call(action, state, definition, context, priority, blockShoot, blockSprint,
                    transition, gun, fallback);
        }
    }
}
