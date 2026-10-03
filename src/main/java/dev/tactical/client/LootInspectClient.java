package dev.tactical.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sgr792.gwo.client.animation.AnimationPose;
import com.sgr792.gwo.client.input.ClientInputEventHandler;
import com.sgr792.gwo.client.render.GwoRenderPass;
import dev.tactical.Tactical;
import dev.tactical.loot.InspectableLootItem;
import dev.tactical.loot.LootInspectMotion;
import dev.tactical.profile.ItemProfiles;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;

@EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID,value=Dist.CLIENT)
public final class LootInspectClient {
    public static final LootInspectMotion MOTION=new LootInspectMotion();
    private static ItemStack held=ItemStack.EMPTY;
    private static InteractionHand hand=InteractionHand.MAIN_HAND;
    private static int slot=-1;
    public static long handFrames,inspectionStarts;
    public static String renderedClip="";
    public static void reset() { MOTION.cancel(); held=ItemStack.EMPTY; slot=-1; renderedClip=""; }
    private static InteractionHand activeHand() {
        var mc=Minecraft.getInstance(); if(mc.player==null) return null;
        if(mc.player.getMainHandItem().getItem() instanceof InspectableLootItem) return InteractionHand.MAIN_HAND;
        if(mc.player.getOffhandItem().getItem() instanceof InspectableLootItem) return InteractionHand.OFF_HAND;
        return null;
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void tick(ClientTickEvent.Pre event) {
        var mc=Minecraft.getInstance(); var next=activeHand();
        if(next==null || mc.player==null || !mc.player.isAlive() || mc.player.isSpectator() || mc.screen!=null) {reset();return;}
        var stack=mc.player.getItemInHand(next);
        if(!MOTION.active() || next!=hand || slot!=mc.player.getInventory().selected || !ItemStack.isSameItemSameComponents(held,stack)) {
            held=stack.copy(); hand=next; slot=mc.player.getInventory().selected; MOTION.equip(Util.getMillis());
        }
        while(ClientInputEventHandler.consumeInspect()) inspect(hand);
    }
    public static void inspect(InteractionHand requested) {
        var mc=Minecraft.getInstance();
        if(mc.player==null || mc.screen!=null || activeHand()!=requested || !(mc.player.getItemInHand(requested).getItem() instanceof InspectableLootItem item)) return;
        if(MOTION.inspect(Util.getMillis(),LootItemRenderer.clipLength(item.model(),"draw",.35f),LootItemRenderer.clipLength(item.model(),"inspect",2.6f))) {
            inspectionStarts++; mc.player.playSound(SoundEvents.ARMOR_EQUIP_GOLD.value(),.4f,1.15f);
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void key(net.neoforged.neoforge.client.event.InputEvent.Key event) {
        if(event.getAction()!=org.lwjgl.glfw.GLFW.GLFW_PRESS || activeHand()==null) return;
        var mc=Minecraft.getInstance();
        for(var key:mc.options.keyMappings) if(key.getName().equals("key.gwo.inspect") && key.isActiveAndMatches(com.mojang.blaze3d.platform.InputConstants.getKey(event.getKey(),event.getScanCode()))) {inspect(activeHand());break;}
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void mouse(net.neoforged.neoforge.client.event.InputEvent.MouseButton.Post event) {
        if(event.getAction()!=org.lwjgl.glfw.GLFW.GLFW_PRESS || activeHand()==null) return;
        for(var key:Minecraft.getInstance().options.keyMappings) if(key.getName().equals("key.gwo.inspect") && key.isActiveAndMatches(com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(event.getButton()))) {inspect(activeHand());break;}
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void render(RenderHandEvent event) {
        var mc=Minecraft.getInstance(); var active=activeHand(); if(active==null || mc.player==null) return;
        if(active==InteractionHand.MAIN_HAND && event.getHand()==InteractionHand.OFF_HAND) {event.setCanceled(true);return;}
        if(event.getHand()!=active || !(event.getItemStack().getItem() instanceof InspectableLootItem item)) return;
        event.setCanceled(true);
        float draw=LootItemRenderer.clipLength(item.model(),"draw",.35f),idle=LootItemRenderer.clipLength(item.model(),"idle",1.5f),inspect=LootItemRenderer.clipLength(item.model(),"inspect",2.6f);
        var sample=MOTION.sample(Util.getMillis(),draw,idle,inspect); renderedClip=sample.clip(); handFrames++;
        var animation=LootItemRenderer.pose(item.model(),sample.clip(),sample.seconds());
        var pose=event.getPoseStack(); pose.pushPose();
        boolean right=mc.player.getMainArm()==HumanoidArm.RIGHT;
        if(active==InteractionHand.OFF_HAND) right=!right;
        pose.translate(right?.18:-.18,-.28,-.63);
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(24));
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(right?-18:18));
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(right?-8:8));
        pose.scale(1.25f,1.25f,1.25f);
        // Skin hands follow the same authored parent animation as the gold mesh.
        if(!mc.player.isInvisible()) {
            skinHand(event,animation,right,true,!right);
            if(active==InteractionHand.MAIN_HAND) skinHand(event,animation,!right,false,!right);
        }
        LootItemRenderer.draw(item.model(),animation,pose,event.getMultiBufferSource(),event.getPackedLight(),net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,GwoRenderPass.FIRST_PERSON,!right);
        pose.popPose();
    }
    private static void skinHand(RenderHandEvent event,AnimationPose animation,boolean right,boolean primary,boolean leftScene) {
        var mc=Minecraft.getInstance(); var pose=event.getPoseStack(); pose.pushPose();
        pose.mulPose(LootItemRenderer.handedPose(animation.global(primary?"hand_right":"hand_left"),leftScene));
        float sign=right?1:-1;
        pose.translate(sign*(primary?.115f:.11f),-.065f,.01f);
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-78));
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(sign*(primary?-12:12)));
        pose.scale(.48f,.48f,.48f);
        pose.translate(sign*.3125f,-.58f,0);
        var renderer=(PlayerRenderer)mc.getEntityRenderDispatcher().getRenderer(mc.player);
        if(right) renderer.renderRightHand(pose,event.getMultiBufferSource(),event.getPackedLight(),mc.player);
        else renderer.renderLeftHand(pose,event.getMultiBufferSource(),event.getPackedLight(),mc.player);
        pose.popPose();
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {reset();}
    @SubscribeEvent public static void tooltip(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event) {
        if(event.getItemStack().getItem() instanceof InspectableLootItem && !event.getToolTip().isEmpty())
            event.getToolTip().set(0,event.getToolTip().getFirst().copy().withStyle(style->style.withColor(ItemProfiles.rarity(event.getItemStack()).rgb)));
    }
}
