package dev.tactical.loot;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

/** Inventories for configured blocks that have no native inventory, shared by all viewers. */
public final class BlockLootData extends SavedData {
    public static final int SIZE=27,MAX_CONTAINERS=65536;
    public record Key(String dimension,long position) {}
    public final class Inventory extends SimpleContainer {
        private final String block;
        private boolean generated;
        private Inventory(String block) { super(SIZE); this.block=block; }
        public boolean generated() { return generated; }
        public void generated(boolean value) { generated=value; BlockLootData.this.setDirty(); }
        @Override public void setChanged() { super.setChanged(); BlockLootData.this.setDirty(); }
    }
    private final Map<Key,Inventory> inventories=new LinkedHashMap<>();
    public static BlockLootData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(BlockLootData::new,BlockLootData::load),
                "tactical_inventory_block_loot");
    }
    public Inventory get(ServerLevel level,BlockPos pos) {
        var key=new Key(level.dimension().location().toString(),pos.asLong());
        String block=BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        var existing=inventories.get(key);
        if(existing!=null && existing.block.equals(block)) return existing;
        if(existing==null && inventories.size()>=MAX_CONTAINERS) throw new IllegalArgumentException("搜刮容器存储已达上限");
        var inventory=new Inventory(block); inventories.put(key,inventory); setDirty(); return inventory;
    }
    public Inventory remove(ServerLevel level,BlockPos pos) {
        var removed=inventories.remove(new Key(level.dimension().location().toString(),pos.asLong()));
        if(removed!=null) setDirty(); return removed;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        var list=new ListTag();
        inventories.forEach((key,inventory)->{
            var row=new CompoundTag(); row.putString("dimension",key.dimension()); row.putLong("position",key.position());
            row.putString("block",inventory.block); row.putBoolean("generated",inventory.generated);
            var items=new ListTag();
            for(int i=0;i<SIZE;i++) if(!inventory.getItem(i).isEmpty()) {
                var item=new CompoundTag(); item.putInt("slot",i); item.put("stack",inventory.getItem(i).saveOptional(registries)); items.add(item);
            }
            row.put("items",items); list.add(row);
        });
        tag.put("containers",list); return tag;
    }
    public static BlockLootData load(CompoundTag tag,HolderLookup.Provider registries) {
        var data=new BlockLootData(); var list=tag.getList("containers",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(MAX_CONTAINERS,list.size());i++) try {
            var row=list.getCompound(i); new dev.tactical.profile.ItemProfile.Key(row.getString("block"),"");
            new dev.tactical.profile.ItemProfile.Key(row.getString("dimension"),"");
            var inventory=data.new Inventory(row.getString("block")); inventory.generated=row.getBoolean("generated");
            var items=row.getList("items",Tag.TAG_COMPOUND);
            for(int j=0;j<Math.min(SIZE,items.size());j++) {
                var item=items.getCompound(j); int slot=item.getInt("slot");
                if(slot>=0 && slot<SIZE) inventory.setItem(slot,ItemStack.parseOptional(registries,item.getCompound("stack")));
            }
            data.inventories.put(new Key(row.getString("dimension"),row.getLong("position")),inventory);
        } catch(IllegalArgumentException ignored) { }
        return data;
    }
}
