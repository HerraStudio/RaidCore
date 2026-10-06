package dev.tactical.loot;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

public final class LootConfigData extends SavedData {
    private int revision;
    private final Map<String,LootRule> rules=new LinkedHashMap<>(LootSettings.defaults());
    public static LootConfigData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(LootConfigData::new,LootConfigData::load),
                "tactical_inventory_loot_rules");
    }
    public LootSettings.Snapshot snapshot() { return new LootSettings.Snapshot(revision,rules); }
    public boolean change(LootRule rule) {
        if(rule.equals(rules.get(rule.block()))) return false;
        if(!rules.containsKey(rule.block()) && rules.size()>=LootRule.MAX_RULES) throw new IllegalArgumentException("容器规则已达上限");
        rules.put(rule.block(),rule); revision++; setDirty(); return true;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        tag.putInt("revision",revision); var list=new ListTag();
        rules.values().stream().sorted(java.util.Comparator.comparing(LootRule::block)).forEach(rule->{
            var row=new CompoundTag(); row.putString("block",rule.block()); row.putString("name",rule.name());
            row.putDouble("multiplier",rule.multiplier()); row.putBoolean("enabled",rule.enabled()); list.add(row);
        });
        tag.put("rules",list); return tag;
    }
    public static LootConfigData load(CompoundTag tag,HolderLookup.Provider registries) {
        var data=new LootConfigData(); data.revision=Math.max(0,tag.getInt("revision"));
        var list=tag.getList("rules",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(LootRule.MAX_RULES,list.size());i++) try {
            var row=list.getCompound(i); var rule=new LootRule(row.getString("block"),row.getString("name"),
                    row.contains("multiplier")?row.getDouble("multiplier"):1,row.getBoolean("enabled"));
            data.rules.put(rule.block(),rule);
        } catch(IllegalArgumentException ignored) { }
        return data;
    }
}
