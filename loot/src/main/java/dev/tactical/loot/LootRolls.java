package dev.tactical.loot;

import com.sgr792.gwo.content.WeaponContentRegistry;
import com.sgr792.gwo.item.GunData;
import dev.tactical.profile.ItemProfile;
import dev.tactical.profile.ItemProfiles;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class LootRolls {
    public static boolean configured() { return ItemProfiles.current().entries().values().stream().anyMatch(ItemProfile::lootable); }
    /** Each exact item/weapon variant gets one independent roll. Random order avoids slot-limit bias. */
    public static List<ItemProfile> select(Iterable<ItemProfile> pool,LootRule rule,DoubleSupplier random) {
        var selected=new ArrayList<ItemProfile>();
        for(var profile:pool) if(profile.lootable() && rule.probability(profile.baseDropChance())>random.getAsDouble()) selected.add(profile);
        return selected;
    }
    public static List<ItemStack> generate(LootRule rule,RandomSource random) {
        var candidates=select(ItemProfiles.current().entries().values(),rule,random::nextDouble);
        for(int i=candidates.size()-1;i>0;i--) java.util.Collections.swap(candidates,i,random.nextInt(i+1));
        var items=new ArrayList<ItemStack>();
        for(var profile:candidates) {
            var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(profile.key().item()));
            if(item==Items.AIR) continue;
            var stack=item.getDefaultInstance();
            if(!profile.key().content().isEmpty()) {
                var id=ResourceLocation.parse(profile.key().content()); var definition=WeaponContentRegistry.get(id);
                if(definition==null) continue;
                GunData.initialize(stack,id,definition);
            }
            items.add(stack);
        }
        return items;
    }
    private LootRolls() {}
}
