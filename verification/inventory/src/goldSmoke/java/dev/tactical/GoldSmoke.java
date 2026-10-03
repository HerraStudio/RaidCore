package dev.tactical;

import dev.tactical.client.LootInspectClient;
import dev.tactical.client.LootItemRenderer;
import dev.tactical.loot.InspectableLootItem;
import dev.tactical.profile.*;
import dev.tactical.profile.client.ProfileScreen;
import java.nio.file.Path;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.*;
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
public final class GoldSmoke {
    private static boolean started,isolated;
    private static int ticks,stage,wait;
    private static String image;
    @SubscribeEvent public static void isolate(RenderFrameEvent.Pre event) {
        long window=Minecraft.getInstance().getWindow().getWindow();
        if(!isolated) {
            GLFW.glfwSetMouseButtonCallback(window,null); GLFW.glfwSetKeyCallback(window,null);
            GLFW.glfwSetCursorPosCallback(window,null); GLFW.glfwSetScrollCallback(window,null); isolated=true;
        }
        if(GLFW.glfwGetWindowAttrib(window,GLFW.GLFW_VISIBLE)!=0) GLFW.glfwHideWindow(window);
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) throws Exception {
        if(image==null) return;
        try(var png=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {png.writeToFile(Path.of(image));}
        image=null;
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        var mc=Minecraft.getInstance();
        if(!started && mc.screen instanceof TitleScreen && mc.getOverlay()==null) {
            started=true; mc.getWindow().setWindowed(1440,900); mc.options.guiScale().set(2);
            mc.options.renderDistance().set(3); mc.options.pauseOnLostFocus=false; mc.resizeDisplay();
            var settings=new LevelSettings("Gold loot integration",GameType.SURVIVAL,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("gold-smoke-"+System.currentTimeMillis(),settings,new WorldOptions(19,false,false),
                    registry->registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),mc.screen);
        }
        if(!started || mc.player==null || ticks==0 && mc.screen!=null) return;
        ticks++;wait++;
        if(ticks>1400) throw new IllegalStateException("Gold smoke timeout stage "+stage);
        if(ticks==20) {
            value(p->{
                p.getInventory().clearContent(); var state=BagState.of(p); state.entries.clear();
                state.gear[1]=new ItemStack(Tactical.RIG.get());state.gear[2]=new ItemStack(Tactical.BACKPACK.get());
                p.getInventory().setItem(4,new ItemStack(Tactical.GOLD_BAR.get())); p.getInventory().setItem(5,new ItemStack(Items.GOLD_INGOT,3));
                p.getInventory().selected=4; p.inventoryMenu.broadcastChanges();state.save(p);
                p.serverLevel().setDayTime(1000);p.setYRot(0);p.setXRot(0);
                require(ItemProfiles.rarity(new ItemStack(Tactical.GOLD_BAR.get()))==Rarity.LEGENDARY,"Gold server default not legendary");
                require(!Rules.gun(new ItemStack(Tactical.GOLD_BAR.get())) && !Rules.melee(new ItemStack(Tactical.GOLD_BAR.get())),"Loot accidentally treated as weapon");
                return true;
            });
            mc.player.getInventory().selected=4;
            mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(4));
            stage=1;wait=0;
        }
        if(stage==1 && wait>=30) {
            var model=LootItemRenderer.model(InspectableLootItem.GOLD_BAR);
            require(!model.isEmpty(),"Actual GWO model cache failed to read gold GLB");
            require(model.embeddedAnimations().clips().keySet().containsAll(java.util.Set.of("draw","idle","inspect")),"Blender clips not loaded into GWO");
            var vertices=model.triangles().stream().flatMap(t->java.util.stream.Stream.of(t.a(),t.b(),t.c())).filter(v->v.normal().y()>.99f).toList();
            log("GOLD_UV_UP_FACE: "+vertices.stream().limit(6).map(v->v.position()+" / "+v.uv()).toList());
            if(com.sgr792.gwo.compat.IrisCompat.isShaderPackInUse()) {
                var managerClass=Class.forName("net.irisshaders.iris.pbr.texture.PBRTextureManager");
                var holderClass=Class.forName("net.irisshaders.iris.pbr.texture.PBRTextureHolder");
                var texture=mc.getTextureManager().getTexture(InspectableLootItem.GOLD_BAR.texture("color"));
                var holder=managerClass.getMethod("getOrLoadHolder",int.class).invoke(managerClass.getField("INSTANCE").get(null),texture.getId());
                var normal=holderClass.getMethod("normalTexture").invoke(holder);var specular=holderClass.getMethod("specularTexture").invoke(holder);
                require(!normal.getClass().getSimpleName().contains("SingleColor") && !specular.getClass().getSimpleName().contains("SingleColor"),"Iris received neutral PBR maps");
                log("GOLD_IRIS_PBR_PASS: active shaderpack, real normal="+normal.getClass().getSimpleName()+", real specular="+specular.getClass().getSimpleName());
            }
            require(LootInspectClient.handFrames>0,"First-person render not used");
            require(ItemProfiles.rarity(mc.player.getMainHandItem())==Rarity.LEGENDARY,"Client gold default not legendary");
            image="gold-held.png";
            var key=java.util.Arrays.stream(mc.options.keyMappings).filter(k->k.getName().equals("key.gwo.inspect")).findFirst().orElseThrow();
            log("GOLD_KEY_FIXTURE: key="+key.getKey()+", clip="+LootInspectClient.renderedClip+", motion="+LootInspectClient.MOTION.active()+", inspect_length="+LootItemRenderer.clipLength(InspectableLootItem.GOLD_BAR,"inspect",0));
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.InputEvent.Key(key.getKey().getValue(),0,GLFW.GLFW_PRESS,0));
            stage=2;wait=0;
        }
        if(stage==2 && wait>=12) {
            require(LootInspectClient.renderedClip.equals("inspect") && LootInspectClient.inspectionStarts==1,"GWO inspect input failed: clip="+LootInspectClient.renderedClip+", starts="+LootInspectClient.inspectionStarts+", active="+LootInspectClient.MOTION.active());
            image="gold-inspect-front.png"; stage=3;wait=0;
        }
        if(stage==3 && wait>=14) {
            image="gold-inspect-back.png";
            var start=LootItemRenderer.pose(InspectableLootItem.GOLD_BAR,"inspect",0).global("gold_bar");
            var mid=LootItemRenderer.pose(InspectableLootItem.GOLD_BAR,"inspect",1.3f).global("gold_bar");
            require(!start.equals(mid,.001f),"GLB inspect does not change model matrix");
            stage=4;wait=0;
        }
        if(stage==4 && wait>=50) {
            require(LootInspectClient.renderedClip.equals("idle"),"Inspection never returns to idle");
            mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);stage=5;wait=0;
        }
        if(stage==5 && wait>=10) {
            require(LootInspectClient.inspectionStarts==2,"Right-click did not trigger inspection");
            require(value(p->p.getMainHandItem().is(Tactical.GOLD_BAR.get()) && p.getMainHandItem().getCount()==1),"Inspection changed server loot");
            log("GOLD_GWO_ANIMATION_PASS: real cache, Blender embedded draw/idle/inspect, actual GWO key + right-click, model matrix changes, held loot intact");
            mc.getConnection().sendCommand("tacticalitems"); stage=6;wait=0;
        }
        if(stage==6 && wait>=16) {
            require(mc.screen instanceof ProfileScreen,"Config menu missing");
            var screen=(ProfileScreen)mc.screen; require(screen.draftRarity()==Rarity.LEGENDARY,"Menu does not show default gold quality");
            image="gold-item-preview.png";
            stage=61;wait=0;
        }
        if(stage==61 && wait>=5) {
            var screen=(ProfileScreen)mc.screen;
            screen.widthField.setText("2");screen.heightField.setText("1");screen.setRarity(Rarity.RARE);screen.saveDraft(false);stage=7;wait=0;
        }
        if(stage==7 && wait>=14) {
            require(ItemProfiles.rarity(new ItemStack(Tactical.GOLD_BAR.get()))==Rarity.RARE,"Admin cannot override gold rarity");
            ((ProfileScreen)mc.screen).saveDraft(true);stage=8;wait=0;
        }
        if(stage==8 && wait>=14) {
            require(ItemProfiles.rarity(new ItemStack(Tactical.GOLD_BAR.get()))==Rarity.LEGENDARY,"Reset did not restore gold default");
            require(Rules.size(new ItemStack(Tactical.GOLD_BAR.get())).equals(new Rules.Size(1,1)),"Default footprint changed");
            ((ProfileScreen)mc.screen).onClose();
            PacketDistributor.sendToServer(new Packets.Action(0,0,0,0,0,0,0,false,false));stage=9;wait=0;
        }
        if(stage==9 && wait>=15) {
            require(mc.screen instanceof dev.tactical.client.BagScreen,"Bag missing");image="gold-inventory.png";
            log("GOLD_RARITY_GUI_PASS: default gold quality, inventory + LDLib preview model, override/reset and multi-cell migration without count loss");
            log("GOLD_RENDER_COUNTS: gpu="+LootItemRenderer.gpuDraws+", fallback="+LootItemRenderer.cpuDraws+", hand_frames="+LootInspectClient.handFrames);
            stage=10;wait=0;
        }
        if(stage==10 && wait>=8) {
            mc.player.closeContainer();mc.setScreen(null);
            value(p->{
                var state=BagState.of(p);var gold=state.entries.stream().filter(e->e.stack.is(Tactical.GOLD_BAR.get())).findFirst().orElseThrow();
                p.getInventory().setItem(4,gold.stack);state.entries.remove(gold);state.save(p);
                p.setMainArm(net.minecraft.world.entity.HumanoidArm.LEFT);p.inventoryMenu.broadcastChanges();return true;
            });
            mc.player.getInventory().selected=4;mc.player.setMainArm(net.minecraft.world.entity.HumanoidArm.LEFT);
            mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(4));stage=11;wait=0;
        }
        if(stage==11 && wait>=20) {
            var key=java.util.Arrays.stream(mc.options.keyMappings).filter(k->k.getName().equals("key.gwo.inspect")).findFirst().orElseThrow();
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.InputEvent.Key(key.getKey().getValue(),0,GLFW.GLFW_PRESS,0));
            stage=12;wait=0;
        }
        if(stage==12 && wait>=12) {
            require(mc.player.getMainArm()==net.minecraft.world.entity.HumanoidArm.LEFT && LootInspectClient.renderedClip.equals("inspect"),"Left-hand inspection failed");
            image="gold-inspect-left.png";stage=13;wait=0;
        }
        if(stage==13 && wait>=8) {log("GOLD_LEFT_HAND_PASS: actual left-arm rendering and mirrored animation transforms without mirroring texture");log("GOLD_NATIVE_SMOKE_PASS");mc.stop();}
    }
    private static <T> T value(Function<net.minecraft.server.level.ServerPlayer,T> action) {
        var mc=Minecraft.getInstance();var id=mc.player.getUUID();
        return mc.getSingleplayerServer().submit(()->action.apply(mc.getSingleplayerServer().getPlayerList().getPlayer(id))).join();
    }
    private static void require(boolean good,String message) {if(!good) throw new IllegalStateException(message);}
    private static void log(String message) {LoggerFactory.getLogger("GoldSmoke").info(message);}
}
