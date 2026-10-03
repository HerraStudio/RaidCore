package dev.herrastudio.tacticalactions;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;

/** Tactical actions bootstrap inside RaidCore; the legacy resource namespace is retained. */
public final class TacticalActions {
    /** Asset and payload namespace, preserved for existing animation and key mappings. */
    public static final String MOD_ID = "tacticalactions";

    private TacticalActions() {}

    public static void register(IEventBus eventBus, ModContainer container) {
        eventBus.addListener(PeekNetwork::register);
        container.registerConfig(ModConfig.Type.CLIENT, PeekClientConfig.SPEC, "tacticalactions-peek-client.toml");
    }
}
