package dev.tactical.loot.client;

import dev.tactical.loot.*;
import dev.tactical.crack.client.CrackClient;
import java.lang.reflect.Method;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID,value=Dist.CLIENT)
public final class LootClient {
    public static final KeyMapping SEARCH=new KeyMapping("key.tactical_inventory.search",GLFW.GLFW_KEY_F,"key.categories.tactical_inventory");
    private static boolean editable,pickupResolved;
    private static Method pickupTarget;
    public static boolean editable() { return editable; }
    /** The existing gun pickup owns F while its prompt has a target. It remains an optional addon. */
    private static boolean gunTarget(Player player) {
        if(!pickupResolved) {
            pickupResolved=true;
            if(ModList.get().isLoaded("gwo_pickup")) try {
                pickupTarget=Class.forName("dev.gwopickup.Targeting").getMethod("find",Player.class);
            } catch(ReflectiveOperationException ignored) { }
        }
        if(pickupTarget!=null) try { return pickupTarget.invoke(null,player)!=null; }
        catch(ReflectiveOperationException ignored) { pickupTarget=null; }
        return false;
    }
    public static BlockPos target() {
        var mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null || mc.screen!=null || mc.player.isSpectator() || gunTarget(mc.player)) return null;
        return LootTargeting.find(mc.player);
    }
    public static Component name(BlockPos pos) {
        var mc=Minecraft.getInstance(); var state=mc.level.getBlockState(pos); var rule=LootSettings.rule(state);
        if(rule!=null && !rule.name().isEmpty()) return Component.literal(rule.name());
        if(mc.level.getBlockEntity(pos) instanceof net.minecraft.world.Nameable named) return named.getDisplayName();
        return state.getBlock().getName();
    }
    public static void handleKeys() {
        var mc=Minecraft.getInstance(); boolean pressed=false;
        while(SEARCH.consumeClick()) pressed=true;
        if(!pressed || mc.player==null || mc.screen!=null || mc.getConnection()==null) return;
        var pos=target(); if(pos==null) return;
        // Consume the same physical key before vanilla and the optional pickup addon process it.
        for(var mapping:mc.options.keyMappings) if(mapping!=SEARCH && mapping.getKey().equals(SEARCH.getKey())
                && (mapping==mc.options.keySwapOffhand || mapping==CrackClient.KEY || mapping.getName().equals("key.gwo_pickup.pickup")))
            while(mapping.consumeClick()) { }
        PacketDistributor.sendToServer(new LootPackets.Open(pos));
    }
    public static void requestOpen() {
        if(Minecraft.getInstance().getConnection()!=null) PacketDistributor.sendToServer(new LootPackets.Request(true));
    }
    public static void accept(LootPackets.State packet) {
        var mc=Minecraft.getInstance(); if(mc.player==null || mc.level==null) return;
        LootSettings.client(LootConfigData.load(packet.data(),mc.player.registryAccess()).snapshot()); editable=packet.editable();
        if(mc.screen instanceof LootAdminScreen screen) screen.accept(packet);
        else if(packet.open()) { mc.player.closeContainer(); mc.setScreen(new LootAdminScreen(editable)); }
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        LootSettings.clearClient(); editable=false; SEARCH.setDown(false); while(SEARCH.consumeClick()) { }
    }
    @EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event) { event.register(SEARCH); LootPackets.receiver=LootClient::accept; }
        @SubscribeEvent public static void layers(RegisterGuiLayersEvent event) {
            event.registerAboveAll(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("raidcore","loot_prompt"),(graphics,delta)->{
                var mc=Minecraft.getInstance(); if(mc.options.hideGui) return;
                var pos=target(); if(pos==null) return;
                String key=SEARCH.getTranslatedKeyMessage().getString(); int keyWidth=Math.max(14,mc.font.width(key)+6);
                int x=mc.getWindow().getGuiScaledWidth()/2-90,y=mc.getWindow().getGuiScaledHeight()/2+20;
                graphics.fill(x,y,x+keyWidth,y+14,0xFFF5F5F5);
                graphics.drawString(mc.font,key,x+(keyWidth-mc.font.width(key))/2,y+3,0xFF171717,false);
                graphics.drawString(mc.font,"搜刮",x+keyWidth+8,y+3,0xFFFFFFFF,true);
                String name=mc.font.plainSubstrByWidth(name(pos).getString(),180);
                graphics.drawString(mc.font,name,x,y+19,0xFFDBE8F2,true);
                if(mc.level.getBlockEntity(pos) instanceof SafeBlockEntity safe && !safe.getBlockState().getValue(SafeBlock.OPEN))
                    graphics.drawString(mc.font,"需先破译",x,y+32,0xFFC6A570,true);
            });
        }
    }
    private LootClient() {}
}
