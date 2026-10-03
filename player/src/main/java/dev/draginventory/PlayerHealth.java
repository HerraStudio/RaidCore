package dev.draginventory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Authoritative base health; effects and equipment can still modify the final maximum. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID)
public final class PlayerHealth {
    public static final double BASE_MAX_HEALTH = 100;

    private PlayerHealth() {}

    @EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
    public static final class AttributesSetup {
        @SubscribeEvent
        public static void defaults(EntityAttributeModificationEvent event) {
            event.add(EntityType.PLAYER, Attributes.MAX_HEALTH, BASE_MAX_HEALTH);
        }
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) normalizeBase(player);
    }

    @SubscribeEvent
    public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) normalizeBase(player);
    }

    /** Migrate saved 20-health attributes once without healing on every login. */
    public static void normalizeBase(ServerPlayer player) {
        var attribute = player.getAttribute(Attributes.MAX_HEALTH);
        if (attribute == null || attribute.getBaseValue() == BASE_MAX_HEALTH) return;
        float fraction = player.getHealth() / player.getMaxHealth();
        attribute.setBaseValue(BASE_MAX_HEALTH);
        player.setHealth(Math.min(player.getMaxHealth(), fraction * player.getMaxHealth()));
    }
}
