package dev.tactical.mixin;

import dev.tactical.loot.client.LootClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=Minecraft.class,priority=1500)
public abstract class LootKeysMixin {
    @Inject(method="handleKeybinds",at=@At("HEAD"))
    private void tactical$searchKey(CallbackInfo ci) { LootClient.handleKeys(); }
}
