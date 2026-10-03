package dev.tactical.profile;

import com.sgr792.gwo.item.GunData;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.util.thread.EffectiveSide;

/** Separate snapshots prevent the integrated server from using client editor drafts. */
public final class ItemProfiles {
    public record Snapshot(int revision,Map<ItemProfile.Key,ItemProfile> entries) {
        public Snapshot { entries=Map.copyOf(entries); }
    }
    private static volatile Snapshot server=new Snapshot(0,Map.of()),client=new Snapshot(0,Map.of());
    public static ItemProfile.Key key(ItemStack stack) {
        var content=GunData.contentId(stack);
        return new ItemProfile.Key(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),content==null?"":content.toString());
    }
    public static Snapshot current() { return EffectiveSide.get().isServer()?server:client; }
    public static Snapshot client() { return client; }
    public static void server(Snapshot snapshot) { server=snapshot; }
    public static void client(Snapshot snapshot) { client=snapshot; }
    public static ItemProfile override(ItemStack stack) { return ItemProfile.resolve(current().entries(),key(stack)); }
    public static Rarity defaultRarity(ItemStack stack) {
        return stack.getItem() instanceof dev.tactical.loot.InspectableLootItem item?item.model().rarity():Rarity.COMMON;
    }
    public static Rarity rarity(ItemStack stack) { var rule=override(stack); return rule==null?defaultRarity(stack):rule.rarity(); }
    public static void clearServer() { server=new Snapshot(0,Map.of()); }
    public static void clearClient() { client=new Snapshot(0,Map.of()); }
    private ItemProfiles() {}
}
