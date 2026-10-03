package dev.tactical.profile.client;

import dev.tactical.client.BagScreen;
import dev.tactical.profile.ItemProfiles;
import dev.tactical.profile.ProfilePackets;
import dev.tactical.profile.ProfileSavedData;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID,value=Dist.CLIENT)
public final class ProfileClient {
    private static boolean editable;
    public static boolean editable() { return editable; }
    public static void requestOpen() { if(Minecraft.getInstance().getConnection()!=null) PacketDistributor.sendToServer(new ProfilePackets.Request(true)); }
    public static void accept(ProfilePackets.State packet) {
        var mc=Minecraft.getInstance(); if(mc.player==null || mc.level==null) return;
        var data=ProfileSavedData.read(packet.data());
        boolean changed=data.revision()!=ItemProfiles.client().revision();
        ItemProfiles.client(data); editable=packet.editable();
        if(changed && mc.screen instanceof BagScreen bag) bag.profilesChanged();
        if(mc.screen instanceof ProfileScreen editor) editor.accept(packet);
        else if(packet.open()) {
            mc.player.closeContainer(); mc.setScreen(new ProfileScreen(editable));
        }
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { ItemProfiles.clearClient(); editable=false; }
}
