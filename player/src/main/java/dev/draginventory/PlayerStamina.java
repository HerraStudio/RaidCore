package dev.draginventory;

import com.mojang.serialization.Codec;
import java.util.function.Supplier;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID)
public final class PlayerStamina {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "draginventory");
    public static final Supplier<AttachmentType<StaminaState>> STATE = ATTACHMENTS.register("stamina",
            () -> AttachmentType.builder(() -> new StaminaState())
                    .serialize(Codec.FLOAT.xmap(StaminaState::new, StaminaState::value))
                    .sync((holder, recipient) -> holder == recipient,
                            ByteBufCodecs.FLOAT.map(StaminaState::new, StaminaState::value))
                    .build());

    private PlayerStamina() {}

    @SubscribeEvent
    public static void beforeTick(PlayerTickEvent.Pre event) {
        stopIfExhausted(event.getEntity());
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.isAlive()) return;
        var stamina = player.getData(STATE);
        boolean moving = stamina.moved(player.getX(), player.getZ());
        float previous = stamina.value();
        stamina.tick(canConsume(player) && player.isSprinting() && moving);
        stopIfExhausted(player);
        if (stamina.value() != previous) player.syncData(STATE);
    }

    @SubscribeEvent
    public static void jump(LivingEvent.LivingJumpEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !canConsume(player)) return;
        if (player.getData(STATE).jump()) {
            stopIfExhausted(player);
            player.syncData(STATE);
        }
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            player.getData(STATE);
            player.syncData(STATE);
        }
    }

    @SubscribeEvent
    public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            player.getData(STATE);
            player.syncData(STATE);
        }
    }

    private static void stopIfExhausted(Player player) {
        if (canConsume(player) && player.isSprinting() && !player.getData(STATE).canSprint(true)) {
            player.setSprinting(false);
        }
    }

    public static boolean canConsume(Player player) {
        return player.isAlive() && !player.isCreative() && !player.isSpectator()
                && !player.getAbilities().flying && !player.isPassenger()
                && !player.isFallFlying() && !player.isSleeping();
    }
}
