package dev.tactical.profile;

import dev.tactical.BagMenu;
import dev.tactical.BagState;
import dev.tactical.crack.CrackService;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID)
public final class ProfileService {
    public static void send(ServerPlayer player,boolean open,int request,boolean accepted,String notice) {
        if(open && (!player.isAlive() || CrackService.locked(player))) return;
        var store=ProfileSavedData.get(player.getServer());
        PacketDistributor.sendToPlayer(player,new ProfilePackets.State(store.save(new CompoundTag(),player.registryAccess()),
                open,player.hasPermissions(2),request,accepted,notice));
    }
    public static void edit(ServerPlayer player,ProfilePackets.Edit edit) {
        String error=null;
        var store=ProfileSavedData.get(player.getServer());
        if(!player.hasPermissions(2)) error="只有管理员可以保存服务器物品配置。";
        else if(CrackService.locked(player) || !player.isAlive()) error="当前无法修改配置。";
        else if(edit.revision()!=store.snapshot().revision()) error="配置已被其他管理员更新，已同步最新配置，请重新确认。";
        if(error!=null) { send(player,false,edit.request(),false,error); return; }
        try {
            var key=new ItemProfile.Key(edit.item(),edit.content());
            var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(key.item()));
            if(item==Items.AIR || !BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(key.item())))
                throw new IllegalArgumentException("找不到这个物品。");
            if(!key.content().isEmpty() && !key.item().startsWith("gwo:")) throw new IllegalArgumentException("只有 GWO 物品可指定型号。");
            if(edit.lootable() && key.content().isEmpty() && (item instanceof com.sgr792.gwo.item.ContentGunItem
                    || item instanceof com.sgr792.gwo.item.ContentMeleeItem))
                throw new IllegalArgumentException("请先选择具体 GWO 型号，再加入掉落池。");
            if(!key.content().isEmpty() && edit.lootable()
                    && com.sgr792.gwo.content.WeaponContentRegistry.get(ResourceLocation.parse(key.content()))==null)
                throw new IllegalArgumentException("找不到这个 GWO 型号，不能加入掉落池。");
            var profile=edit.reset()?null:new ItemProfile(key,edit.width(),edit.height(),Rarity.parse(edit.rarity()),
                    edit.lootable(),edit.baseDropChance());
            if(store.change(key,profile)) {
                ItemProfiles.server(store.snapshot());
                for(var online:player.getServer().getPlayerList().getPlayers()) {
                    // Publish profiles before coordinates; stale drag requests then fail the menu revision check.
                    send(online,false,-1,true,"");
                    BagState.of(online).normalize(online);
                    if(online.containerMenu instanceof BagMenu menu) menu.broadcastChanges();
                }
                // Persist immediately, rather than relying only on the world's next periodic autosave.
                player.getServer().overworld().getDataStorage().save();
            }
            send(player,false,edit.request(),true,edit.reset()?"已恢复默认规则并同步所有玩家。":"已保存并同步所有玩家。");
        } catch(IllegalArgumentException invalid) {
            send(player,false,edit.request(),false,invalid.getMessage()==null?"配置无效。":invalid.getMessage());
        }
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tacticalitems").executes(ctx->{
            var player=ctx.getSource().getPlayerOrException();
            if(CrackService.locked(player)) { ctx.getSource().sendFailure(Component.literal("请先结束破译。")); return 0; }
            send(player,true,-1,true,""); return 1;
        }));
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) { ItemProfiles.server(ProfileSavedData.get(event.getServer()).snapshot()); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { ItemProfiles.clearServer(); }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) send(player,false,-1,true,"");
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) send(player,false,-1,true,"");
    }
}
