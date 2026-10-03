package dev.herrastudio.raidcore.integration;

import dev.draginventory.PlayerStamina;
import dev.herrastudio.raidcore.RaidCore;
import dev.tactical.BagState;
import dev.tactical.Tactical;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import static dev.herrastudio.raidcore.integration.RaidCoreSmokeChecks.require;

@EventBusSubscriber(modid = RaidCore.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class RaidCoreServerSmoke {
    private RaidCoreServerSmoke() {}

    @SubscribeEvent
    public static void started(ServerStartedEvent event) throws Exception {
        if (!Boolean.getBoolean("raidcore.smoke")) return;
        var server = event.getServer();
        try {
            require(Path.of(".").toRealPath().equals(Path.of(System.getProperty("raidcore.smoke.directory")).toRealPath()),
                    "Server harness directory is not isolated");
            RaidCoreSmokeChecks.verify();
            var player = FakePlayerFactory.getMinecraft(server.overworld());
            require(player.getMaxHealth() == 100, "Health attribute subscriber missing on dedicated server");
            require(player.getData(PlayerStamina.STATE).value() > 0, "Stamina attachment missing on dedicated server");
            var state = BagState.of(player);
            state.gear[2] = new ItemStack(Tactical.BACKPACK.get());
            var gold = new ItemStack(Tactical.GOLD_BAR.get());
            gold.set(DataComponents.CUSTOM_NAME, Component.literal("RaidCore migration fixture"));
            state.entries.add(new BagState.Entry(100, 1, 0, 0, false, gold));
            state.nextId = 101;
            state.save(player);
            var restored = BagState.read(player.getPersistentData().getCompound("tactical_inventory:storage"), player.registryAccess());
            require(restored.entry(100) != null && ItemStack.isSameItemSameComponents(restored.entry(100).stack, gold),
                    "Legacy storage component round trip failed");
            var commands = server.getCommands().getDispatcher().getRoot();
            require(commands.getChild("raid") != null && commands.getChild("tacticalitems") != null, "Merged server commands missing");
            LoggerFactory.getLogger("RaidCoreSmoke").info(
                    "RAIDCORE_SERVER_SMOKE_PASS: dedicated startup, one mod, legacy registries, 100 health, stamina, original inventory NBT/components and commands");
        } finally {
            server.halt(false);
        }
    }
}
