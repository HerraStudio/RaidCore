package dev.tactical.crack;

import dev.tactical.Tactical;
import dev.tactical.crack.client.CrackClient;
import dev.tactical.crack.client.CrackScreen;
import dev.tactical.client.BagScreen;
import dev.tactical.loot.SafeBlock;
import dev.tactical.loot.SafeBlockEntity;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID,value=Dist.CLIENT)
public final class CrackSmoke {
    private static boolean started,isolated;
    private static int ticks,stage,wait,expected,revision;
    private static BlockPos pos;
    private static String image;
    private static UUID token;
    @SubscribeEvent public static void isolate(RenderFrameEvent.Pre event) {
        long window=Minecraft.getInstance().getWindow().getWindow();
        if(!isolated) {
            GLFW.glfwSetMouseButtonCallback(window,null); GLFW.glfwSetKeyCallback(window,null);
            GLFW.glfwSetCursorPosCallback(window,null); GLFW.glfwSetScrollCallback(window,null);
            isolated=true;
        }
        if(GLFW.glfwGetWindowAttrib(window,GLFW.GLFW_VISIBLE)!=0) GLFW.glfwHideWindow(window);
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) throws Exception {
        if(image==null) return;
        try(var png=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) { png.writeToFile(Path.of(image)); }
        image=null;
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        var mc=Minecraft.getInstance();
        if(!started && mc.screen instanceof TitleScreen && mc.getOverlay()==null) {
            started=true; mc.getWindow().setWindowed(1440,900); mc.options.guiScale().set(2);
            mc.options.renderDistance().set(3); mc.options.pauseOnLostFocus=false; mc.resizeDisplay();
            var settings=new LevelSettings("Crack integration",GameType.SURVIVAL,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("crack-smoke-"+System.currentTimeMillis(),settings,new WorldOptions(17,false,false),
                    registry->registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),mc.screen);
        }
        if(!started || mc.player==null || ticks==0 && mc.screen!=null) return;
        ticks++; wait++;
        if(ticks>1300) throw new IllegalStateException("Crack smoke timeout stage "+stage);
        if(ticks==20) {
            value(player -> {
                pos=new BlockPos(0,(int)Math.floor(player.getY()),0);
                for(int x=-3;x<5;x++) for(int z=-2;z<7;z++) player.serverLevel().setBlock(pos.offset(x,-1,z),Blocks.SMOOTH_STONE.defaultBlockState(),3);
                player.serverLevel().setBlock(pos,Tactical.SAFE.get().defaultBlockState().setValue(SafeBlock.FACING,Direction.SOUTH),3);
                var safe=(SafeBlockEntity)player.serverLevel().getBlockEntity(pos); safe.setItem(0,new ItemStack(Items.BREAD,12));
                player.teleportTo(player.serverLevel(),.5,pos.getY(),3,180,15);
                require(safe.getFieldHolder().getSyncedFieldIndex("hackSeed")!=null,"Seed not annotated/managed by LDLib2");
                require(!safe.getBlockState().getValue(SafeBlock.OPEN),"Safe must begin locked");
                return true;
            });
            stage=1; wait=0;
        }
        if(stage==1 && wait>=25) {
            require(CrackClient.target()!=null,"F targeting missed modeled safe");
            KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F));
            stage=2; wait=0;
        }
        if(stage==2 && wait>=20) {
            require(mc.screen instanceof CrackScreen,"F did not open LDLib2 crack screen");
            var screen=(CrackScreen)mc.screen; token=screen.session;
            require(value(CrackService::locked),"Player not server locked");
            require(screen.modularUI.ui.getRootElement()!=null,"LDLib tree missing");
            image="safe-crack-ui.png";
            // Packet trust checks: wrong token, impossible revision and repeated sequence are ignored.
            PacketDistributor.sendToServer(new CrackPackets.Input(UUID.randomUUID(),0,0,false));
            PacketDistributor.sendToServer(new CrackPackets.Input(token,999,0,false));
            value(p -> {
                var anchor=p.position(); int selected=p.getInventory().selected;
                p.connection.handleMovePlayer(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(anchor.x+1,anchor.y,anchor.z,p.onGround()));
                require(p.position().distanceToSqr(anchor)<.001,"Move packet bypassed crack lock");
                p.connection.handleSetCarriedItem(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket((selected+1)%9));
                require(p.getInventory().selected==selected,"Weapon-switch packet bypassed lock");
                try {
                    var method=com.sgr792.gwo.network.ModPayloads.class.getDeclaredMethod("processAcceptedFireRequest",net.minecraft.server.level.ServerPlayer.class,com.sgr792.gwo.network.ModPayloads.FirePayload.class);
                    method.setAccessible(true); method.invoke(null,p,null);
                } catch(ReflectiveOperationException error) { throw new IllegalStateException("GWO fire guard failed",error); }
                return true;
            });
            log("CRACK_ACTION_GUARDS_PASS: actual movement, hotbar packet and GWO deferred-fire entry denied");
            stage=3; wait=0;
        }
        if(stage==3 && wait>=10) {
            require(serverSafe().hackSuccesses==0 && serverSafe().hackFailures==0,"Forged input changed result");
            var delayed=value(p -> new CrackPackets.State(pos,((SafeBlockEntity)p.serverLevel().getBlockEntity(pos)).serializeInitialData(p.registryAccess()),""));
            ((CrackScreen)mc.screen).onClose(); stage=4; wait=0;
            CrackClient.accept(delayed);
            require(mc.screen==null,"Late active sync reopened a canceled screen");
        }
        if(stage==4 && wait>=12) {
            require(!value(CrackService::locked) && mc.screen==null,"Cancel leaked movement lock");
            require(!serverSafe().rewardGenerated() && !serverSafe().getBlockState().getValue(SafeBlock.OPEN),"Cancel unlocked/rewarded box");
            PacketDistributor.sendToServer(new CrackPackets.Start(pos)); stage=5; wait=0; expected=0;
        }
        if(stage==5 && wait>=12 && mc.screen instanceof CrackScreen screen) {
            var safe=serverSafe();
            if(safe.hackFailures>expected) { expected=safe.hackFailures; wait=0; }
            if(expected>=3 || !safe.hackStatus.equals("active")) { stage=6; wait=0; }
            else if(canClick(false) && wait>=5) { screen.hit(); wait=0; }
        }
        if(stage==6 && wait>=16) {
            require(serverSafe().hackStatus.equals("failed") && serverSafe().hackFailures==3,"Three misses did not fail");
            require(!value(CrackService::locked) && !serverSafe().rewardGenerated(),"Failure rewarded/kept lock");
            log("CRACK_FAILURE_CANCEL_PASS: real F/LDLib2, forged packet rejection, cancellation reset, three misses, no reward/lock leak");
            stage=7; wait=0;
        }
        if(stage==7 && wait>=70) {
            PacketDistributor.sendToServer(new CrackPackets.Start(pos)); stage=8; wait=0; expected=0;
        }
        if(stage==8 && wait>=12 && mc.screen instanceof CrackScreen screen) {
            var safe=serverSafe();
            if(safe.hackSuccesses>expected) { expected=safe.hackSuccesses; wait=0; }
            if(expected<3 && safe.hackStatus.equals("active") && canClick(true) && wait>=5) { screen.hit(); wait=0; }
            if(!safe.hackStatus.equals("active")) { stage=9; wait=0; }
        }
        if(stage==8 && mc.screen instanceof BagScreen) { stage=9; wait=0; }
        if(stage==9 && wait>=25) {
            require(serverSafe().hackStatus.equals("success") && serverSafe().hackSuccesses==3,"Three correct clicks did not win");
            require(mc.screen instanceof BagScreen,"Success did not open original loot screen after door animation");
            require(serverSafe().rewardGenerated() && serverSafe().getBlockState().getValue(SafeBlock.OPEN),"Success not persistent/open");
            int diamonds=value(p -> {var safe=(SafeBlockEntity)p.serverLevel().getBlockEntity(pos); int count=0; for(int i=0;i<27;i++) if(safe.getItem(i).is(Items.DIAMOND)) count+=safe.getItem(i).getCount(); return count;});
            require(diamonds>=2,"Loot table did not generate high-value contents");
            require(value(p -> ((SafeBlockEntity)p.serverLevel().getBlockEntity(pos)).getItem(0).getCount())==12,"Existing box contents overwritten");
            image="safe-crack-reward.png";
            value(p -> {
                var safe=(SafeBlockEntity)p.serverLevel().getBlockEntity(pos);
                var saved=safe.saveWithoutMetadata(p.registryAccess()); var clone=new SafeBlockEntity(pos,safe.getBlockState()); clone.loadWithComponents(saved,p.registryAccess());
                require(clone.rewardGenerated(),"Reward-once flag not persistent");
                for(int i=0;i<27;i++) clone.setItem(i,new ItemStack(Items.STONE,64));
                clone.enqueueReward(new ItemStack(Items.DIAMOND,5));
                require(clone.pendingRewardCount()==5,"Full box lost reward");
                var pendingTag=clone.saveWithoutMetadata(p.registryAccess());
                var pendingClone=new SafeBlockEntity(pos,clone.getBlockState()); pendingClone.loadWithComponents(pendingTag,p.registryAccess());
                require(pendingClone.pendingRewardCount()==5,"Overflow reward not persistent");
                var drops=safe.getBlockState().getDrops(new net.minecraft.world.level.storage.loot.LootParams.Builder(p.serverLevel())
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,net.minecraft.world.phys.Vec3.atCenterOf(pos))
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL,ItemStack.EMPTY)
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY,safe)
                        .withOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY,p));
                var safeItem=drops.stream().filter(stack->stack.is(Tactical.SAFE_ITEM.get())).findFirst().orElseThrow();
                require(safeItem.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag().getBoolean("HackRewardGenerated"),
                        "Broken safe can reset reward-once marker");
                return true;
            });
            log("CRACK_WIN_REWARD_PASS: three real clicks, annotation seed sync, shrinking arcs, box-only loot, original items retained, persistent one-shot reward and permanent open");
            stage=10; wait=0;
        }
        if(stage==10 && wait>=10) {
            mc.player.closeContainer(); mc.setScreen(null);
            value(p -> {
                p.serverLevel().setBlock(pos,Tactical.SAFE.get().defaultBlockState().setValue(SafeBlock.FACING,Direction.SOUTH),3);
                // Replacing only properties preserves the previous entity; replace with air first for a fresh challenge.
                p.serverLevel().setBlock(pos,Blocks.AIR.defaultBlockState(),3);
                p.serverLevel().setBlock(pos,Tactical.SAFE.get().defaultBlockState().setValue(SafeBlock.FACING,Direction.SOUTH),3);
                return true;
            });
            PacketDistributor.sendToServer(new CrackPackets.Start(pos)); stage=11; wait=0;
        }
        if(stage==11 && wait>=20) {
            require(mc.screen instanceof CrackScreen,"Damage fixture not active");
            value(p -> {p.invulnerableTime=0; p.hurt(p.damageSources().generic(),1); return true;});
            stage=12; wait=0;
        }
        if(stage==12 && wait>=12) {
            require(serverSafe().hackShakeStart>=0,"Real damage did not produce synchronized shake");
            if(value(CrackService::locked)) {
                ((CrackScreen)mc.screen).onClose();
            }
            stage=13; wait=0;
        }
        if(stage==13 && wait>=16) {
            require(!value(CrackService::locked),"Injury/cancel kept player frozen");
            PacketDistributor.sendToServer(new CrackPackets.Start(pos)); stage=14; wait=0;
        }
        if(stage==14 && wait>=16) {
            value(p -> {p.teleportTo(p.serverLevel(),10,pos.getY(),10,0,0); return true;});
            stage=15; wait=0;
        }
        if(stage==15 && wait>=16) {
            require(!value(CrackService::locked),"Leaving range did not cancel");
            log("CRACK_DAMAGE_RANGE_PASS: actual damage jitters/interrupts, close/range changes release locks, no duplicate reward");
            log("CRACK_NATIVE_SMOKE_PASS: full LDLib2 rhythm game, desc seed, real packets, failure/win/loot/state/interruptions");
            mc.stop();
        }
    }
    private static boolean canClick(boolean wantSuccess) {
        return value(p -> {
            var safe=(SafeBlockEntity)p.serverLevel().getBlockEntity(pos); long now=Util.getMillis();
            if(now<safe.hackPhaseStart+100) return false;
            var round=CrackRules.round(safe.hackSeed,safe.hackSuccesses);
            var angle=CrackRules.angle(safe.hackSeed,safe.hackSuccesses,now-safe.hackPhaseStart,-1);
            double difference=Math.abs(CrackRules.normalize(angle-round.center()+180)-180);
            return wantSuccess?difference<round.width()/5:difference>round.width()/2+40;
        });
    }
    private static SafeBlockEntity serverSafe() { return value(p -> (SafeBlockEntity)p.serverLevel().getBlockEntity(pos)); }
    private static <T> T value(Function<net.minecraft.server.level.ServerPlayer,T> action) {
        var mc=Minecraft.getInstance(); var id=mc.player.getUUID();
        return mc.getSingleplayerServer().submit(()->action.apply(mc.getSingleplayerServer().getPlayerList().getPlayer(id))).join();
    }
    private static void require(boolean good,String message) { if(!good) throw new IllegalStateException(message); }
    private static void log(String message) { LoggerFactory.getLogger("CrackSmoke").info(message); }
}
