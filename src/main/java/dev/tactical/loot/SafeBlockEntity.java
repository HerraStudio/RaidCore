package dev.tactical.loot;

import dev.tactical.Tactical;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import java.util.LinkedHashSet;
import java.util.UUID;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

public final class SafeBlockEntity extends RandomizableContainerBlockEntity implements
        com.lowdragmc.lowdraglib2.syncdata.holder.blockentity.ISyncBlockEntity,
        com.lowdragmc.lowdraglib2.syncdata.holder.blockentity.IBlockEntityManaged {
    public static final int SIZE = 27;
    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private final SafeDoorMotion doorMotion;
    private final LinkedHashSet<UUID> waitingPlayers = new LinkedHashSet<>();
    private int openingTicks;
    private static final com.lowdragmc.lowdraglib2.syncdata.field.ManagedFieldHolder HACK_FIELDS =
            new com.lowdragmc.lowdraglib2.syncdata.field.ManagedFieldHolder(SafeBlockEntity.class);
    private final com.lowdragmc.lowdraglib2.syncdata.storage.FieldManagedStorage hackStorage =
            new com.lowdragmc.lowdraglib2.syncdata.storage.FieldManagedStorage(this);
    @com.lowdragmc.lowdraglib2.syncdata.annotation.DescSynced public long hackSeed;
    @com.lowdragmc.lowdraglib2.syncdata.annotation.DescSynced public long hackPhaseStart;
    @com.lowdragmc.lowdraglib2.syncdata.annotation.DescSynced public long hackServerTime;
    @com.lowdragmc.lowdraglib2.syncdata.annotation.DescSynced public long hackShakeStart=-1;
    @com.lowdragmc.lowdraglib2.syncdata.annotation.DescSynced public long hackFeedbackTime;
    @com.lowdragmc.lowdraglib2.syncdata.annotation.DescSynced public int hackSuccesses;
    @com.lowdragmc.lowdraglib2.syncdata.annotation.DescSynced public int hackFailures;
    @com.lowdragmc.lowdraglib2.syncdata.annotation.DescSynced public int hackRevision;
    @com.lowdragmc.lowdraglib2.syncdata.annotation.DescSynced public int hackFeedback;
    @com.lowdragmc.lowdraglib2.syncdata.annotation.DescSynced public String hackSession="";
    @com.lowdragmc.lowdraglib2.syncdata.annotation.DescSynced public String hackStatus="idle";
    private boolean rewardGenerated;
    private long hackRetryAfter;
    private final java.util.List<ItemStack> pendingRewards=new java.util.ArrayList<>();
    public void enqueueReward(ItemStack stack) { pendingRewards.add(stack.copy()); flushRewards(); setChanged(); }
    public int pendingRewardCount() { return pendingRewards.stream().mapToInt(ItemStack::getCount).sum(); }
    private void flushRewards() {
        boolean changed=false;
        for(var remainder:pendingRewards) for(int slot=0;slot<SIZE && !remainder.isEmpty();slot++) {
            var existing=getItem(slot);
            if(existing.isEmpty()) { setItem(slot,remainder.copy()); remainder.setCount(0); changed=true; }
            else if(ItemStack.isSameItemSameComponents(existing,remainder)) {
                int count=Math.min(existing.getMaxStackSize()-existing.getCount(),remainder.getCount());
                if(count>0) { existing.grow(count); remainder.shrink(count); changed=true; }
            }
        }
        pendingRewards.removeIf(ItemStack::isEmpty);
        if(changed) setChanged();
    }
    public void dropPendingRewards() {
        if(level!=null && !level.isClientSide) for(var stack:pendingRewards)
            net.minecraft.world.Containers.dropItemStack(level,worldPosition.getX()+.5,worldPosition.getY()+.5,worldPosition.getZ()+.5,stack);
        pendingRewards.clear();
    }

    @Override public com.lowdragmc.lowdraglib2.syncdata.field.ManagedFieldHolder getFieldHolder() { return HACK_FIELDS; }
    @Override public com.lowdragmc.lowdraglib2.syncdata.storage.IManagedStorage getSyncStorage() { return hackStorage; }
    @Override public com.lowdragmc.lowdraglib2.syncdata.storage.IManagedStorage getRootStorage() { return hackStorage; }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return serializeInitialData(registries); }
    @Override public void handleUpdateTag(CompoundTag tag,HolderLookup.Provider registries) { deserializeInitialData(registries,tag); }
    public boolean rewardGenerated() { return rewardGenerated; }
    public void markRewardGenerated() { rewardGenerated=true; setChanged(); }
    public long retryAfter() { return hackRetryAfter; }
    public void setRetryAfter(long time) { hackRetryAfter=time; }

    public SafeBlockEntity(BlockPos position, BlockState state) {
        super(Tactical.SAFE_ENTITY.get(), position, state);
        doorMotion = new SafeDoorMotion(state.getValue(SafeBlock.OPEN));
    }

    public void requestOpen(ServerPlayer player) {
        if(level == null || !player.isAlive() || !stillValid(player)) return;
        if(getBlockState().getValue(SafeBlock.OPEN)) {
            if(openingTicks == 0) LootContainers.openSafe(player, this);
            else waitingPlayers.add(player.getUUID());
            return;
        }
        openingTicks = SafeDoorMotion.DURATION;
        waitingPlayers.add(player.getUUID());
        level.setBlock(worldPosition, getBlockState().setValue(SafeBlock.OPEN, true), 3);
        level.playSound(null, worldPosition, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, .6f, .75f);
        setChanged();
    }

    public float doorAngle(float partialTick) { return doorMotion.angle(partialTick); }
    public float doorProgress(float partialTick) { return doorMotion.progress(partialTick); }
    public int openingTicksRemaining() { return openingTicks; }

    public static void tick(Level level, BlockPos position, BlockState state, SafeBlockEntity safe) {
        if(!level.isClientSide && !safe.pendingRewards.isEmpty()) safe.flushRewards();
        if(level.isClientSide) {
            safe.doorMotion.tick(state.getValue(SafeBlock.OPEN));
        } else if(safe.openingTicks > 0 && --safe.openingTicks == 0) {
            for(UUID id : safe.waitingPlayers) {
                var player = level.getServer().getPlayerList().getPlayer(id);
                if(player != null && player.isAlive() && player.level() == level && safe.stillValid(player)
                        && player.containerMenu == player.inventoryMenu) LootContainers.openSafe(player, safe);
            }
            safe.waitingPlayers.clear();
        }
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.tactical_inventory.safe");
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        lootTable = null;
        lootTableSeed = 0L;
        rewardGenerated=tag.contains("HackRewardGenerated") ? tag.getBoolean("HackRewardGenerated")
                : getBlockState().getValue(SafeBlock.OPEN);
        pendingRewards.clear();
        var pending=tag.getList("HackPendingRewards",net.minecraft.nbt.Tag.TAG_COMPOUND);
        for(int i=0;i<pending.size();i++) { var stack=ItemStack.parseOptional(registries,pending.getCompound(i)); if(!stack.isEmpty()) pendingRewards.add(stack); }
        if (!tryLoadLootTable(tag)) {
            ContainerHelper.loadAllItems(tag, items, registries);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("HackRewardGenerated",rewardGenerated);
        var pending=new net.minecraft.nbt.ListTag();
        for(var stack:pendingRewards) pending.add(stack.saveOptional(registries));
        tag.put("HackPendingRewards",pending);
        if (!trySaveLootTable(tag)) {
            ContainerHelper.saveAllItems(tag, items, registries);
        }
    }

    @Override
    public void clearContent() {
        super.clearContent();
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return !isRemoved() && getBlockState().is(Tactical.SAFE.get()) && super.stillValid(player);
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return ChestMenu.threeRows(containerId, inventory, this);
    }
}
