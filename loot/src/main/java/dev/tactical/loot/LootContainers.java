package dev.tactical.loot;

import dev.tactical.BagMenu;
import dev.tactical.BagState;
import java.util.OptionalInt;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;

public final class LootContainers {
    public static OptionalInt open(Player player,MenuProvider provider) {
        if(!(player instanceof ServerPlayer) || player.isSpectator()) return player.openMenu(provider);
        BagState.of(player).normalize(player);
        return player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return provider.getDisplayName(); }
            @Override public AbstractContainerMenu createMenu(int id,Inventory inventory,Player owner) {
                var original=provider.createMenu(id,inventory,owner);
                if(original instanceof ChestMenu chest)
                    return new BagMenu(id,inventory,new LootSession(chest,provider.getDisplayName()));
                return original;
            }
        });
    }

    public static OptionalInt openSafe(ServerPlayer player,SafeBlockEntity safe) {
        if(!LootSettings.searchable(safe.getBlockState())) return OptionalInt.empty();
        return open(player,new MenuProvider() {
            @Override public Component getDisplayName() { return LootService.title(safe); }
            @Override public AbstractContainerMenu createMenu(int id,Inventory inventory,Player owner) { return safe.createMenu(id,inventory,owner); }
        });
    }

    private LootContainers() {}
}
