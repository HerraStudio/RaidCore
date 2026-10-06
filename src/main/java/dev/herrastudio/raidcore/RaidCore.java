package dev.herrastudio.raidcore;

import dev.draginventory.DragInventory;
import dev.herrastudio.tacticalactions.TacticalActions;
import dev.tactical.Tactical;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.LoggerFactory;

/** The single loader entry for inventory, HUD and tactical actions. */
@Mod(RaidCore.MOD_ID)
public final class RaidCore {
    public static final String MOD_ID = "raidcore";

    public RaidCore(IEventBus eventBus, ModContainer container) {
        Tactical.register(eventBus);
        DragInventory.register(eventBus, container);
        TacticalActions.register(eventBus, container);
        eventBus.addListener(dev.herrastudio.raidcore.network.ImpactDecalNetwork::register);
        LoggerFactory.getLogger("RaidCore").info(
                "RaidCore {} initialized: Tactical Inventory 1.0.20, Drag Inventory 2.6.1, Tactical Actions 1.1.0",
                container.getModInfo().getVersion());
    }
}
