package dev.tactical;

import dev.tactical.loot.SafeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

public final class LootWorldChecks {
    public static BlockPos safePosition;
    public static void verify(ServerPlayer player) {
        var level=player.serverLevel();
        BlockPos position=player.blockPosition().offset(2,0,0);
        level.setBlockAndUpdate(position.above(),Blocks.AIR.defaultBlockState());
        for(var block:new net.minecraft.world.level.block.Block[]{Blocks.CHEST,Blocks.TRAPPED_CHEST,Blocks.BARREL,Tactical.SAFE.get()}) {
            player.closeContainer();
            level.setBlockAndUpdate(position,block.defaultBlockState());
            var container=(Container)level.getBlockEntity(position);
            container.setItem(0,new ItemStack(Items.DIAMOND,3));
            use(player,position);
            require(player.containerMenu instanceof BagMenu menu && menu.loot()!=null,"Container did not open loot screen: "+block);
            var menu=(BagMenu)player.containerMenu;
            require(menu.loot().contains(container),"Wrong container binding");
            require(menu.stillValid(player),"Nearby container invalid");
            if(container instanceof ChestBlockEntity chest) {
                chest.recheckOpen();
                require(ChestBlockEntity.getOpenCount(level,position)==1,"Chest opener lost while loot screen open");
            }
            if(container instanceof BarrelBlockEntity barrel) {
                barrel.recheckOpen();
                require(level.getBlockState(position).getValue(net.minecraft.world.level.block.BarrelBlock.OPEN),"Barrel closed while looting");
            }
            player.closeContainer();
            require(container.getItem(0).getCount()==3,"Closing changed container contents");
            require(!menu.stillValid(player),"Closed loot session still valid");
            if(container instanceof ChestBlockEntity) require(ChestBlockEntity.getOpenCount(level,position)==0,"Chest opener not released");
            container.clearContent(); level.setBlockAndUpdate(position,Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(position,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.LEFT));
        BlockPos second=position.east();
        level.setBlockAndUpdate(second,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.RIGHT));
        level.setBlockAndUpdate(position,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.LEFT));
        var left=(ChestBlockEntity)level.getBlockEntity(position);
        var right=(ChestBlockEntity)level.getBlockEntity(second);
        left.setItem(0,new ItemStack(Items.GOLD_INGOT,2)); right.setItem(0,new ItemStack(Items.DIAMOND,4));
        use(player,position);
        require(player.containerMenu instanceof BagMenu,"Double chest did not open loot menu");
        var combined=((BagMenu)player.containerMenu).loot();
        require(combined.snapshot(player.registryAccess()).getInt("slots")==54,"Double chest lost a half");
        require(combined.contains(left) && combined.contains(right),"Double chest ownership missing");
        left.recheckOpen(); right.recheckOpen();
        require(ChestBlockEntity.getOpenCount(level,position)==1 && ChestBlockEntity.getOpenCount(level,second)==1,"Double chest openers mismatch");
        player.closeContainer();
        left.clearContent(); right.clearContent(); level.setBlockAndUpdate(second,Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(position.above(),Blocks.STONE.defaultBlockState());
        use(player,position);
        require(player.containerMenu==player.inventoryMenu,"Blocked chest bypassed vanilla access");
        level.setBlockAndUpdate(position.above(),Blocks.AIR.defaultBlockState());
        var locked=left.saveWithFullMetadata(player.registryAccess()); locked.putString("Lock","secret-test-key");
        left.loadWithComponents(locked,player.registryAccess());
        use(player,position);
        require(player.containerMenu==player.inventoryMenu,"Locked chest bypassed vanilla access");
        level.setBlockAndUpdate(position,Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(position,Tactical.SAFE.get().defaultBlockState());
        var safe=(SafeBlockEntity)level.getBlockEntity(position);
        safe.setItem(4,new ItemStack(Items.EMERALD,7));
        CompoundTag saved=safe.saveWithFullMetadata(player.registryAccess());
        var restored=new SafeBlockEntity(position,Tactical.SAFE.get().defaultBlockState());
        restored.loadWithComponents(saved,player.registryAccess());
        require(restored.getItem(4).is(Items.EMERALD) && restored.getItem(4).getCount()==7,"Safe persistence failed");
        use(player,position);
        var opened=(BagMenu)player.containerMenu;
        var dropsBefore=level.getEntitiesOfClass(ItemEntity.class,new AABB(position).inflate(2)).stream().map(ItemEntity::getUUID).toList();
        level.setBlockAndUpdate(position,Blocks.AIR.defaultBlockState());
        require(!opened.stillValid(player),"Destroyed safe remained usable");
        int dropped=level.getEntitiesOfClass(ItemEntity.class,new AABB(position).inflate(2)).stream()
                .filter(entity->!dropsBefore.contains(entity.getUUID()) && entity.getItem().is(Items.EMERALD)).mapToInt(entity->entity.getItem().getCount()).sum();
        require(dropped==7,"Breaking safe lost or duplicated contents");
        level.getEntitiesOfClass(ItemEntity.class,new AABB(position).inflate(2)).stream().filter(entity->!dropsBefore.contains(entity.getUUID())).forEach(ItemEntity::discard);
        player.closeContainer();
        level.setBlockAndUpdate(position,Tactical.SAFE.get().defaultBlockState());
        safePosition=position;
        LoggerFactory.getLogger("LootWorldChecks").info("LOOT_WORLD_CHECKS_PASS: safe/chest/trapped chest/barrel/double chest, access locks, obstruction, open/close, persistence and break drops");
    }
    public static void use(ServerPlayer player,BlockPos position) {
        var level=player.serverLevel();
        level.getBlockState(position).useWithoutItem(level,player,new BlockHitResult(Vec3.atCenterOf(position),Direction.UP,position,false));
    }
    private static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
