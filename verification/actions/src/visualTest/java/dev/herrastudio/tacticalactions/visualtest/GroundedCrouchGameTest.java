package dev.herrastudio.tacticalactions.visualtest;

import com.mojang.logging.LogUtils;
import dev.herrastudio.tacticalactions.TacticalActions;
import dev.herrastudio.tacticalactions.TacticalActionsClient;
import dev.herrastudio.tacticalactions.TacticalActionsKeyMappings;
import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranimcore.bones.PlayerAnimBone;
import net.minecraft.client.CameraType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Development-only physics/input regression in the disposable project run world. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class GroundedCrouchGameTest {
    private static boolean initialized;
    private static volatile boolean ready;
    private static int tick;
    private static int checks;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeTick(ClientTickEvent.Pre event) {
        if (!Boolean.getBoolean("tacticalactions.crouchTest")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        if (!initialized) {
            try {
                if (!mc.gameDirectory.toPath().toRealPath().equals(java.nio.file.Path.of(
                        System.getProperty("tacticalactions.visualTest.expectedGameDir")).toRealPath())) {
                    throw new IllegalStateException("Refusing test outside project run directory");
                }
            } catch (Exception e) { throw new IllegalStateException(e); }
            initialized = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.autoJump().set(false);
            mc.options.keyShift.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keySprint.setDown(false);
            var id = mc.player.getUUID();
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var p = server.getPlayerList().getPlayer(id);
                var level = p.serverLevel();
                for (int x = -4; x <= 7; x++) for (int z = -4; z <= 7; z++) {
                    level.setBlockAndUpdate(new BlockPos(x,-61,z),Blocks.STONE.defaultBlockState());
                    for (int y=-60;y<=-56;y++) level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
                }
                level.setBlockAndUpdate(new BlockPos(4,-61,0),Blocks.STONE_SLAB.defaultBlockState());
                p.setGameMode(GameType.SURVIVAL);
                p.teleportTo(level,0.5,-60,0.5,0,0);
                ready = true;
            });
        }
        if (!ready || mc.screen != null) return;
        tick++;
        if (tick == 60) mc.options.keyShift.setDown(true);
        if (tick == 80) mc.options.keyJump.setDown(true);
        if (tick == 81) mc.options.keyJump.setDown(false); // A single tick tap must still jump.
        if (tick == 125) mc.options.keyShift.setDown(false);
        if (tick == 140) {
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(true);
        }
        if (tick == 150) mc.options.keyShift.setDown(true);
        if (tick == 155) mc.options.keyShift.setDown(false);
        if (tick == 160) {
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyShift.setDown(false);
            var id=mc.player.getUUID();
            var server=mc.getSingleplayerServer();
            server.execute(()->{var p=server.getPlayerList().getPlayer(id);p.teleportTo(p.serverLevel(),4.5,-60.5,0.5,0,0);});
        }
        if (tick == 180) mc.options.keyShift.setDown(true);
        if (tick == 200) {
            var server=mc.getSingleplayerServer();
            server.execute(()->server.overworld().setBlockAndUpdate(new BlockPos(4,-59,0),Blocks.STONE.defaultBlockState()));
        }
        if (tick == 215) mc.options.keyJump.setDown(true);
        if (tick == 230) {
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            var id=mc.player.getUUID();var server=mc.getSingleplayerServer();
            server.execute(()->{var p=server.getPlayerList().getPlayer(id);p.teleportTo(p.serverLevel(),0.5,-60,0.5,0,0);});
        }
        if(tick==245) mc.options.keyJump.setDown(true);
        if(tick==246) mc.options.keyJump.setDown(false);
        if(tick==249) mc.options.keyShift.setDown(true);
        if(tick==280) mc.options.keyShift.setDown(false);
        if(tick==283) mc.options.keyShift.setDown(true);
        if(tick==286) mc.options.keyShift.setDown(false);
        if(tick==290) {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            TacticalActionsKeyMappings.LEAN_LEFT.setDown(true);
        }
        if(tick==305) TacticalActionsKeyMappings.LEAN_RIGHT.setDown(true);
        if(tick==320) TacticalActionsKeyMappings.LEAN_LEFT.setDown(false);
        if(tick==335) TacticalActionsKeyMappings.LEAN_LEFT.setDown(true);
        if(tick==350) TacticalActionsKeyMappings.LEAN_RIGHT.setDown(false);
        if(tick>=365 && tick<405) {
            TacticalActionsKeyMappings.LEAN_LEFT.setDown(tick%2==0);
            TacticalActionsKeyMappings.LEAN_RIGHT.setDown(tick%3==0);
        }
        if(tick==405) {
            TacticalActionsKeyMappings.LEAN_LEFT.setDown(false);
            TacticalActionsKeyMappings.LEAN_RIGHT.setDown(false);
        }
        if(tick==425) {
            LogUtils.getLogger().info("[CrouchGameTest] COMPLETE: {} runtime assertions passed",checks);
            mc.stop();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterTick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("tacticalactions.crouchTest") || !ready) return;
        var p=Minecraft.getInstance().player;
        if(p==null)return;
        switch(tick) {
            case 70 -> check(p.onGround() && p.isCrouching(),"Grounded Shift crouches");
            case 80 -> check(p.onGround() && !p.isCrouching() && p.getPose()==Pose.STANDING,
                    "Crouch-jump first stands without taking off");
            case 81 -> check(p.getY()>-60 && !p.isCrouching(),"Tapped jump executes after standing");
            case 86 -> check(!p.onGround() && !p.isCrouching() && !p.input.shiftKeyDown,
                    "Held Shift cannot crouch in midair");
            case 120 -> check(p.onGround() && !p.isCrouching() && p.getPose()==Pose.STANDING,"Crouch-jump lands standing despite held Shift");
            case 147 -> check(p.isSprinting(),"Sprint baseline is active");
            case 151 -> check(p.isCrouching() && !p.isSprinting(),"Sprint can transition straight to crouch");
            case 158 -> check(!p.isCrouching() && p.isSprinting(),"Held sprint resumes after crouch released");
            case 190 -> check(p.onGround() && p.isCrouching() && Math.abs(p.getY()+60.5)<0.01,
                    "Slab collision surface supports crouching");
            case 218 -> check(p.onGround() && p.isCrouching() && Math.abs(p.getY()+60.5)<0.01,
                    "Low ceiling prevents forced standing/jumping");
            case 251 -> check(!p.onGround() && !p.isCrouching(),"Midair fresh crouch press is rejected");
            case 276 -> check(p.onGround() && !p.isCrouching(),"Midair press is not cached for landing");
            case 285 -> check(p.isCrouching(),"Fresh ground crouch press rearms after landing");
            case 303,363 -> checkLean(1,"Q left");
            case 318,348 -> checkLean(0,"Both keys neutral");
            case 333 -> checkLean(-1,"Remaining E restores right");
            case 423 -> checkLean(0,"Rapid alternating input releases without residual lean");
            default -> { }
        }
    }
    private static void checkLean(int sign,String message) {
        var mc=Minecraft.getInstance();
        var layer=PlayerAnimationAccess.getPlayerAnimationLayer(mc.player,
                ResourceLocation.fromNamespaceAndPath(TacticalActions.MOD_ID,"peek"));
        var head=layer.get3DTransform(new PlayerAnimBone("head"));
        var camera=mc.gameRenderer.getMainCamera();
        // getEyePosition already includes the virtual peek origin; compare the body center.
        double dx=camera.getPosition().x-mc.player.getX();
        if(sign==0) check(Math.abs(TacticalActionsClient.leanAt(1))<0.01 && Math.abs(head.positionX)<0.05,message);
        else check(head.positionX*sign>0.5 && dx*sign>0.15 && head.rotZ*sign>0,message+" camera and upper-body directions agree");
    }
    private static void check(boolean condition,String label) {
        if(!condition) {
            var p=Minecraft.getInstance().player;
            LogUtils.getLogger().error("[CrouchGameTest] FAILED {} tick={} pos={} pose={} ground={} shift={} jump={}",
                    label,tick,p.position(),p.getPose(),p.onGround(),p.input.shiftKeyDown,p.input.jumping);
            Minecraft.getInstance().stop();
            throw new AssertionError(label);
        }
        checks++;
        LogUtils.getLogger().info("[CrouchGameTest] PASS {}",label);
    }
}
