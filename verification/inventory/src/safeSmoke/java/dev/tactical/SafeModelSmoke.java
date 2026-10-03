package dev.tactical;

import dev.tactical.client.BagScreen;
import dev.tactical.client.SafeRenderer;
import dev.tactical.loot.LootContainers;
import dev.tactical.loot.SafeBlock;
import dev.tactical.loot.SafeBlockEntity;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

/** Visual and container checks in an owned hidden window and new temporary world. */
@EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID,value=Dist.CLIENT)
public final class SafeModelSmoke {
    private static boolean started,isolated;
    private static int ticks;
    private static BlockPos first;
    private static String screenshot;
    private static boolean openingCaptured;
    private static final java.util.List<Float> doorFrames=new java.util.ArrayList<>();
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
        var mc=Minecraft.getInstance();
        if(ticks>=120 && ticks<170 && mc.level!=null && mc.level.getBlockEntity(first) instanceof SafeBlockEntity safe) {
            float angle=SafeRenderer.animatedAngle(safe,1);
            doorFrames.add(angle);
            if(!openingCaptured && angle>20 && angle<58 && mc.screen==null) {
                openingCaptured=true;
                screenshot="safe-door-opening.png";
            }
        }
        if(screenshot==null) return;
        try(var image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            image.writeToFile(Path.of(screenshot));
        }
        screenshot=null;
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        var mc=Minecraft.getInstance();
        if(!started && mc.screen instanceof TitleScreen && mc.getOverlay()==null) {
            started=true; mc.getWindow().setWindowed(1440,900); mc.options.guiScale().set(2);
            mc.options.renderDistance().set(3); mc.options.pauseOnLostFocus=false; mc.resizeDisplay();
            var settings=new LevelSettings("Safe model QA",GameType.SURVIVAL,false,Difficulty.PEACEFUL,true,
                    new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("safe-model-"+System.currentTimeMillis(),settings,
                    new WorldOptions(13,false,false), registry->registry.registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),mc.screen);
        }
        if(!started || mc.player==null || ticks==0 && mc.screen!=null) return;
        ticks++;
        if(ticks==20) {
            server(player -> {
                first=new BlockPos(0,(int)Math.floor(player.getY()),0);
                for(int x=-3;x<=9;x++) for(int z=-2;z<=9;z++)
                    player.serverLevel().setBlock(first.offset(x,-1,z),Blocks.SMOOTH_STONE.defaultBlockState(),3);
                var directions=new Direction[]{Direction.SOUTH,Direction.WEST,Direction.NORTH,Direction.EAST};
                for(int index=0;index<4;index++) player.serverLevel().setBlock(first.offset(index*2,0,0),
                        Tactical.SAFE.get().defaultBlockState().setValue(SafeBlock.FACING,directions[index]),3);
                var state=Tactical.SAFE.get().defaultBlockState();
                require(!state.getValue(SafeBlock.OPEN),"New safes must start closed");
                require(state.getValue(SafeBlock.FACING)==Direction.NORTH,"Default old-world facing must be north");
                require(state.rotate(Rotation.CLOCKWISE_90).getValue(SafeBlock.FACING)==Direction.EAST,"Rotation failed");
                require(state.mirror(Mirror.LEFT_RIGHT).getValue(SafeBlock.FACING)==Direction.SOUTH,"Mirror failed");
                var bounds=state.getShape(player.serverLevel(),first).bounds();
                require(bounds.getYsize()>1.37 && bounds.getYsize()<1.38 && bounds.getXsize()>1.26
                        && Math.abs(bounds.getZsize()-1.2277)<.001,"Closed shape mismatches doubled model");
                var safe=(SafeBlockEntity)player.serverLevel().getBlockEntity(first);
                safe.setItem(0,new ItemStack(Items.DIAMOND,3)); safe.setItem(1,new ItemStack(Items.BREAD,12));
                var saved=safe.saveWithoutMetadata(player.registryAccess());
                var restored=new SafeBlockEntity(first,safe.getBlockState()); restored.loadWithComponents(saved,player.registryAccess());
                require(restored.getContainerSize()==27 && restored.getItem(0).getCount()==3 && restored.getItem(1).getCount()==12,
                        "Model update changed container persistence");
                player.teleportTo(player.serverLevel(),3.5,first.getY(),5.5,180,15);
                player.getInventory().setItem(4,new ItemStack(Tactical.SAFE_ITEM.get()));
            });
        }
        if(ticks==70) {
            for(int index=0;index<4;index++) {
                var state=mc.level.getBlockState(first.offset(index*2,0,0));
                var model=mc.getBlockRenderer().getBlockModel(state);
                var quads=model.getQuads(state,null,RandomSource.create(0),ModelData.EMPTY,null);
                require(quads.size()>=2100,"Safe body mesh missing: "+quads.size());
                var door=mc.getModelManager().getModel(SafeRenderer.DOOR);
                require(door.getQuads(null,null,RandomSource.create(0),ModelData.EMPTY,null).size()>=3300,"Separate animated door missing");
                require(mc.level.getBlockEntity(first.offset(index*2,0,0)) instanceof SafeBlockEntity safe
                        && SafeRenderer.animatedAngle(safe,1)==0,"Placed door is not closed");
                require(model.getParticleIcon(ModelData.EMPTY).contents().name().equals(
                        ResourceLocation.fromNamespaceAndPath("tactical_inventory","block/safe_color")),"Wrong safe texture");
            }
            var itemModel=mc.getItemRenderer().getModel(new ItemStack(Tactical.SAFE_ITEM.get()),mc.level,mc.player,0);
            require(itemModel.getQuads(null,null,RandomSource.create(0),ModelData.EMPTY,null).size()>=5000,"Inventory item uses placeholder mesh");
            mc.gui.getChat().clearMessages(true); mc.getToasts().clear(); mc.options.hideGui=true;
            screenshot="safe-model-world.png";
            log("SAFE_MODEL_GEOMETRY_PASS: original 5461 triangles, authored PNG, all four orientations, actual item mesh, doubled world size and fitted shape, 27-slot persistence");
        }
        if(ticks==90) { mc.setScreen(new ItemPreview()); }
        if(ticks==105) screenshot="safe-model-item.png";
        if(ticks==120) {
            mc.setScreen(null); mc.options.hideGui=false;
            server(player -> {
                player.teleportTo(player.serverLevel(),.5,first.getY(),3,180,15);
                var hit=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(first),Direction.SOUTH,first,false);
                player.gameMode.useItemOn(player,player.serverLevel(),ItemStack.EMPTY,net.minecraft.world.InteractionHand.MAIN_HAND,hit);
                require(player.serverLevel().getBlockState(first).getValue(SafeBlock.OPEN),"Interaction did not synchronize open state");
                var safe=(SafeBlockEntity)player.serverLevel().getBlockEntity(first);
                require(safe.openingTicksRemaining()>0 && player.containerMenu==player.inventoryMenu,"Loot UI opened before door animation");
            });
        }
        if(ticks==170) {
            require(mc.screen instanceof BagScreen screen && screen.hasLoot(),"Original safe loot interface stopped opening");
            require(openingCaptured && doorFrames.stream().anyMatch(angle->angle>0 && angle<59),"No intermediate opening frames rendered");
            require(mc.level.getBlockEntity(first) instanceof SafeBlockEntity safe
                    && Math.abs(SafeRenderer.animatedAngle(safe,1)-60)<.01,"Client door did not finish open");
            screenshot="safe-model-loot.png";
            log("SAFE_MODEL_LOOT_UI_PASS: real modeled safe opens existing LDLib loot UI over new world model");
        }
        if(ticks==180) {
            mc.player.closeContainer(); mc.setScreen(null);
        }
        if(ticks==190) {
            require(mc.screen==null && mc.level.getBlockState(first).getValue(SafeBlock.OPEN),"Closing loot screen shut the door");
            screenshot="safe-door-open.png";
            server(player -> {
                var safe=(SafeBlockEntity)player.serverLevel().getBlockEntity(first);
                var stateTag=net.minecraft.nbt.NbtUtils.writeBlockState(safe.getBlockState());
                var restoredState=net.minecraft.nbt.NbtUtils.readBlockState(player.registryAccess().lookupOrThrow(Registries.BLOCK),stateTag);
                require(restoredState.getValue(SafeBlock.OPEN),"Open state did not survive block-state save/load");
                var restored=new SafeBlockEntity(first,restoredState);
                require(restored.doorAngle(0)==60,"Loaded open safe replayed opening");
                safe.requestOpen(player);
                require(safe.openingTicksRemaining()==0 && player.containerMenu instanceof BagMenu,"Open safe did not loot immediately");
            });
            log("SAFE_DOOR_ANIMATION_PASS: default closed, actual interaction, Blender curve intermediate frames, delayed loot, stays open, save/reload and subsequent viewer");
        }
        if(ticks==210) {
            mc.player.closeContainer(); mc.setScreen(null);
            server(player -> {
                var drops=player.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(first).inflate(2));
                drops.forEach(net.minecraft.world.entity.Entity::discard);
                player.serverLevel().destroyBlock(first,true,player);
                var after=player.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(first).inflate(2));
                require(after.stream().filter(e->e.getItem().is(Items.DIAMOND)).mapToInt(e->e.getItem().getCount()).sum()==3
                        && after.stream().filter(e->e.getItem().is(Items.BREAD)).mapToInt(e->e.getItem().getCount()).sum()==12,
                        "Modeled safe break lost container contents");
            });
            log("SAFE_MODEL_SMOKE_PASS: original asset rendered in world/item, four facing states, persistence, loot UI, break drops");
            mc.stop();
        }
    }
    private static final class ItemPreview extends Screen {
        ItemPreview() { super(Component.literal("保险箱模型")); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void renderBackground(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {}
        @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
            graphics.fill(0,0,width,height,0xEC15212B);
            graphics.drawCenteredString(font,"保险箱 · Simple Safe",width/2,30,0xFFFFFF);
            graphics.drawCenteredString(font,"avhatar · CC BY 4.0",width/2,height-35,0xB9CDD8);
            graphics.pose().pushPose();
            graphics.pose().translate(width/2f-104,height/2f-104,100);
            graphics.pose().scale(13,13,13);
            graphics.renderItem(new ItemStack(Tactical.SAFE_ITEM.get()),0,0);
            graphics.pose().popPose();
        }
    }
    private static void server(Consumer<ServerPlayer> work) {
        var mc=Minecraft.getInstance(); var id=mc.player.getUUID();
        mc.getSingleplayerServer().submit(()->work.accept(mc.getSingleplayerServer().getPlayerList().getPlayer(id))).join();
    }
    private static void require(boolean okay,String message) { if(!okay) throw new IllegalStateException(message); }
    private static void log(String message) { LoggerFactory.getLogger("SafeModelSmoke").info(message); }
}
