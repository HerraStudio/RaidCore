package dev.tactical.raid;

import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.sgr792.gwo.item.ContentMeleeItem;
import dev.tactical.BagState;
import dev.tactical.Rules;
import dev.tactical.Tactical;
import dev.tactical.raid.client.RaidClient;
import dev.tactical.raid.client.RaidEffects;
import dev.tactical.raid.client.RaidSettlementScreen;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

/** Owns a fresh, isolated world and hidden window; never runs in the production JAR. */
@EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID, value=Dist.CLIENT)
public final class RaidSmoke {
    private static boolean started, isolatedInput, awaitingExtraction, cancelNextDeath;
    private static int ticks, stage, stageTick, deathCase;
    private static double floorY;
    private static ItemStack retainedKnife;
    private static UUID sessionId;
    private static String screenshot;
    @SubscribeEvent public static void isolate(RenderFrameEvent.Pre event) {
        var mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();
        if (!isolatedInput) {
            GLFW.glfwSetMouseButtonCallback(window,null); GLFW.glfwSetKeyCallback(window,null);
            GLFW.glfwSetCursorPosCallback(window,null); GLFW.glfwSetScrollCallback(window,null);
            isolatedInput = true;
        }
        if (GLFW.glfwGetWindowAttrib(window,GLFW.GLFW_VISIBLE)!=0) GLFW.glfwHideWindow(window);
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) throws Exception {
        if (screenshot == null) return;
        try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            image.writeToFile(Path.of(screenshot));
        }
        screenshot = null;
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        var mc = Minecraft.getInstance();
        if (!started && mc.screen instanceof TitleScreen && mc.getOverlay()==null) {
            started=true; mc.getWindow().setWindowed(1440,900); mc.options.guiScale().set(2);
            mc.options.renderDistance().set(3); mc.options.pauseOnLostFocus=false; mc.resizeDisplay();
            var settings=new LevelSettings("Raid integration",GameType.SURVIVAL,false,Difficulty.PEACEFUL,
                    true,new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("raid-smoke-"+System.currentTimeMillis(),settings,
                    new WorldOptions(5,false,false), registry->registry.registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),mc.screen);
        }
        if (!started || mc.player==null || ticks==0 && mc.screen!=null) return;
        ticks++;
        if (ticks > 1800) throw new IllegalStateException("Raid smoke timed out at stage "+stage+" "+snapshot());
        if (ticks == 20) {
            server(player -> {
                require(RaidManager.readinessError(player.server)!=null,"Unconfigured raid must reject entry");
                require(!RaidManager.join(player),"Unconfigured join was accepted");
                floorY=player.getY();
                teleport(player,24,0); command(player,"raid lobby");
                teleport(player,0,0); command(player,"raid spawn add alpha");
                teleport(player,0,8); command(player,"raid extract add gate 3");
                command(player,"raid name 测试行动");
                require(RaidSavedData.get(player.server).config.extractions.get("gate").seconds()==20,"Default extraction duration");
                teleport(player,24,0);
                player.getInventory().setItem(4,new ItemStack(Items.BREAD,12));
            });
            mc.player.connection.sendCommand("raid join");
            stage=1; stageTick=ticks;
        }
        if (stage==1 && ticks-stageTick>=45) {
            var tag=snapshot();
            require("ACTIVE".equals(tag.getString("status")),"Join must be active outside extraction");
            sessionId=tag.getUUID("session");
            server(player -> require(player.distanceToSqr(0,floorY,0)<1,"Spawn teleport failed"));
            server(player -> {
                var cow=net.minecraft.world.entity.EntityType.COW.create(player.serverLevel());
                require(cow!=null,"Kill fixture missing"); cow.moveTo(4,floorY,0,0,0);
                player.serverLevel().addFreshEntity(cow);
                cow.hurt(player.damageSources().playerAttack(player),1000);
                require(RaidManager.current(player).session.kills()==1,"Accepted raid kill not counted");
            });
            var runtime=RaidEffects.activeRuntime("gate");
            require(runtime!=null && runtime.isValid(),"Photon smoke runtime missing");
            var emitter=runtime.getFxData().objects().stream().filter(ParticleEmitter.class::isInstance)
                    .map(ParticleEmitter.class::cast).findFirst().orElseThrow();
            require(emitter.config.isLooping() && emitter.getParticleAmount()>0,"Photon looping particles missing");
            require(emitter.config.getStartColor().get(0f,()->.5f).intValue()==0xff11ff00,"Author's smoke color changed");
            require(Math.abs(runtime.root.transform().position().z-8)<.01,"Photon effect transform wrong");
            mc.gui.getChat().clearMessages(true); mc.getToasts().clear();
            screenshot="raid-smoke-photon.png";
            log("RAID_ENTRY_PHOTON_PASS: command join, real spawn, authored looping green emitter, live particles and world transform");
            stage=2; stageTick=ticks;
        }
        if (stage==2 && ticks-stageTick==15) server(player -> teleport(player,0,5.5));
        if (stage==2 && ticks-stageTick==40) {
            var tag=snapshot(); require("EXTRACTING".equals(tag.getString("status")),"Square extraction did not start");
            require(tag.getInt("totalTicks")==400 && tag.getInt("remainingTicks")<400,"20 second server countdown missing");
            screenshot="raid-smoke-countdown.png";
        }
        if (stage==2 && ticks-stageTick==65) server(player -> teleport(player,0,0));
        if (stage==2 && ticks-stageTick==80) {
            var tag=snapshot(); require("ACTIVE".equals(tag.getString("status")) && tag.getInt("remainingTicks")==0,"Leaving must reset countdown");
            server(player -> teleport(player,0,5.5));
            stage=3; stageTick=ticks;
        }
        if (stage==3 && ticks-stageTick==15) {
            var tag=snapshot(); require("EXTRACTING".equals(tag.getString("status")) && tag.getInt("remainingTicks")>=375,"Reentry retained prior progress");
            PacketDistributor.sendToServer(new RaidPackets.Acknowledge(sessionId));
            server(player -> {
                var data=RaidSavedData.get(player.server);
                var restored=RaidSavedData.load(data.save(new CompoundTag(),player.registryAccess()),player.registryAccess());
                var previous=restored.players.get(player.getUUID());
                require(previous.session.outcome()==RaidSession.Outcome.ABORTED && previous.returnPending && restored.isDirty(),"Restart must interrupt active raid safely");
            });
            log("RAID_COUNTDOWN_RESET_PASS: leave/reentry reset, 400 ticks, active ack rejected, persistence interruption");
            awaitingExtraction=true;
        }
        if (stage==3 && awaitingExtraction && "SETTLED".equals(snapshot().getString("status"))) {
            require("EXTRACTED".equals(snapshot().getString("outcome")),"Successful extraction result wrong");
            server(player -> {
                require(player.distanceToSqr(24,floorY,0)<1,"Extraction did not return to lobby");
                require(player.getInventory().getItem(4).is(Items.BREAD) && player.getInventory().getItem(4).getCount()==12,"Extraction changed carried loot");
            });
            stage=4; stageTick=ticks; awaitingExtraction=false;
        }
        if (stage==4 && ticks-stageTick==15) {
            require(mc.screen instanceof RaidSettlementScreen,"Extraction settlement UI missing");
            screenshot="raid-smoke-settlement-success.png";
        }
        // Capture the actually painted result before replacing it like an external screen.
        if (stage==4 && ticks-stageTick==20) mc.setScreen(null);
        if (stage==4 && ticks-stageTick==25) {
            require(mc.screen instanceof RaidSettlementScreen,"Unacknowledged result lost after screen replacement");
            server(player -> {
                player.serverLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true,player.server);
                player.invulnerableTime=0;
                player.hurt(player.damageSources().genericKill(),Float.MAX_VALUE);
            });
        }
        if (stage==4 && ticks-stageTick==40) {
            require(mc.screen instanceof DeathScreen,"Repeated death did not show normal death screen");
            mc.player.respawn();
        }
        if (stage==4 && ticks-stageTick==65) {
            require(mc.screen instanceof RaidSettlementScreen,"Unacknowledged settlement lost after another death");
            server(player -> {
                require(player.distanceToSqr(24,floorY,0)<1,"Repeated death respawn did not return to lobby");
                require(RaidManager.current(player).session.outcome()==RaidSession.Outcome.EXTRACTED
                        && RaidManager.current(player).session.id.equals(sessionId),"Repeated death overwrote committed outcome");
            });
            mc.screen.onClose();
        }
        if (stage==4 && ticks-stageTick==80) {
            require(mc.screen==null && "IDLE".equals(snapshot().getString("status")),"Result acknowledgement failed");
            server(player -> {
                var data=RaidSavedData.get(player.server);
                var restored=RaidSavedData.load(data.save(new CompoundTag(),player.registryAccess()),player.registryAccess());
                require(restored.config.extractions.get("gate").seconds()==20,"Configuration not persistent");
                require(restored.lastResults.get(player.getUUID()).session.outcome()==RaidSession.Outcome.EXTRACTED,"Acknowledged outcome not persistent");
            });
            log("RAID_EXTRACTION_SETTLEMENT_PASS: server completion, loot retained, lobby return, real screen reopen and network acknowledgement");
            prepareDeath(); stage=5; stageTick=ticks;
        }
        if (stage==5 && ticks-stageTick==20) {
            require("ACTIVE".equals(snapshot().getString("status")),"Death fixture join failed");
            if (deathCase==0) server(player -> {
                cancelNextDeath=true;
                player.hurt(player.damageSources().genericKill(),Float.MAX_VALUE);
                require(player.isAlive() && RaidManager.current(player).session.active(),"Canceled death committed raid result");
                require(player.getInventory().getItem(4).getCount()==12,"Canceled death dropped inventory");
                log("RAID_CANCELED_DEATH_PASS: revival cancellation keeps active session and equipment");
            });
            server(player -> {
                // The preceding canceled hit still sets vanilla hurt immunity.
                player.invulnerableTime=0;
                player.hurt(player.damageSources().genericKill(),Float.MAX_VALUE);
            });
            stage=6; stageTick=ticks;
        }
        if (stage==6 && ticks-stageTick==15) {
            var tag=snapshot(); require("DEAD".equals(tag.getString("outcome")) && tag.getBoolean("returnPending"),"Death outcome must await respawn: "+tag);
            require(mc.screen instanceof DeathScreen,"Normal death screen must run before result");
            server(RaidSmoke::verifyDeathDrops);
            mc.player.respawn(); stage=7; stageTick=ticks;
        }
        if (stage==7 && ticks-stageTick>=20 && mc.player.isAlive()) {
            require(mc.screen instanceof RaidSettlementScreen,"Death settlement UI missing after respawn");
            server(player -> {
                require(player.distanceToSqr(24,floorY,0)<1,"Death respawn did not return to lobby");
                require(ItemStack.matches(player.getInventory().getItem(3),retainedKnife),"GWO melee components not retained after respawn");
                require(player.getInventory().getItem(4).isEmpty() && BagState.of(player).gear[2].isEmpty(),"Nonmelee retained after death");
                require(BagState.of(player).entries.size()==1 && BagState.of(player).entries.getFirst().stack.is(Items.IRON_AXE)
                        && BagState.of(player).entries.getFirst().area==2,"Stored melee not retained in recovery after backpack loss");
            });
            screenshot="raid-smoke-settlement-death.png";
            log("RAID_DEATH_RULE_PASS: keepInventory="+(deathCase==1)+", GWO melee preserved incl components, stored axe recovered, other equipment and loot dropped");
            stage=8; stageTick=ticks;
        }
        if (stage==8 && ticks-stageTick==15) mc.screen.onClose();
        if (stage==8 && ticks-stageTick==30) {
            require("IDLE".equals(snapshot().getString("status")),"Death result ack failed");
            if (deathCase++==0) { prepareDeath(); stage=5; stageTick=ticks; }
            else {
                mc.player.connection.sendCommand("raid join"); stage=9; stageTick=ticks;
            }
        }
        if (stage==9 && ticks-stageTick==20) {
            server(player -> {
                var id=RaidManager.current(player).session.id;
                require(!RaidManager.acknowledge(player,UUID.randomUUID()),"Forged session ack accepted");
                RaidManager.logout(player);
                require(RaidManager.current(player).session.outcome()==RaidSession.Outcome.ABORTED,"Logout did not interrupt");
                RaidManager.login(player);
                require(player.distanceToSqr(24,floorY,0)<1 && !RaidManager.current(player).returnPending,"Relog did not return to lobby");
                require(RaidManager.current(player).session.id.equals(id),"Relog created duplicate result");
            });
            stage=10; stageTick=ticks;
        }
        if (stage==10 && ticks-stageTick==20) {
            require(mc.screen instanceof RaidSettlementScreen,"Interrupted result screen missing");
            mc.screen.onClose();
            var previous=RaidEffects.activeRuntime("gate");
            RaidEffects.onResourceReload(); RaidEffects.tick();
            require(RaidEffects.activeCount()==1 && RaidEffects.activeRuntime("gate")!=previous,"Resource reload must replace smoke without duplicates");
            RaidClient.clear(); require(RaidEffects.activeCount()==0,"Disconnect cleanup leaked smoke");
            log("RAID_RELOG_RELOAD_PASS: interruption idempotency, lobby return, wrong token rejection, Photon reload and cleanup");
            stage=11; stageTick=ticks;
        }
        if (stage==11 && ticks-stageTick==10) {
            server(player -> {
                require(RaidManager.join(player),"Spectator fixture join failed");
                player.setGameMode(GameType.SPECTATOR);
                RaidManager.tick(player.server);
                require(RaidManager.current(player).session.outcome()==RaidSession.Outcome.ABORTED
                        && player.distanceToSqr(24,floorY,0)<1,"Spectator conversion must leave raid");
                player.setGameMode(GameType.SURVIVAL);
            });
            log("RAID_SPECTATOR_ABORT_PASS: becoming an observer terminates active participation safely");
            log("RAID_SMOKE_PASS: extraction framework, real Photon/HUD/settlement, default20s, death melee-only retention under both game rules");
            mc.stop();
        }
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void cancelDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (!cancelNextDeath || !(event.getEntity() instanceof ServerPlayer)) return;
        cancelNextDeath=false;
        event.setCanceled(true); event.getEntity().setHealth(event.getEntity().getMaxHealth());
    }
    private static void prepareDeath() {
        server(player -> {
            player.getInventory().clearContent(); var bag=BagState.of(player); bag.entries.clear();
            for(int index=0;index<3;index++) bag.gear[index]=ItemStack.EMPTY;
            player.serverLevel().getEntitiesOfClass(ItemEntity.class,new AABB(-16,floorY-8,-16,16,floorY+8,16)).forEach(ItemEntity::discard);
            player.serverLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(deathCase==1,player.server);
            var melee=BuiltInRegistries.ITEM.stream().filter(ContentMeleeItem.class::isInstance).findFirst().orElseThrow();
            retainedKnife=new ItemStack(melee); retainedKnife.set(DataComponents.CUSTOM_NAME,Component.literal("保留近战测试"));
            var data=new CompoundTag(); data.putInt("componentAudit",617); retainedKnife.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
            player.getInventory().setItem(3,retainedKnife.copy()); player.getInventory().setItem(4,new ItemStack(Items.BREAD,12));
            player.getInventory().setItem(38,new ItemStack(Items.IRON_CHESTPLATE));
            bag.gear[2]=new ItemStack(Tactical.BACKPACK.get());
            bag.entries.add(new BagState.Entry(bag.nextId++,1,0,0,false,new ItemStack(Items.DIAMOND,7)));
            bag.entries.add(new BagState.Entry(bag.nextId++,1,1,0,false,new ItemStack(Items.IRON_AXE)));
            bag.save(player);
        });
        Minecraft.getInstance().player.connection.sendCommand("raid join");
    }
    private static void verifyDeathDrops(ServerPlayer player) {
        var drops=player.serverLevel().getEntitiesOfClass(ItemEntity.class,new AABB(-16,floorY-8,-16,16,floorY+8,16));
        require(drops.stream().noneMatch(entity->Rules.melee(entity.getItem())),"Protected melee dropped");
        require(drops.stream().filter(entity->entity.getItem().is(Items.BREAD)).mapToInt(entity->entity.getItem().getCount()).sum()==12,"Pocket loot drop wrong");
        require(drops.stream().filter(entity->entity.getItem().is(Items.DIAMOND)).mapToInt(entity->entity.getItem().getCount()).sum()==7,"Storage loot drop wrong");
        require(drops.stream().anyMatch(entity->entity.getItem().is(Tactical.BACKPACK.get())),"Backpack did not drop");
        require(drops.stream().anyMatch(entity->entity.getItem().is(Items.IRON_CHESTPLATE)),"Armor did not drop");
    }
    private static CompoundTag snapshot() { return value(RaidManager::snapshot); }
    private static void server(Consumer<ServerPlayer> work) { value(player -> {work.accept(player); return true;}); }
    private static <T> T value(Function<ServerPlayer,T> work) {
        var mc=Minecraft.getInstance(); var id=mc.player.getUUID();
        return mc.getSingleplayerServer().submit(()->work.apply(mc.getSingleplayerServer().getPlayerList().getPlayer(id))).join();
    }
    private static void command(ServerPlayer player,String text) {
        player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4),text);
    }
    private static void teleport(ServerPlayer player,double x,double z) { player.teleportTo(player.serverLevel(),x,floorY,z,0,0); }
    private static void require(boolean okay,String message) { if(!okay) throw new IllegalStateException(message); }
    private static void log(String message) { LoggerFactory.getLogger("RaidSmoke").info(message); }
}
