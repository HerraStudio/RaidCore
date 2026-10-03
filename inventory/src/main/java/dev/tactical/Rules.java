package dev.tactical;

import com.sgr792.gwo.item.GunItem;
import com.sgr792.gwo.item.GunData;
import com.sgr792.gwo.item.ContentMeleeItem;
import com.sgr792.gwo.content.WeaponContentRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.EquipmentSlot;
import java.util.Locale;

public final class Rules {
    public record Size(int w, int h) {}
    public static Size capacity(ItemStack stack,int area) {
        // Per-stack capacity travels with the equipment and is read identically on both sides.
        var custom=stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if(custom!=null) {
            var data=custom.copyTag().getCompound("tactical_inventory");
            int w=data.getInt("columns"),h=data.getInt("rows");
            if(w>=1 && w<=6 && h>=1 && h<=12) return new Size(w,h);
        }
        for(int w=1;w<=6;w++) for(int h=1;h<=12;h++)
            if(tag(stack,"capacity_"+w+"x"+h)) return new Size(w,h);
        return area==0?new Size(4,5):new Size(6,6);
    }
    public static boolean tag(ItemStack s, String name) {
        return s.is(TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("tactical_inventory", name)));
    }
    public static boolean melee(ItemStack s) {
        return s.getItem() instanceof ContentMeleeItem || s.getItem() instanceof SwordItem
                || s.getItem() instanceof AxeItem || tag(s, "melee");
    }
    public static boolean gun(ItemStack s) { return s.getItem() instanceof GunItem<?> && !melee(s); }
    public static boolean pistol(ItemStack s) {
        if (tag(s, "sidearms")) return true;
        if (!gun(s)) return false;
        var id = GunData.contentId(s);
        var def = id == null ? null : WeaponContentRegistry.get(id);
        String text = ((id == null ? "" : id.getPath()) + " " + (def == null ? "" : def.creativeCategory())).toLowerCase(Locale.ROOT);
        return text.matches(".*(pistol|handgun|glock|viper|2011|1911|deagle|revolver|usp|p226|m9a1|fn57).*");
    }
    public static Size size(ItemStack s) {
        var configured=dev.tactical.profile.ItemProfiles.override(s);
        if(configured!=null) return new Size(configured.width(),configured.height());
        return defaultSize(s);
    }
    public static Size defaultSize(ItemStack s) {
        for (int w = 1; w <= 6; w++) for (int h = 1; h <= 6; h++)
            if (tag(s, "size_" + w + "x" + h)) return new Size(w, h);
        if(s.getItem() instanceof dev.tactical.loot.InspectableLootItem item) return new Size(item.model().width(),item.model().height());
        if (s.is(Tactical.BACKPACK.get())) return new Size(3, 3);
        if (s.is(Tactical.RIG.get())) return new Size(3, 3);
        if (gun(s)) return pistol(s) ? new Size(2, 2) : new Size(5, 2);
        if (melee(s)) return new Size(1, 3);
        if (s.getItem() instanceof ArmorItem) return new Size(2, 3);
        if (s.getItem() instanceof ShieldItem) return new Size(2, 3);
        if (s.getItem() instanceof DiggerItem || s.getItem() instanceof BowItem || s.getItem() instanceof CrossbowItem) return new Size(1, 3);
        if (tag(s, "magazines")) return new Size(1, 2);
        return new Size(1, 1);
    }
    public static boolean accepts(int slot, ItemStack s) {
        if (s.isEmpty()) return true;
        if (slot == 0 || slot == 1) return gun(s) && !pistol(s);
        if (slot == 2) return pistol(s);
        if (slot == 3) return melee(s);
        if (slot >= 4 && slot <= 8) return size(s).equals(new Size(1, 1));
        if (slot >= 36 && slot <= 39) return s.getItem() instanceof ArmorItem armor
                && armor.getEquipmentSlot().getIndex() == slot - 36;
        if (slot == 40) return true;
        if (slot == 41) return false; // Retired equipment slot; old contents are migrated safely.
        if (slot == 42) return s.is(Tactical.RIG.get()) || tag(s, "rigs");
        if (slot == 43) return s.is(Tactical.BACKPACK.get()) || tag(s, "backpacks");
        return false;
    }
}
