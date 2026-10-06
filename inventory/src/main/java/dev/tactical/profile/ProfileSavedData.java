package dev.tactical.profile;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

public final class ProfileSavedData extends SavedData {
    private int revision;
    private final Map<ItemProfile.Key,ItemProfile> profiles=new LinkedHashMap<>();
    public static ProfileSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(ProfileSavedData::new,ProfileSavedData::load),
                "tactical_inventory_item_profiles");
    }
    public ItemProfiles.Snapshot snapshot() { return new ItemProfiles.Snapshot(revision,profiles); }
    public boolean change(ItemProfile.Key key,ItemProfile value) {
        if(value==null) { if(profiles.remove(key)==null) return false; }
        else {
            if(value.equals(profiles.get(key))) return false;
            if(!profiles.containsKey(key) && profiles.size()>=ItemProfile.MAX_RULES) throw new IllegalArgumentException("配置条目已达上限");
            profiles.put(key,value);
        }
        revision++; setDirty(); return true;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        tag.putInt("revision",revision); var list=new ListTag();
        profiles.values().stream().sorted(java.util.Comparator.comparing(p->p.key().encoded())).forEach(p->{
            var row=new CompoundTag(); row.putString("item",p.key().item()); row.putString("content",p.key().content());
            row.putInt("width",p.width()); row.putInt("height",p.height()); row.putString("rarity",p.rarity().name());
            row.putBoolean("lootable",p.lootable()); row.putDouble("baseDropChance",p.baseDropChance()); list.add(row);
        });
        tag.put("profiles",list); return tag;
    }
    public static ItemProfiles.Snapshot read(CompoundTag tag) { return load(tag,null).snapshot(); }
    public static ProfileSavedData load(CompoundTag tag,HolderLookup.Provider registries) {
        var data=new ProfileSavedData(); data.revision=Math.max(0,tag.getInt("revision"));
        var list=tag.getList("profiles",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(ItemProfile.MAX_RULES,list.size());i++) try {
            var row=list.getCompound(i); var key=new ItemProfile.Key(row.getString("item"),row.getString("content"));
            data.profiles.put(key,new ItemProfile(key,row.getInt("width"),row.getInt("height"),Rarity.parse(row.getString("rarity")),
                    row.getBoolean("lootable"),row.getDouble("baseDropChance")));
        } catch(IllegalArgumentException ignored) { }
        return data;
    }
}
