package dev.tactical.crack.client;

import dev.tactical.Tactical;
import dev.tactical.crack.CrackPackets;
import dev.tactical.loot.SafeBlockEntity;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID,value=Dist.CLIENT)
public final class CrackClient {
    private static String cancelledSession="";
    public static void cancelled(UUID session) { cancelledSession=session.toString(); }
    public static final KeyMapping KEY=new KeyMapping("key.tactical_inventory.decrypt",GLFW.GLFW_KEY_F,"key.categories.tactical_inventory");
    public static BlockPos target() {
        var mc=Minecraft.getInstance(); if(mc.player==null || mc.level==null) return null;
        var eye=mc.player.getEyePosition(); var end=eye.add(mc.player.getLookAngle().scale(4));
        BlockPos winner=null; double distance=Double.MAX_VALUE;
        var base=mc.player.blockPosition();
        for(BlockPos pos:BlockPos.betweenClosed(base.offset(-4,-2,-4),base.offset(4,2,4))) {
            if(!mc.level.getBlockState(pos).is(Tactical.SAFE.get())) continue;
            var hit=mc.level.getBlockState(pos).getShape(mc.level,pos).bounds().move(pos).clip(eye,end);
            if(hit.isPresent() && eye.distanceToSqr(hit.get())<distance && mc.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))<=12.25) {
                winner=pos.immutable(); distance=eye.distanceToSqr(hit.get());
            }
        }
        return winner;
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        while(KEY.consumeClick()) if(mc.player!=null && mc.screen==null) {
            var pos=target();
            if(pos!=null) {
                while(mc.options.keySwapOffhand.consumeClick()) {}
                PacketDistributor.sendToServer(new CrackPackets.Start(pos));
            }
        }
    }
    @SubscribeEvent public static void key(net.neoforged.neoforge.client.event.InputEvent.Key event) {
        var mc=Minecraft.getInstance();
        if(event.getKey()==GLFW.GLFW_KEY_F && event.getAction()==GLFW.GLFW_PRESS && mc.screen==null && target()!=null)
            while(mc.options.keySwapOffhand.consumeClick()) {}
    }
    public static void accept(CrackPackets.State packet) {
        var mc=Minecraft.getInstance(); if(mc.level==null || mc.player==null) return;
        if(!(mc.level.getBlockEntity(packet.pos()) instanceof SafeBlockEntity safe)) return;
        safe.deserializeInitialData(mc.player.registryAccess(),packet.desc());
        if(mc.screen instanceof CrackScreen screen && screen.session.toString().equals(safe.hackSession)) screen.accept(packet.notice());
        else if(safe.hackStatus.equals("active") && !safe.hackSession.isBlank()
                && !safe.hackSession.equals(cancelledSession)) mc.setScreen(new CrackScreen(safe));
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { KEY.setDown(false); cancelledSession=""; }
    @EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event) { event.register(KEY); CrackPackets.receiver=CrackClient::accept; }
    }
}
