package dev.herrastudio.tacticalactions.visualtest;

import com.mojang.logging.LogUtils;
import dev.herrastudio.tacticalactions.TacticalActions;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import java.nio.file.Path;

/** Optional local GWO pack verification, never included in the published mod. */
@EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID,value=Dist.CLIENT)
public final class GwoAttackVisualTest {
    private static int ticks;
    private static boolean started;
    private static String capture;
    @SubscribeEvent
    public static void tick(ClientTickEvent.Pre event) throws Exception {
        if(!Boolean.getBoolean("tacticalactions.attackTest")) return;
        var mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null || mc.getSingleplayerServer()==null || mc.screen!=null) return;
        if(!started) {
            if(!mc.gameDirectory.toPath().toRealPath().equals(Path.of(System.getProperty("tacticalactions.visualTest.expectedGameDir")).toRealPath())) throw new IllegalStateException("Only project run world allowed");
            started=true;
            mc.options.pauseOnLostFocus=false;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.hideGui=false;
            mc.options.bobView().set(false);
            mc.options.keyJump.setDown(false);mc.options.keyShift.setDown(false);
            var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
            server.execute(()->{
                var p=server.getPlayerList().getPlayer(id);
                p.setGameMode(GameType.CREATIVE);
                p.getInventory().clearContent();p.getInventory().selected=0;
                p.teleportTo(p.serverLevel(),0.5,-60,0.5,0,0);
                server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4),"gwo give melee \"gwo_meleepack:katana\"");
            });
        }
        mc.player.setYRot(0);mc.player.yRotO=0;mc.player.setXRot(0);mc.player.xRotO=0;
        ticks++;
        if(ticks==95) capture="idle.png";
        int elapsed=ticks-110;
        if(elapsed>=0 && elapsed<66) {
            int frame=elapsed%22,attack=elapsed/22+1;
            mc.options.keyAttack.setDown(frame<2);
            if(frame==0) {KeyMapping.click(mc.options.keyAttack.getKey());LogUtils.getLogger().info("[GwoAttackTest] attack {} held {}",attack,mc.player.getMainHandItem());}
            if(frame>=1 && frame<=20) capture="attack-"+attack+"-tick-"+frame+".png";
        }
        if(ticks==180) {mc.options.keyAttack.setDown(false);capture="return-idle.png";}
        if(ticks==190) {LogUtils.getLogger().info("[GwoAttackTest] COMPLETE");mc.stop();}
    }
    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if(capture==null)return;
        var mc=Minecraft.getInstance();String file=capture;capture=null;
        var directory=mc.gameDirectory.toPath().resolve("gwo-attack-test").toFile();directory.mkdirs();
        Screenshot.grab(directory,file,mc.getMainRenderTarget(),m->LogUtils.getLogger().info("[GwoAttackTest] {}",m.getString()));
    }
}
