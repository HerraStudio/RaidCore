package dev.tactical.profile.client;

import com.sgr792.gwo.content.WeaponContentRegistry;
import com.sgr792.gwo.item.ContentGunItem;
import com.sgr792.gwo.item.ContentMeleeItem;
import com.sgr792.gwo.item.GunData;
import dev.tactical.profile.ItemProfile;
import dev.tactical.profile.ItemProfiles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ProfileCatalog {
    public record Entry(ItemProfile.Key key,ItemStack stack,String name) {}
    public static List<Entry> build() {
        var catalog=new LinkedHashMap<ItemProfile.Key,Entry>();
        for(var item:BuiltInRegistries.ITEM) if(item!=Items.AIR) {
            var stack=item.getDefaultInstance(); add(catalog,stack);
            if(item instanceof ContentGunItem || item instanceof ContentMeleeItem) {
                for(var id:WeaponContentRegistry.ids()) {
                    var definition=WeaponContentRegistry.get(id); if(definition==null) continue;
                    boolean melee=definition.weaponType().name().equals("MELEE");
                    if(melee!=(item instanceof ContentMeleeItem)) continue;
                    var variant=item.getDefaultInstance(); GunData.initialize(variant,id,definition); add(catalog,variant);
                }
            }
        }
        var player=Minecraft.getInstance().player;
        if(player!=null) {
            for(int i=0;i<player.getInventory().getContainerSize();i++) add(catalog,player.getInventory().getItem(i));
        }
        for(var key:ItemProfiles.client().entries().keySet()) if(!catalog.containsKey(key)) {
            var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(key.item())); if(item==Items.AIR) continue;
            var stack=item.getDefaultInstance();
            if(!key.content().isEmpty()) {
                var id=ResourceLocation.parse(key.content()); var definition=WeaponContentRegistry.get(id);
                if(definition!=null) GunData.initialize(stack,id,definition);
                else GunData.initializeContentItem(stack,id,key.content(),0);
            }
            add(catalog,stack);
        }
        var result=new ArrayList<>(catalog.values());
        result.sort(Comparator.comparing(Entry::name).thenComparing(e->e.key.encoded())); return List.copyOf(result);
    }
    private static void add(LinkedHashMap<ItemProfile.Key,Entry> catalog,ItemStack original) {
        if(original.isEmpty()) return;
        var stack=original.copyWithCount(1); var key=ItemProfiles.key(stack);
        if(!key.content().isEmpty()) GunData.isolatePreviewIdentity(stack);
        var definition=key.content().isEmpty()?null:WeaponContentRegistry.get(ResourceLocation.parse(key.content()));
        String name=definition==null?stack.getHoverName().getString():definition.displayName().getString();
        catalog.put(key,new Entry(key,stack,name));
    }
    private ProfileCatalog() {}
}
