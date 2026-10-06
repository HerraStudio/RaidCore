package dev.tactical.loot;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.util.thread.EffectiveSide;

public final class LootSettings {
    public record Snapshot(int revision,Map<String,LootRule> rules) {
        public Snapshot { rules=Map.copyOf(rules); }
        public LootRule rule(String block) { return rules.get(block); }
    }
    public static Map<String,LootRule> defaults() {
        var map=new LinkedHashMap<String,LootRule>();
        for(String id:new String[]{"minecraft:chest","minecraft:trapped_chest","minecraft:barrel","minecraft:shulker_box",
                "tactical_inventory:safe"}) map.put(id,new LootRule(id,"",1,true));
        for(String color:new String[]{"white","orange","magenta","light_blue","yellow","lime","pink","gray",
                "light_gray","cyan","purple","blue","brown","green","red","black"}) {
            String id="minecraft:"+color+"_shulker_box"; map.put(id,new LootRule(id,"",1,true));
        }
        return map;
    }
    private static volatile Snapshot server=new Snapshot(0,defaults()),client=new Snapshot(0,defaults());
    public static Snapshot current() { return EffectiveSide.get().isServer()?server:client; }
    public static Snapshot client() { return client; }
    public static void server(Snapshot value) { server=value; }
    public static void client(Snapshot value) { client=value; }
    public static LootRule rule(BlockState state) { return current().rule(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()); }
    public static boolean searchable(BlockState state) { var rule=rule(state); return rule!=null && rule.enabled() && !state.isAir(); }
    public static void clearServer() { server=new Snapshot(0,defaults()); }
    public static void clearClient() { client=new Snapshot(0,defaults()); }
    private LootSettings() {}
}
