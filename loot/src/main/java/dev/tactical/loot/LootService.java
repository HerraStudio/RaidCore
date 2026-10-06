package dev.tactical.loot;

import dev.tactical.BagMenu;
import dev.tactical.BagState;
import dev.tactical.crack.CrackService;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.network.PacketDistributor;

public final class LootService {
    private static final String GENERATED="tactical_inventory:loot_generated";
    public static void send(ServerPlayer player,boolean open,int request,boolean accepted,String notice) {
        if(open && (!player.isAlive() || CrackService.locked(player))) return;
        var store=LootConfigData.get(player.server);
        PacketDistributor.sendToPlayer(player,new LootPackets.State(store.save(new CompoundTag(),player.registryAccess()),
                open,player.hasPermissions(2),request,accepted,notice));
    }
    public static void edit(ServerPlayer player,LootPackets.Edit packet) {
        var store=LootConfigData.get(player.server);
        String error=!player.hasPermissions(2)?"只有管理员可以保存搜刮配置。"
                :!player.isAlive() || CrackService.locked(player)?"当前无法修改配置。"
                :packet.revision()!=store.snapshot().revision()?"配置已被其他管理员更新，请确认最新设置。":null;
        if(error!=null) { send(player,false,packet.request(),false,error); return; }
        try {
            var rule=new LootRule(packet.block(),packet.name(),packet.multiplier(),packet.enabled());
            var id=ResourceLocation.parse(rule.block());
            if(!BuiltInRegistries.BLOCK.containsKey(id) || BuiltInRegistries.BLOCK.get(id).defaultBlockState().isAir())
                throw new IllegalArgumentException("找不到这个方块，或该方块是空气。");
            if(store.change(rule)) {
                LootSettings.server(store.snapshot());
                for(var online:player.server.getPlayerList().getPlayers()) send(online,false,-1,true,"");
                player.server.overworld().getDataStorage().save();
            }
            send(player,false,packet.request(),true,"已保存容器规则并同步所有玩家。");
        } catch(IllegalArgumentException invalid) { send(player,false,packet.request(),false,invalid.getMessage()); }
    }
    public static OptionalInt open(ServerPlayer player,BlockPos pos) {
        if(!player.isAlive() || player.isSpectator() || CrackService.locked(player)
                || player.containerMenu!=player.inventoryMenu || !player.serverLevel().hasChunkAt(pos)
                || !pos.equals(LootTargeting.find(player))) return OptionalInt.empty();
        var level=player.serverLevel(); var state=level.getBlockState(pos); var rule=LootSettings.rule(state);
        if(rule==null || !rule.enabled()) return OptionalInt.empty();
        var entity=level.getBlockEntity(pos);
        if(entity instanceof SafeBlockEntity safe) {
            if(state.getValue(SafeBlock.OPEN) || safe.rewardGenerated()) safe.requestOpen(player);
            else CrackService.start(player,pos);
            return OptionalInt.empty();
        }
        if(entity instanceof BaseContainerBlockEntity base && !base.canOpen(player)) return OptionalInt.empty();
        if(entity instanceof Container nativeInventory && !nativeInventory.stillValid(player)) return OptionalInt.empty();
        var provider=state.getMenuProvider(level,pos);
        if(state.getBlock() instanceof ChestBlock && provider==null) return OptionalInt.empty(); // blocked / locked double chest
        Component title=rule.name().isEmpty()?(provider==null?state.getBlock().getName():provider.getDisplayName()):Component.literal(rule.name());
        BagState.of(player).normalize(player);
        return player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return title; }
            @Override public AbstractContainerMenu createMenu(int id,Inventory inventory,Player owner) {
                if(entity instanceof Container nativeInventory) {
                    preparePhysical(player,pos,rule);
                    if(state.getBlock() instanceof ChestBlock || state.is(Blocks.BARREL)) {
                        var original=provider.createMenu(id,inventory,owner);
                        if(original==null) return null;
                        if(original instanceof ChestMenu chest) return new BagMenu(id,inventory,new LootSession(chest,title,
                                p->chest.stillValid(p) && valid(player,level,pos,state.getBlock())));
                        original.removed(owner);
                    }
                    return new BagMenu(id,inventory,new LootSession(nativeInventory,title,owner,
                            p->nativeInventory.stillValid(p) && valid(player,level,pos,state.getBlock())));
                }
                var virtual=BlockLootData.get(player.server).get(level,pos);
                if(!virtual.generated() && LootRolls.configured()) {
                    virtual.generated(true); fill(virtual,LootRolls.generate(rule,level.random));
                }
                return new BagMenu(id,inventory,new LootSession(virtual,title,owner,p->valid(player,level,pos,state.getBlock())));
            }
        });
    }
    private static boolean valid(ServerPlayer player,net.minecraft.server.level.ServerLevel sourceLevel,BlockPos pos,net.minecraft.world.level.block.Block block) {
        return player.serverLevel()==sourceLevel && player.isAlive() && !player.isSpectator() && player.serverLevel().hasChunkAt(pos)
                && player.serverLevel().getBlockState(pos).is(block) && LootSettings.searchable(player.serverLevel().getBlockState(pos))
                && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))<=64;
    }
    private static List<BlockEntity> physicalParts(ServerPlayer player,BlockPos pos) {
        var parts=new ArrayList<BlockEntity>(); var level=player.serverLevel(); var state=level.getBlockState(pos);
        var entity=level.getBlockEntity(pos); if(entity!=null) parts.add(entity);
        if(state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE) {
            var other=level.getBlockEntity(pos.relative(ChestBlock.getConnectedDirection(state)));
            if(other instanceof Container && other.getBlockState().is(state.getBlock())) parts.add(other);
        }
        return parts;
    }
    private static void preparePhysical(ServerPlayer player,BlockPos pos,LootRule rule) {
        if(!LootRolls.configured()) return; // Existing worlds retain loot tables until a pool is configured.
        var parts=physicalParts(player,pos);
        if(parts.stream().anyMatch(part->part instanceof BaseContainerBlockEntity base && !base.canOpen(player))) return;
        Container combined=null;
        for(var part:parts) {
            if(!(part instanceof Container inventory) || part.getPersistentData().getBoolean(GENERATED)) continue;
            if(part instanceof RandomizableContainerBlockEntity randomizable) randomizable.setLootTable(null);
            part.getPersistentData().putBoolean(GENERATED,true);
            combined=combined==null?inventory:new net.minecraft.world.CompoundContainer(combined,inventory);
            part.setChanged();
        }
        // A double chest is one roll against its combined capacity; both halves retain the one-time marker.
        if(combined!=null) fill(combined,LootRolls.generate(rule,player.serverLevel().random));
    }
    public static void fill(Container container,List<ItemStack> items) {
        for(var stack:items) {
            for(int slot=0;slot<container.getContainerSize();slot++) if(container.getItem(slot).isEmpty() && container.canPlaceItem(slot,stack)) {
                container.setItem(slot,stack.copyWithCount(Math.min(stack.getCount(),container.getMaxStackSize(stack)))); break;
            }
        }
        container.setChanged();
    }
    public static boolean safeReward(ServerPlayer player,SafeBlockEntity safe) {
        var rule=LootSettings.rule(safe.getBlockState());
        if(rule==null || !rule.enabled() || !LootRolls.configured()) return false;
        for(var stack:LootRolls.generate(rule,player.serverLevel().random)) safe.enqueueReward(stack);
        return true;
    }
    public static Component title(SafeBlockEntity safe) {
        var rule=LootSettings.rule(safe.getBlockState());
        return rule!=null && !rule.name().isEmpty()?Component.literal(rule.name()):safe.getDisplayName();
    }
    public static void commands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tacticalloot").requires(source->source.hasPermission(2)).executes(ctx->{
            send(ctx.getSource().getPlayerOrException(),true,-1,true,""); return 1;
        }));
    }
    private LootService() {}
}
