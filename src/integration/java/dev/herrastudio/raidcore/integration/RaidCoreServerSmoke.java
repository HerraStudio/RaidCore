package dev.herrastudio.raidcore.integration;

import dev.draginventory.PlayerStamina;
import dev.herrastudio.raidcore.RaidCore;
import dev.tactical.BagState;
import dev.tactical.Tactical;
import dev.tactical.raid.RaidConfig;
import dev.tactical.raid.RaidSavedData;
import dev.tactical.raid.RaidSession;
import net.minecraft.nbt.CompoundTag;
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
        if (!Boolean.getBoolean("raidcore.smoke") || Boolean.getBoolean("raidcore.sharedSmoke")) return;
        var server = event.getServer();
        try {
            require(Path.of(".").toRealPath().equals(Path.of(System.getProperty("raidcore.smoke.directory")).toRealPath()),
                    "Server harness directory is not isolated");
            RaidCoreSmokeChecks.verify();
            require(java.util.Arrays.stream(com.sgr792.gwo.entity.projectile.BulletEntity.class.getDeclaredMethods())
                    .anyMatch(method -> method.getName().contains("raidcore$sendDirection")),
                    "GWO impact direction mixin missing on dedicated server");
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
            require(commands.getChild("tacticalloot") != null, "Loot management command missing on dedicated server");
            var lootRules=new dev.tactical.loot.LootConfigData();
            var lootRule=new dev.tactical.loot.LootRule("minecraft:stone","Dedicated loot box",.5,true);
            lootRules.change(lootRule);
            require(dev.tactical.loot.LootConfigData.load(lootRules.save(new CompoundTag(),player.registryAccess()),player.registryAccess())
                    .snapshot().equals(lootRules.snapshot()),"Loot rule save/load lost name, coefficient or enabled state");
            var itemRules=new dev.tactical.profile.ProfileSavedData();
            var lootKey=new dev.tactical.profile.ItemProfile.Key("minecraft:diamond","");
            itemRules.change(lootKey,new dev.tactical.profile.ItemProfile(lootKey,2,1,dev.tactical.profile.Rarity.RARE,true,.2));
            require(dev.tactical.profile.ProfileSavedData.load(itemRules.save(new CompoundTag(),player.registryAccess()),player.registryAccess())
                    .snapshot().equals(itemRules.snapshot()),"Dedicated server lost item loot probability");
            require(lootRule.probability(.2)==.1,"Dedicated probability multiplier is incorrect");
            LoggerFactory.getLogger("RaidCoreSmoke").info("RAIDCORE_LOOT_SERVER_PASS: command, container/item NBT, probability math, dedicated server without client classes");
            require(commands.getChild("raid").getChild("duration") != null, "Raid duration command missing");
            require(RaidConfig.read(new CompoundTag()).matchDurationSeconds == 1800, "Legacy Raid config needs thirty-minute default");
            var saved = new RaidSavedData();
            saved.config.matchDurationSeconds = 90;
            var session = new RaidSession(java.util.UUID.randomUUID(), "Timer persistence", 90);
            session.tick(null, 0);
            session.finish(RaidSession.Outcome.TIMED_OUT);
            saved.players.put(player.getUUID(), new RaidSavedData.PlayerRaid(session, null));
            var tag = saved.save(new CompoundTag(), player.registryAccess());
            var roundTrip = RaidSavedData.load(tag, player.registryAccess());
            var restoredRaid = roundTrip.players.get(player.getUUID());
            require(roundTrip.config.matchDurationSeconds == 90 && restoredRaid.session.matchDurationTicks() == 1800
                    && restoredRaid.session.elapsedTicks() == 1 && restoredRaid.session.outcome() == RaidSession.Outcome.TIMED_OUT,
                    "Raid duration or timeout settlement did not survive NBT round trip");
            tag.getCompound("config").remove("matchDurationSeconds");
            tag.getList("players", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).remove("matchDurationSeconds");
            var legacyRaid = RaidSavedData.load(tag, player.registryAccess());
            require(legacyRaid.config.matchDurationSeconds == 1800
                    && legacyRaid.players.get(player.getUUID()).session.matchDurationTicks() == 36_000,
                    "Old world/session data did not default to thirty minutes");
            LoggerFactory.getLogger("RaidCoreSmoke").info(
                    "RAIDCORE_RAID_TIMER_SERVER_PASS: duration command, 30-minute legacy defaults, config/session NBT persistence and timeout outcome");
            LoggerFactory.getLogger("RaidCoreSmoke").info(
                    "RAIDCORE_SERVER_SMOKE_PASS: dedicated startup, one mod, legacy registries, 100 health, stamina, original inventory NBT/components, commands and GWO impact direction mixin without client classes");
        } finally {
            server.halt(false);
        }
    }
}
