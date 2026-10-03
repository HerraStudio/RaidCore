package dev.tactical.loot;

import dev.tactical.profile.Rarity;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Reusable loot item. It has no firearm content ID, ammo, or weapon behavior. */
public final class InspectableLootItem extends Item {
    public record Model(String name,int width,int height,Rarity rarity) {
        public ResourceLocation model() { return resource("models/loot/"+name+".glb"); }
        public ResourceLocation texture(String map) { return resource("textures/item/"+name+"_"+map+".png"); }
        private static ResourceLocation resource(String path) { return ResourceLocation.fromNamespaceAndPath("tactical_inventory",path); }
    }
    public static final Model GOLD_BAR=new Model("gold_bar",1,1,Rarity.LEGENDARY);
    public static Consumer<InteractionHand> inspect=hand->{};
    private final Model model;
    public InspectableLootItem(Model model,Properties properties) { super(properties); this.model=model; }
    public Model model() { return model; }
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand) {
        if(level.isClientSide) inspect.accept(hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide);
    }
}
