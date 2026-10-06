package dev.tactical.loot;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID)
public final class LootEvents {
    @SubscribeEvent(priority=EventPriority.HIGH)
    public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
        if(LootSettings.searchable(event.getLevel().getBlockState(event.getPos()))) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void broken(BlockEvent.BreakEvent event) {
        if(event.getLevel() instanceof ServerLevel level) {
            var inventory=BlockLootData.get(level.getServer()).remove(level,event.getPos());
            if(inventory!=null) net.minecraft.world.Containers.dropContents(level,event.getPos(),inventory);
        }
    }
    @SubscribeEvent public static void placed(BlockEvent.EntityPlaceEvent event) {
        if(event.getLevel() instanceof ServerLevel level) BlockLootData.get(level.getServer()).remove(level,event.getPos());
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) { LootService.commands(event); }
    @SubscribeEvent public static void started(ServerStartedEvent event) { LootSettings.server(LootConfigData.get(event.getServer()).snapshot()); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { LootSettings.clearServer(); }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) LootService.send(player,false,-1,true,"");
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) LootService.send(player,false,-1,true,"");
    }
    private LootEvents() {}
}
