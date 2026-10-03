package dev.tactical;

import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID)
public final class Events {
    @SubscribeEvent public static void tick(PlayerTickEvent.Post event) {
        var p=event.getEntity();
        if(!p.level().isClientSide && p.isAlive()) BagState.of(p).normalize(p);
    }
    @SubscribeEvent public static void clone(PlayerEvent.Clone event) { BagState.copy(event.getOriginal(),event.getEntity()); }
}
