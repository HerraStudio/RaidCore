package dev.draginventory.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Uses vanilla's tracked selection packet, including its duplicate-send suppression. */
@Mixin(MultiPlayerGameMode.class)
public interface CarriedItemSync {
    @Invoker("ensureHasSentCarriedItem")
    void draginventory$syncCarriedItem();
}
