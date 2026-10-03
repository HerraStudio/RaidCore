package dev.tactical;

import com.sgr792.gwo.GwoMod;
import com.sgr792.gwo.content.WeaponContentRegistry;
import com.sgr792.gwo.content.FirearmDefinition;
import com.sgr792.gwo.content.GunDefinition;
import com.sgr792.gwo.content.WeaponDefinition;
import com.sgr792.gwo.item.GunData;
import com.google.gson.JsonParser;
import dev.tactical.client.BagScreen;
import dev.tactical.client.BagLayout;
import dev.tactical.client.LootView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID,value=Dist.CLIENT)
public final class BagSmoke {
    private static boolean started;
    private static boolean originalHideGui;
    private static int ticks;
    private static int renderedBagTooltips;
    private static int styleAuditStage,styleAuditWait;
    private static boolean styleAuditDone;
    private static int previewStage,previewFrame,renderedFrames;
    private static boolean previewDone;
    private record IconSnapshot(float x,float y,float w,float h,int paintW,int paintH) {}
    private record WholePaint(IconSnapshot actual,IconSnapshot expected,boolean rotation) {}
    private record SinglePaint(IconSnapshot actual,float goalX,float goalY,int mouseX,int mouseY) {}
    private static final java.util.List<IconSnapshot> dragFrames=new java.util.ArrayList<>();
    private static final java.util.List<WholePaint> wholeDragFrames=new java.util.ArrayList<>();
    private static final java.util.List<SinglePaint> singleDragFrames=new java.util.ArrayList<>();
    private static int offsetStage,offsetFrame,offsetId=-1,offsetCount,offsetRevision;
    private static int lootOffsetStage,lootOffsetFrame,trackedDrag=-1;
    private static boolean offsetDone,lootOffsetDone;
    private static IconSnapshot offsetSource;
    private static float offsetTargetX,offsetTargetY;
    private static long offsetColor;
    private static String firstDragScreenshot;
    private static boolean offsetPendingMove;
    private static int offsetButton;
    private static int anchorStage,anchorCase,anchorFrame,anchorWait,lootAnchorStage,lootAnchorFrame,lootAnchorWait;
    private static boolean anchorDone,lootAnchorDone;
    private static net.minecraft.nbt.CompoundTag anchorSavedState,anchorRejectState,lootAnchorSavedState;
    private static ItemStack anchorWeapon,lootAnchorWeapon;
    private static ItemStack[] lootAnchorSavedSlots;
    private static int videoStage,videoFrame,videoWait;
    private static boolean videoDone;
    private static boolean isolatedInput;
    private static net.minecraft.nbt.CompoundTag videoSavedState,videoFixtureState;
    private static ItemStack[] videoSavedInventory,videoComponents;
    private static ItemStack videoTool;
    @SubscribeEvent public static void isolateInput(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        var mc=Minecraft.getInstance();
        if(mc.getWindow()==null || mc.getWindow().getWindow()==0) return;
        long window=mc.getWindow().getWindow();
        // This test owns its window. Keep physical desktop clicks/keys from selecting
        // items while scripted Screen/MouseHandler events exercise the real UI path.
        if(!isolatedInput) {
            org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback(window,null);
            org.lwjgl.glfw.GLFW.glfwSetKeyCallback(window,null);
            org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback(window,null);
            org.lwjgl.glfw.GLFW.glfwSetScrollCallback(window,null);
            isolatedInput=true;
        }
        if(org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_VISIBLE)!=0)
            org.lwjgl.glfw.GLFW.glfwHideWindow(window);
    }
    @SubscribeEvent public static void frame(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) {
        renderedFrames++;
        if(trackedDrag!=-1 && Minecraft.getInstance().screen instanceof BagScreen screen) {
            try {
                if((int)screenField(screen,"selected")==trackedDrag) {
                    var floating=screenField(screen,"floatingIcon");
                    require(floating!=null && floatingSource(screen,trackedDrag),"Active drag did not mark its captured source for the gray mask");
                    var actual=iconSnapshot(floating);
                    dragFrames.add(actual);
                    // Capture the mouse and effective direction belonging to this painted
                    // frame before Frame.Post moves the pointer for the next frame.
                    if(screenField(screen,"wholeDrag")!=null)
                        wholeDragFrames.add(new WholePaint(actual,expectedWholeIcon(screen),(boolean)screenField(screen,"rotated")));
                    else {
                        float frameScale=(float)screenField(screen,"scale");
                        float goalX=((float)screenField(screen,"ghostX")-(float)screenField(screen,"offsetX"))/frameScale
                                -(float)screenField(screen,"grabRatioX")*actual.w;
                        float goalY=((float)screenField(screen,"ghostY")-(float)screenField(screen,"offsetY"))/frameScale
                                -(float)screenField(screen,"grabRatioY")*actual.h;
                        singleDragFrames.add(new SinglePaint(actual,goalX,goalY,(int)screenField(screen,"mouseX"),(int)screenField(screen,"mouseY")));
                    }
                    if(dragFrames.size()==1 && firstDragScreenshot!=null)
                        previewScreenshot(Minecraft.getInstance(),firstDragScreenshot);
                    if(dragFrames.size()==1 && offsetPendingMove) {
                        sameIcon(offsetSource,iconSnapshot(floating),"First actual rendered drag frame changed the source geometry");
                        var fit=BagLayout.fit(screen.width,screen.height,screen.hasLoot());
                        movePreviewCursor(Minecraft.getInstance(),offsetTargetX,offsetTargetY);
                        double x=fit.screenX(offsetTargetX),y=fit.screenY(offsetTargetY);
                        screen.mouseDragged(x,y,offsetButton,x-numberField(screen,"pressX"),y-numberField(screen,"pressY"));
                        offsetPendingMove=false;
                    }
                }
            } catch(Exception failure) { throw new IllegalStateException("Drag frame audit failed",failure); }
        }
    }
    @SubscribeEvent public static void tooltip(net.neoforged.neoforge.client.event.RenderTooltipEvent.Pre event) {
        if(Minecraft.getInstance().screen instanceof BagScreen) renderedBagTooltips++;
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        var mc=Minecraft.getInstance();
        if(!started && mc.screen instanceof TitleScreen && mc.getOverlay()==null) {
            started=true; originalHideGui=mc.options.hideGui;
            mc.getWindow().setWindowed(1440,1000); mc.options.guiScale().set(2);
            mc.options.renderDistance().set(2); mc.options.pauseOnLostFocus=false; mc.resizeDisplay();
            var settings=new LevelSettings("Tactical inventory integration",GameType.SURVIVAL,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("bag-smoke-"+System.currentTimeMillis(),settings,new WorldOptions(1,false,false),
                    registry->registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),mc.screen);
        }
        if(!started || mc.player==null) return;
        if(ticks==0 && mc.screen!=null) return;
        ticks++;
        if(ticks==149 || ticks==239 || ticks==254 || ticks==310 || ticks==327 || ticks==479) mc.getToasts().clear();
        if(ticks==20) {
            // Hide only the unrelated HUD in this isolated visual test; BagScreen still renders normally.
            mc.options.hideGui=true;
            installUnprotectedFixtures();
            var uuid=mc.player.getUUID(); mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                VideoBehaviorChecks.verify(player);
                verify(player);
                prepareGlassBackdrop(player);
            }).join();
            mc.player.setYRot(0); mc.player.setXRot(0);
            mc.setScreen(new InventoryScreen(mc.player));
        }
        if(ticks==140 && mc.screen instanceof BagScreen) {
            var layout=BagLayout.fit(mc.screen.width,mc.screen.height);
            mc.screen.mouseScrolled(layout.screenX(535),layout.screenY(250),0,100);
        }
        if(ticks==145) hoverFirstPocket(mc);
        if(ticks==150) {
            require(mc.screen instanceof BagScreen,"Inventory key did not open LDLib bag screen");
            var screen=(BagScreen)mc.screen;
            require(!screen.hasLoot(),"Loot panel visible without container");
            requireCapacity((BagScreen)mc.screen,0,"2/20");
            requireCapacity((BagScreen)mc.screen,-1,"4/5");
            requireCapacity((BagScreen)mc.screen,1,"15/36");
            // Exercise real mouse + packet path: half-stack from pocket 5 to a free rig cell.
            var layout=BagLayout.fit(mc.screen.width,mc.screen.height);
            float scale=layout.scale(),ox=layout.x(),oy=layout.y();
            double pocketY=layout.screenY(BagLayout.pocketY(5)+BagLayout.CELL_SIZE/2.0);
            double firstPocketX=layout.screenX(BagLayout.GRID_X+BagLayout.CELL_SIZE/2.0);
            double lastPocketX=layout.screenX(BagLayout.GRID_X+4*BagLayout.GRID_STEP+BagLayout.CELL_SIZE/2.0);
            var canDrop=java.util.Arrays.stream(BagScreen.class.getDeclaredMethods()).filter(method->method.getName().equals("canDrop")).findFirst().orElseThrow();
            canDrop.setAccessible(true);
            LoggerFactory.getLogger("BagSmoke").info("BAG_SPLIT_BEFORE: frames {} scroll {} selected {} half {} revision {} pocket [{}] target [{}]",
                    renderedFrames,screenField(screen,"scroll"),screenField(screen,"selected"),screenField(screen,"half"),screenField(screen,"revision"),
                    describeHit(screen,BagLayout.GRID_X+BagLayout.CELL_SIZE/2.0,BagLayout.pocketY(5)+BagLayout.CELL_SIZE/2.0),describeHit(screen,441,180));
            try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(Path.of("tactical-inventory-preview.png")); }
            // Reject a rifle dragged into a pocket. The next click must select bread,
            // not keep dragging the rifle after the left button has been released.
            mc.screen.mouseClicked(ox+195*scale,oy+295*scale,0);
            mc.screen.mouseDragged(lastPocketX,pocketY,0,lastPocketX-(ox+195*scale),pocketY-(oy+295*scale));
            LoggerFactory.getLogger("BagSmoke").info("BAG_SPLIT_INVALID_RIFLE: selected {} half {} dragged {} target [{}] canDrop {}",
                    screenField(screen,"selected"),screenField(screen,"half"),screenField(screen,"dragged"),
                    describeHit(screen,BagLayout.GRID_X+4*BagLayout.GRID_STEP+BagLayout.CELL_SIZE/2.0,BagLayout.pocketY(5)+BagLayout.CELL_SIZE/2.0),
                    canDrop.invoke(screen,screenHit(screen,BagLayout.GRID_X+4*BagLayout.GRID_STEP+BagLayout.CELL_SIZE/2.0,BagLayout.pocketY(5)+BagLayout.CELL_SIZE/2.0)));
            mc.screen.mouseReleased(lastPocketX,pocketY,0);
            LoggerFactory.getLogger("BagSmoke").info("BAG_SPLIT_AFTER_INVALID_RELEASE: selected {} half {} notice {}",
                    screenField(screen,"selected"),screenField(screen,"half"),screenField(screen,"notice"));
            mc.screen.mouseClicked(firstPocketX,pocketY,1);
            mc.screen.mouseReleased(firstPocketX,pocketY,1);
            LoggerFactory.getLogger("BagSmoke").info("BAG_SPLIT_PRE_SEND: selected {} half {} dragged {} clientPocket {} revision {} target [{}] canDrop {}",
                    screenField(screen,"selected"),screenField(screen,"half"),screenField(screen,"dragged"),
                    ((ItemStack[])screenField(screen,"inventory"))[4],screenField(screen,"revision"),describeHit(screen,441,180),canDrop.invoke(screen,screenHit(screen,441,180)));
            mc.screen.mouseClicked(ox+441*scale,oy+180*scale,0);
            mc.screen.mouseReleased(ox+441*scale,oy+180*scale,0);
            LoggerFactory.getLogger("BagSmoke").info("BAG_SPLIT_AFTER_SEND: selected {} half {} dragged {} notice {} revision {}",
                    screenField(screen,"selected"),screenField(screen,"half"),screenField(screen,"dragged"),screenField(screen,"notice"),screenField(screen,"revision"));
        }
        if(ticks==175) {
            var screen=(BagScreen)mc.screen;
            LoggerFactory.getLogger("BagSmoke").info("BAG_SPLIT_CLIENT_VERIFY: frames {} scroll {} selected {} half {} revision {} pocket {} notice {} entries {}",
                    renderedFrames,screenField(screen,"scroll"),screenField(screen,"selected"),screenField(screen,"half"),screenField(screen,"revision"),
                    ((ItemStack[])screenField(screen,"inventory"))[4],screenField(screen,"notice"),
                    ((BagState)screenField(screen,"state")).entries.stream().map(entry->entry.id+":"+entry.area+":"+entry.x+","+entry.y+":"+entry.stack).toList());
            var uuid=mc.player.getUUID(); mc.getSingleplayerServer().submit(()-> {
                var p=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                LoggerFactory.getLogger("BagSmoke").info("BAG_SPLIT_SERVER_VERIFY: menu {} revision {} pocket {} entries {}",
                        p.containerMenu.containerId,p.containerMenu instanceof BagMenu menu?menu.revision:-1,p.getInventory().getItem(4),
                        BagState.of(p).entries.stream().map(entry->entry.id+":"+entry.area+":"+entry.x+","+entry.y+":"+entry.stack).toList());
                require(p.getInventory().getItem(4).getCount()==6,"Mouse/network split failed");
                require(BagState.of(p).entries.stream().anyMatch(e->e.area==0 && e.x==3 && e.y==4 && e.stack.is(Items.BREAD) && e.stack.getCount()==6),"Split target missing");
            }).join();
            requireCapacity((BagScreen)mc.screen,0,"3/20");
            requireCapacity((BagScreen)mc.screen,-1,"4/5");
            requireCapacity((BagScreen)mc.screen,1,"15/36");
            var layout=BagLayout.fit(mc.screen.width,mc.screen.height);
            mc.screen.mouseScrolled(layout.screenX(535),mc.screen.height/2,0,-5);
        }
        if(ticks==190) {
            try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(Path.of("tactical-backpack-preview.png")); }
            mc.getWindow().setWindowed(1920,1080); mc.resizeDisplay();
            var layout=BagLayout.fit(mc.screen.width,mc.screen.height);
            mc.screen.mouseScrolled(layout.screenX(535),layout.screenY(250),0,100);
        }
        if(ticks>=194 && ticks<=199) hoverFirstPocket(mc);
        if(ticks==199) mc.getToasts().clear();
        if(ticks==200) {
            require(renderedBagTooltips==0,"Hovering bag items rendered a tooltip");
            var fit=BagLayout.fit(mc.screen.width,mc.screen.height);
            double mouseGuiX=mc.mouseHandler.xpos()*mc.screen.width/mc.getWindow().getScreenWidth();
            double mouseGuiY=mc.mouseHandler.ypos()*mc.screen.height/mc.getWindow().getScreenHeight();
            require(Math.abs(mouseGuiX-fit.screenX(BagLayout.GRID_X+BagLayout.CELL_SIZE/2.0))<2
                    && Math.abs(mouseGuiY-fit.screenY(BagLayout.pocketY(5)+BagLayout.CELL_SIZE/2.0))<2,"Hover capture cursor missed the pocket");
            try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(Path.of("tactical-pocket-military-glass-preview.png")); }
        }
        if(ticks==201 && !styleAuditDone) {
            styleAuditDone=verifyCapacityStyle(mc);
            // Keep the existing screenshot, close, creative and loot stages in their original order.
            if(!styleAuditDone) { ticks--; return; }
        }
        if(ticks==202 && !previewDone) {
            previewDone=verifyDragPreview(mc);
            if(!previewDone) { ticks--; return; }
        }
        if(ticks==203 && !offsetDone) {
            offsetDone=verifyDragOffset(mc);
            if(!offsetDone) { ticks--; return; }
        }
        if(ticks==204 && !anchorDone) {
            anchorDone=verifyWholeItemAnchor(mc);
            if(!anchorDone) { ticks--; return; }
        }
        if(ticks==204 && anchorDone && !videoDone) {
            videoDone=verifyVideoBehaviorUI(mc);
            if(!videoDone) { ticks--; return; }
        }
        if(ticks==205) {
            mc.screen.onClose();
            mc.getWindow().setWindowed(1440,1000); mc.resizeDisplay();
        }
        if(ticks==210) {
            require(mc.screen==null,"Bag failed to close");
            require(mc.player.getInventory().getItem(4).getCount()==6,"Pocket/hotbar desynchronized after closing");
            LoggerFactory.getLogger("BagSmoke").info("TACTICAL_INVENTORY_SMOKE_PASS: separate five-pocket section, hovered pocket without tooltip, filled 1920x1080 military glass preview, slot filters, overflow migration, real GWO component preservation, grid rotation/collision, split, stale packet rejection, persistence roundtrip, death drops, real LDLib screen mouse/network transfer");
            var uuid=mc.player.getUUID();
            mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                for(int slot=0;slot<4;slot++) player.getInventory().setItem(slot,ItemStack.EMPTY);
                for(int slot=36;slot<40;slot++) player.getInventory().setItem(slot,ItemStack.EMPTY);
                player.setGameMode(GameType.CREATIVE);
            }).join();
        }
        if(ticks==215) mc.setScreen(new InventoryScreen(mc.player));
        if(ticks==240) {
            require(mc.screen instanceof BagScreen,"Empty equipment preview did not open");
            try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(Path.of("tactical-equipment-empty-preview.png")); }
            mc.getWindow().setWindowed(1920,1080); mc.resizeDisplay();
        }
        if(ticks==255) {
            try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(Path.of("tactical-equipment-widescreen-preview.png")); }
            mc.screen.onClose();
        }
        if(ticks==275) require(net.neoforged.neoforge.client.ClientCommandHandler.runCommand("creativeinv"),"Creative command not registered");
        if(ticks==285) {
            require(mc.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen,"Creative command failed to open vanilla inventory");
            LoggerFactory.getLogger("BagSmoke").info("CREATIVE_INVENTORY_COMMAND_PASS");
            mc.player.closeContainer();
        }
        if(ticks==295) {
            var uuid=mc.player.getUUID();
            mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                LootChecks.verify(player);
                verify(player);
                LootWorldChecks.verify(player);
                var safe=(dev.tactical.loot.SafeBlockEntity)player.serverLevel().getBlockEntity(LootWorldChecks.safePosition);
                safe.setItem(0,new ItemStack(Items.DIAMOND_SWORD));
                safe.setItem(1,gun("m4"));
                safe.setItem(2,new ItemStack(Items.DIAMOND,8));
                safe.setItem(3,new ItemStack(Items.IRON_CHESTPLATE));
                safe.setItem(4,new ItemStack(Items.GOLDEN_APPLE,3));
                safe.setItem(5,new ItemStack(Items.IRON_PICKAXE));
                safe.setChanged();
                player.getInventory().setItem(3,ItemStack.EMPTY);
                LootWorldChecks.use(player,LootWorldChecks.safePosition);
            }).join();
        }
        if(ticks==311 || ticks==328 || ticks==480) {
            require(mc.screen instanceof BagScreen screen && screen.hasLoot(),"Container loot panel not visible");
            try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(Path.of(ticks==311?"tactical-loot-search.png":ticks==328?"tactical-loot-reveal.png":"tactical-loot-complete.png"));
            }
        }
        if(ticks==481 && !lootOffsetDone) {
            lootOffsetDone=verifyLootDragOffset(mc);
            if(!lootOffsetDone) { ticks--; return; }
        }
        if(ticks==482 && !lootAnchorDone) {
            lootAnchorDone=verifyWholeItemLootAnchor(mc);
            if(!lootAnchorDone) { ticks--; return; }
        }
        if(ticks==336) {
            var fit=BagLayout.fit(mc.screen.width,mc.screen.height,true);
            mc.screen.mouseClicked(fit.screenX(BagLayout.LOOT_X+14),fit.screenY(BagLayout.LOOT_GRID_TOP+14),0);
            mc.screen.mouseReleased(fit.screenX(BagLayout.LOOT_X+14),fit.screenY(BagLayout.LOOT_GRID_TOP+14),0);
            mc.screen.mouseClicked(fit.screenX(224),fit.screenY(229),0);
            mc.screen.mouseReleased(fit.screenX(224),fit.screenY(229),0);
        }
        if(ticks==345) {
            var uuid=mc.player.getUUID();
            mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                require(player.getInventory().getItem(3).is(Items.DIAMOND_SWORD),"Loot mouse/network retrieval failed");
                var safe=(dev.tactical.loot.SafeBlockEntity)player.serverLevel().getBlockEntity(LootWorldChecks.safePosition);
                require(safe.getItem(0).isEmpty(),"Retrieved loot remained in safe");
            }).join();
            var fit=BagLayout.fit(mc.screen.width,mc.screen.height,true);
            mc.screen.mouseClicked(fit.screenX(BagLayout.GRID_X+13),fit.screenY(BagLayout.pocketY(5)+13),1);
            mc.screen.mouseReleased(fit.screenX(BagLayout.GRID_X+13),fit.screenY(BagLayout.pocketY(5)+13),1);
            mc.screen.mouseClicked(fit.screenX(BagLayout.LOOT_X+14),fit.screenY(BagLayout.LOOT_GRID_TOP+14),0);
            mc.screen.mouseReleased(fit.screenX(BagLayout.LOOT_X+14),fit.screenY(BagLayout.LOOT_GRID_TOP+14),0);
        }
        if(ticks==360) {
            var uuid=mc.player.getUUID();
            mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                var safe=(dev.tactical.loot.SafeBlockEntity)player.serverLevel().getBlockEntity(LootWorldChecks.safePosition);
                require(safe.getItem(0).is(Items.BREAD) && safe.getItem(0).getCount()==6 && player.getInventory().getItem(4).getCount()==6,"Loot mouse/network deposit split failed");
                LoggerFactory.getLogger("BagSmoke").info("LOOT_MOUSE_NETWORK_PASS");
            }).join();
        }
        if(ticks==490) mc.screen.onClose();
        if(ticks==510) mc.setScreen(new InventoryScreen(mc.player));
        if(ticks==535) {
            require(mc.screen instanceof BagScreen screen && !screen.hasLoot(),"Loot panel survived reopening backpack alone");
            LoggerFactory.getLogger("BagSmoke").info("LOOT_VISIBILITY_PASS");
            mc.options.hideGui=originalHideGui;
            mc.stop();
        }
    }
    private static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private static Object screenField(BagScreen screen,String name) throws ReflectiveOperationException {
        var field=BagScreen.class.getDeclaredField(name); field.setAccessible(true); return field.get(screen);
    }
    private static String capacityText(BagScreen screen,int area) throws ReflectiveOperationException {
        var method=BagScreen.class.getDeclaredMethod("capacityText",int.class); method.setAccessible(true);
        return ((String)method.invoke(screen,area)).replaceAll("\\s+","");
    }
    private static void requireCapacity(BagScreen screen,int area,String expected) throws ReflectiveOperationException {
        String actual=capacityText(screen,area);
        var inventory=(ItemStack[])screenField(screen,"inventory");
        require(actual.equals(expected),"Capacity header for area "+area+" expected "+expected+" but showed "+actual
                +"; pockets 4..8="+java.util.Arrays.toString(java.util.Arrays.copyOfRange(inventory,4,9)));
    }
    private static Object screenHit(BagScreen screen,double x,double y) throws ReflectiveOperationException {
        var method=BagScreen.class.getDeclaredMethod("hit",double.class,double.class); method.setAccessible(true);
        var fit=BagLayout.fit(screen.width,screen.height,screen.hasLoot());
        return method.invoke(screen,fit.screenX(x),fit.screenY(y));
    }
    private static int hitField(Object hit,String name) throws ReflectiveOperationException {
        require(hit!=null,"Expected a visible item hit");
        var field=hit.getClass().getDeclaredField(name); field.setAccessible(true); return field.getInt(hit);
    }
    private static void drag(BagScreen screen,double fromX,double fromY,double toX,double toY) throws ReflectiveOperationException {
        var fit=BagLayout.fit(screen.width,screen.height);
        double startX=fit.screenX(fromX),startY=fit.screenY(fromY),endX=fit.screenX(toX),endY=fit.screenY(toY);
        screen.mouseClicked(startX,startY,0);
        int sourceId=(int)screenField(screen,"selected");
        screen.mouseDragged(endX,endY,0,endX-startX,endY-startY);
        var target=screenHit(screen,toX,toY);
        var canDrop=java.util.Arrays.stream(BagScreen.class.getDeclaredMethods()).filter(method->method.getName().equals("canDrop")).findFirst().orElseThrow();
        canDrop.setAccessible(true);
        LoggerFactory.getLogger("BagSmoke").info("BAG_STYLE_PRE_RELEASE: selected {} half {} rotated {} dragged {} canDrop {} source [{}] target [{}]",
                screenField(screen,"selected"),screenField(screen,"half"),screenField(screen,"rotated"),screenField(screen,"dragged"),
                canDrop.invoke(screen,target),describeHit(screen,fromX,fromY),describeHit(screen,toX,toY));
        screen.mouseReleased(endX,endY,0);
        require(!floatingSource(screen,sourceId),"Accepted drop retained the source gray mask");
    }
    private static String describeHit(BagScreen screen,double x,double y) throws ReflectiveOperationException {
        var hit=screenHit(screen,x,y);
        return hit==null?"none":"id="+hitField(hit,"id")+", area="+hitField(hit,"area")
                +", cell="+hitField(hit,"cellX")+","+hitField(hit,"cellY");
    }
    private static boolean verifyCapacityStyle(Minecraft mc) throws Exception {
        require(mc.screen instanceof BagScreen,"Capacity style audit lost the inventory screen");
        var screen=(BagScreen)mc.screen;
        var fit=BagLayout.fit(screen.width,screen.height);
        if(styleAuditStage==0) {
            screen.mouseScrolled(fit.screenX(535),fit.screenY(250),0,-100);
            styleAuditStage=1; styleAuditWait=0; return false;
        }
        if(styleAuditStage==1 && ++styleAuditWait<3) return false;
        int scroll=(int)screenField(screen,"scroll");
        int gridY=BagLayout.backpackY(5)+BagLayout.SECTION_HEADER-scroll;
        int pocketY=BagLayout.pocketY(5)-scroll;
        int size=BagLayout.gridCellSize(1),step=BagLayout.GRID_STEP;
        double pocketX=BagLayout.GRID_X+BagLayout.CELL_SIZE/2.0,pocketCenterY=pocketY+BagLayout.CELL_SIZE/2.0;
        double targetX=BagLayout.GRID_X+5*step+size/2.0,targetY=gridY+5*step+size/2.0;
        if(styleAuditStage==1) {
            require(size==step,"Backpack cells did not form a continuous grid");
            require(hitField(screenHit(screen,BagLayout.GRID_X+5*step-0.5,targetY),"cellX")==4
                    && hitField(screenHit(screen,BagLayout.GRID_X+5*step,targetY),"cellX")==5,
                    "Adjacent backpack columns left an unclickable boundary");
            require(hitField(screenHit(screen,targetX,gridY+5*step-0.5),"cellY")==4
                    && hitField(screenHit(screen,targetX,gridY+5*step),"cellY")==5,
                    "Adjacent backpack rows left an unclickable boundary");
            var state=(BagState)screenField(screen,"state");
            var weapon=state.entries.stream().filter(entry->entry.area==1 && Rules.gun(entry.stack)).findFirst().orElseThrow();
            var diamond=state.entries.stream().filter(entry->entry.area==1 && entry.stack.is(Items.DIAMOND)).findFirst().orElseThrow();
            var leather=state.entries.stream().filter(entry->entry.area==1 && entry.stack.is(Items.LEATHER)).findFirst().orElseThrow();
            require(hitField(screenHit(screen,BagLayout.GRID_X+3*step-0.5,gridY+2*step+size/2.0),"id")==diamond.id
                    && hitField(screenHit(screen,BagLayout.GRID_X+3*step,gridY+2*step+size/2.0),"id")==leather.id,
                    "Neighboring one-cell backpack items left an unclickable boundary");
            require(hitField(screenHit(screen,BagLayout.GRID_X+step-0.5,gridY+size/2.0),"id")==weapon.id,
                    "Multi-cell weapon had an unclickable internal column seam");
            require(hitField(screenHit(screen,BagLayout.GRID_X+size/2.0,gridY+step-0.5),"id")==weapon.id,
                    "Multi-cell weapon had an unclickable internal row seam");
            var lastCell=screenHit(screen,BagLayout.GRID_X+BagLayout.gridItemSize(1,5)-0.5,
                    gridY+BagLayout.gridItemSize(1,2)-0.5);
            require(hitField(lastCell,"id")==weapon.id && hitField(lastCell,"cellX")==4 && hitField(lastCell,"cellY")==1,
                    "Merged weapon's last visible cell did not map to its actual grid position");
            var neighbor=screenHit(screen,BagLayout.GRID_X+BagLayout.gridItemSize(1,5),gridY+size/2.0);
            require(hitField(neighbor,"id")==-1 && hitField(neighbor,"cellX")==5 && hitField(neighbor,"cellY")==0,
                    "Merged weapon boundary did not immediately reach the adjacent empty cell");
            // Select through a filled internal seam, then cancel without changing any server item.
            screen.mouseClicked(fit.screenX(BagLayout.GRID_X+step-0.5),fit.screenY(gridY+size/2.0),0);
            require((int)screenField(screen,"selected")==weapon.id,"Mouse could not select the visible multi-cell weapon seam");
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);
            require((int)screenField(screen,"selected")==-1,"Cancelling the item preview left it selected");
            try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(Path.of("tactical-backpack-continuous-grid-preview.png"));
            }
            drag(screen,pocketX,pocketCenterY,targetX,targetY);
            require((int)screenField(screen,"selected")==-1,"Backpack drop retained the drag preview");
            LoggerFactory.getLogger("BagSmoke").info("BAG_STYLE_DRAG: stage 1 scroll {} source [{}] target [{}] client bag {} pockets {} notice {} revision {}",
                    scroll,describeHit(screen,pocketX,pocketCenterY),describeHit(screen,targetX,targetY),
                    capacityText(screen,1),capacityText(screen,-1),screenField(screen,"notice"),screenField(screen,"revision"));
            styleAuditStage=2; styleAuditWait=0; return false;
        }
        if(styleAuditStage==2 || styleAuditStage==4) {
            boolean restoring=styleAuditStage==4;
            boolean clientReady=capacityText(screen,1).equals(restoring?"15/36":"16/36")
                    && capacityText(screen,-1).equals(restoring?"4/5":"3/5");
            var uuid=mc.player.getUUID();
            boolean serverReady=mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                var inventory=BagState.of(player);
                boolean bread=inventory.entries.stream().anyMatch(entry->entry.area==1 && entry.x==5 && entry.y==5
                        && entry.stack.is(Items.BREAD) && entry.stack.getCount()==6);
                return restoring?!bread && player.getInventory().getItem(4).is(Items.BREAD)
                        && player.getInventory().getItem(4).getCount()==6:bread && player.getInventory().getItem(4).isEmpty();
            }).join();
            if(!clientReady || !serverReady) {
                styleAuditWait++;
                if(styleAuditWait%20==0) {
                    String server=mc.getSingleplayerServer().submit(()-> {
                        var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                        return "pocket4="+player.getInventory().getItem(4)+", entries="+BagState.of(player).entries.stream()
                                .map(entry->entry.id+":"+entry.area+":"+entry.x+","+entry.y+":"+entry.stack).toList();
                    }).join();
                    LoggerFactory.getLogger("BagSmoke").info("BAG_STYLE_WAIT: stage {} wait {} client bag {} pockets {} notice {} revision {} serverReady {} server {}",
                            styleAuditStage,styleAuditWait,capacityText(screen,1),capacityText(screen,-1),screenField(screen,"notice"),
                            screenField(screen,"revision"),serverReady,server);
                }
                require(styleAuditWait<80,"Backpack mouse/network capacity update timed out: stage="+styleAuditStage
                        +", bag="+capacityText(screen,1)+", pockets="+capacityText(screen,-1)+", serverReady="+serverReady);
                return false;
            }
            requireCapacity(screen,0,"3/20");
            if(!restoring) { styleAuditStage=3; styleAuditWait=0; return false; }
            verifyDisplayCapacityFixtures(screen);
            screen.mouseScrolled(fit.screenX(535),fit.screenY(250),0,100);
            LoggerFactory.getLogger("BagSmoke").info("BAG_CAPACITY_STYLE_PASS: live occupied-cell headers, continuous backpack grid boundaries, visible merged-item seams, last-cell hits, full-stack drag into backpack and back, rotated item area, custom 4x3 capacity, recovery exclusion, unequipped 0/0");
            return true;
        }
        if(styleAuditStage==3) {
            // Several client ticks may run before a frame. Wait for the actual rendered hit map,
            // rather than assuming that the next tick already drew the synchronized entry.
            var state=(BagState)screenField(screen,"state");
            var bread=state.entries.stream().filter(entry->entry.area==1 && entry.x==5 && entry.y==5
                    && entry.stack.is(Items.BREAD)).findFirst().orElseThrow();
            var source=screenHit(screen,targetX,targetY);
            if(source==null || hitField(source,"id")!=bread.id) {
                require(++styleAuditWait<80,"Rendered backpack hit map did not catch up to the received item snapshot");
                return false;
            }
            drag(screen,targetX,targetY,pocketX,pocketCenterY);
            LoggerFactory.getLogger("BagSmoke").info("BAG_STYLE_DRAG: stage 3 scroll {} source [{}] target [{}] client bag {} pockets {} notice {} revision {}",
                    scroll,describeHit(screen,targetX,targetY),describeHit(screen,pocketX,pocketCenterY),
                    capacityText(screen,1),capacityText(screen,-1),screenField(screen,"notice"),screenField(screen,"revision"));
            styleAuditStage=4; styleAuditWait=0;
        }
        return false;
    }
    private static void verifyDisplayCapacityFixtures(BagScreen screen) throws ReflectiveOperationException {
        var field=BagScreen.class.getDeclaredField("state"); field.setAccessible(true);
        var original=(BagState)field.get(screen);
        try {
            var fixture=new BagState();
            fixture.entries.add(new BagState.Entry(900,2,0,0,false,new ItemStack(Items.DIAMOND,64)));
            field.set(screen,fixture);
            requireCapacity(screen,0,"0/0"); requireCapacity(screen,1,"0/0");
            fixture.gear[2]=new ItemStack(Tactical.BACKPACK.get());
            var data=new net.minecraft.nbt.CompoundTag(); var capacity=new net.minecraft.nbt.CompoundTag();
            capacity.putInt("columns",4); capacity.putInt("rows",3); data.put("tactical_inventory",capacity);
            fixture.gear[2].set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(data));
            fixture.entries.add(new BagState.Entry(901,1,0,0,true,new ItemStack(Items.IRON_PICKAXE)));
            fixture.entries.add(new BagState.Entry(902,1,3,2,false,new ItemStack(Items.DIAMOND,64)));
            requireCapacity(screen,1,"4/12");
            fixture.entries.removeIf(entry->entry.area==1);
            fixture.gear[2].remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            var weapon=new BagState.Entry(903,1,0,0,false,gun("m590")); fixture.entries.add(weapon);
            requireCapacity(screen,1,"10/36"); weapon.rotated=true; requireCapacity(screen,1,"10/36");
        } finally { field.set(screen,original); }
    }
    private static void movePreviewCursor(Minecraft mc,double x,double y) throws ReflectiveOperationException {
        var fit=BagLayout.fit(mc.screen.width,mc.screen.height,mc.screen instanceof BagScreen screen && screen.hasLoot());
        double windowX=fit.screenX(x)*mc.getWindow().getScreenWidth()/mc.screen.width;
        double windowY=fit.screenY(y)*mc.getWindow().getScreenHeight()/mc.screen.height;
        long window=mc.getWindow().getWindow();
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(window,windowX,windowY);
        var onMove=net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove",long.class,double.class,double.class);
        onMove.setAccessible(true); onMove.invoke(mc.mouseHandler,window,windowX,windowY);
    }
    private static Object placement(BagScreen screen) throws ReflectiveOperationException {
        var method=BagScreen.class.getDeclaredMethod("placementPreview"); method.setAccessible(true);
        return method.invoke(screen);
    }
    private static void requirePreview(BagScreen screen,int x,int y,int w,int h,boolean valid) throws ReflectiveOperationException {
        var preview=placement(screen); require(preview!=null,"Grid placement preview missing");
        require(hitField(preview,"x")==x && hitField(preview,"y")==y && hitField(preview,"w")==w && hitField(preview,"h")==h,
                "Placement preview did not match the real target cell and item dimensions");
        var field=preview.getClass().getDeclaredField("valid"); field.setAccessible(true);
        require(field.getBoolean(preview)==valid,"Placement preview color disagreed with real placement validity");
        require((float)screenField(screen,"ghostX")==((Integer)screenField(screen,"mouseX")).floatValue()
                && (float)screenField(screen,"ghostY")==((Integer)screenField(screen,"mouseY")).floatValue(),
                "Free item icon did not follow the actual mouse position");
    }
    private static void previewScreenshot(Minecraft mc,String name) throws java.io.IOException {
        try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(Path.of(name)); }
    }
    private static boolean verifyDragPreview(Minecraft mc) throws Exception {
        require(mc.screen instanceof BagScreen,"Drag preview lost inventory screen");
        var screen=(BagScreen)mc.screen; var fit=BagLayout.fit(screen.width,screen.height);
        if(previewStage==0) {
            screen.mouseScrolled(fit.screenX(535),fit.screenY(250),0,-100);
            previewStage=1; previewFrame=renderedFrames; return false;
        }
        if(renderedFrames<previewFrame+2) return false;
        int scroll=(int)screenField(screen,"scroll");
        int gridX=BagLayout.GRID_X,gridY=BagLayout.backpackY(5)+BagLayout.SECTION_HEADER-scroll;
        int pocketY=BagLayout.pocketY(5)-scroll;
        if(previewStage==1) {
            int rigY=BagLayout.STORAGE_CONTENT_TOP+BagLayout.SECTION_HEADER-scroll;
            require(hitField(screenHit(screen,gridX+2*28-0.5,rigY+3*28+14),"cellX")==1
                    && hitField(screenHit(screen,gridX+2*28,rigY+3*28+14),"cellX")==2,
                    "Rig columns did not share continuous clickable boundaries");
            require(hitField(screenHit(screen,gridX+14,rigY+4*28-0.5),"cellY")==3
                    && hitField(screenHit(screen,gridX+14,rigY+4*28),"cellY")==4,
                    "Rig rows did not share continuous clickable boundaries");
            require(hitField(screenHit(screen,gridX+28-0.5,pocketY+14),"id")==4
                    && hitField(screenHit(screen,gridX+28,pocketY+14),"id")==5,
                    "Pocket row left an unclickable boundary between adjacent hotbar slots");
            screen.mouseClicked(fit.screenX(gridX+14),fit.screenY(gridY+2*28+14),0);
            require((int)screenField(screen,"selected")>=100,"Cannot pick actual backpack tool for preview");
            movePreviewCursor(mc,gridX+4*28+4,gridY+3*28+4);
        } else if(previewStage==2) {
            requirePreview(screen,gridX+4*28,gridY+3*28,28,84,true);
            previewScreenshot(mc,"tactical-drag-tool-green.png");
            movePreviewCursor(mc,gridX+4*28+21,gridY+3*28+20);
        } else if(previewStage==3) {
            // Move within the same cell: the icon moves freely while the snapped region stays fixed.
            requirePreview(screen,gridX+4*28,gridY+3*28,28,84,true);
            movePreviewCursor(mc,gridX+5*28+14,gridY+4*28+14);
        } else if(previewStage==4) {
            requirePreview(screen,gridX+5*28,gridY+4*28,28,84,false);
            previewScreenshot(mc,"tactical-drag-space-red.png");
            movePreviewCursor(mc,gridX+13,pocketY+13);
        } else if(previewStage==5) {
            requirePreview(screen,gridX,pocketY,BagLayout.CELL_SIZE,BagLayout.CELL_SIZE,false);
            previewScreenshot(mc,"tactical-drag-type-red.png");
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);
            screen.mouseClicked(fit.screenX(gridX+14),fit.screenY(gridY+14),0);
            movePreviewCursor(mc,gridX+28+14,gridY+3*28+14);
        } else if(previewStage==6) {
            requirePreview(screen,gridX+28,gridY+3*28,140,56,true);
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_R,0,0);
            // R keeps the normalized grab point of the complete 140x56 item.
            movePreviewCursor(mc,gridX+4*28+14*56f/140f,gridY+14*140f/56f);
        } else if(previewStage==7) {
            requirePreview(screen,gridX+4*28,gridY,56,140,true);
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);
            screen.mouseClicked(fit.screenX(gridX+2*28+14),fit.screenY(gridY+2*28+14),1);
            movePreviewCursor(mc,gridX+4*28+14,gridY+3*28+14);
        } else if(previewStage==8) {
            requirePreview(screen,gridX+4*28,gridY+3*28,28,28,true);
            previewScreenshot(mc,"tactical-drag-stack-green.png");
            movePreviewCursor(mc,25,200);
        } else if(previewStage==9) {
            require(placement(screen)==null,"Placement preview remained outside all target regions");
            require((float)screenField(screen,"ghostX")==((Integer)screenField(screen,"mouseX")).floatValue(),"Icon snapped outside grid");
            previewScreenshot(mc,"tactical-drag-free-icon.png");
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);
            requireCapacity(screen,0,"3/20"); requireCapacity(screen,1,"15/36"); requireCapacity(screen,-1,"4/5");
            screen.mouseScrolled(fit.screenX(535),fit.screenY(250),0,100);
            LoggerFactory.getLogger("BagSmoke").info("BAG_DRAG_PREVIEW_PASS: independent free icon and snapped 1x3/5x2/rotated2x5 region, green valid, red no space/type mismatch, half stack without floating count, outside grid icon, no server mutations");
            return true;
        }
        previewStage++; previewFrame=renderedFrames; return false;
    }
    private static IconSnapshot iconSnapshot(Object icon) throws ReflectiveOperationException {
        require(icon!=null,"Expected an actual painted source icon rectangle");
        var type=icon.getClass();
        var x=type.getDeclaredField("x"); var y=type.getDeclaredField("y");
        var w=type.getDeclaredField("w"); var h=type.getDeclaredField("h");
        var pw=type.getDeclaredField("paintW"); var ph=type.getDeclaredField("paintH");
        for(var field:new java.lang.reflect.Field[]{x,y,w,h,pw,ph}) field.setAccessible(true);
        return new IconSnapshot(x.getFloat(icon),y.getFloat(icon),w.getFloat(icon),h.getFloat(icon),pw.getInt(icon),ph.getInt(icon));
    }
    private static IconSnapshot sourceIcon(BagScreen screen,int id) throws ReflectiveOperationException {
        return iconSnapshot(((Map<?,?>)screenField(screen,"iconBounds")).get(id));
    }
    private static boolean floatingSource(BagScreen screen,int id) throws ReflectiveOperationException {
        var method=BagScreen.class.getDeclaredMethod("isFloatingSource",int.class); method.setAccessible(true);
        return (boolean)method.invoke(screen,id);
    }
    private static ItemStack screenStack(BagScreen screen,int id) throws ReflectiveOperationException {
        var method=BagScreen.class.getDeclaredMethod("stack",int.class); method.setAccessible(true);
        return (ItemStack)method.invoke(screen,id);
    }
    private static void sameIcon(IconSnapshot expected,IconSnapshot actual,String context) {
        require(Math.abs(expected.x-actual.x)<0.002f && Math.abs(expected.y-actual.y)<0.002f
                && Math.abs(expected.w-actual.w)<0.002f && Math.abs(expected.h-actual.h)<0.002f
                && expected.paintW==actual.paintW && expected.paintH==actual.paintH,
                context+": expected "+expected+" but painted "+actual);
    }
    private static long sourceColor(Minecraft mc,BagScreen screen,IconSnapshot source,boolean cyan) throws Exception {
        float scale=(float)screenField(screen,"scale"),ox=(float)screenField(screen,"offsetX"),oy=(float)screenField(screen,"offsetY");
        try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            double sx=image.getWidth()/(double)screen.width,sy=image.getHeight()/(double)screen.height;
            int left=Math.max(0,(int)Math.floor((ox+source.x*scale)*sx));
            int top=Math.max(0,(int)Math.floor((oy+source.y*scale)*sy));
            int right=Math.min(image.getWidth(),(int)Math.ceil((ox+(source.x+source.w)*scale)*sx));
            int bottom=Math.min(image.getHeight(),(int)Math.ceil((oy+(source.y+source.h)*scale)*sy));
            long color=0;
            for(int y=top;y<bottom;y++) for(int x=left;x<right;x++) {
                int rgba=image.getPixelRGBA(x,y),r=rgba&255,g=(rgba>>>8)&255,b=(rgba>>>16)&255;
                if(cyan) {
                    if(g>r*1.1 && b>r*1.1 && g>45) color+=Math.min(g,b)-r;
                } else if(r>g*1.06 && b<g*0.95 && r>40) color+=r-b;
            }
            return color;
        }
    }
    private static void beginOffsetDrag(Minecraft mc,BagScreen screen,int id,float ratioX,float ratioY,int button,
                                        float dx,float dy,String firstScreenshot) throws Exception {
        offsetId=id; offsetSource=sourceIcon(screen,id); offsetCount=screenStack(screen,id).getCount();
        offsetRevision=(int)screenField(screen,"revision");
        float x=offsetSource.x+ratioX*offsetSource.w,y=offsetSource.y+ratioY*offsetSource.h;
        var fit=BagLayout.fit(screen.width,screen.height,screen.hasLoot());
        int previousSelection=(int)screenField(screen,"selected");
        var hitMethod=BagScreen.class.getDeclaredMethod("hit",double.class,double.class); hitMethod.setAccessible(true);
        var actualHit=hitMethod.invoke(screen,fit.screenX(x),fit.screenY(y));
        movePreviewCursor(mc,x,y);
        screen.mouseClicked(fit.screenX(x),fit.screenY(y),button);
        require((int)screenField(screen,"selected")==id,"Offset click did not select the visible source item: phase="+firstScreenshot+", tick="+ticks
                +", offsetStage="+offsetStage+", lootOffsetStage="+lootOffsetStage
                +", source="+id+", previous="+previousSelection+", selected="+screenField(screen,"selected")+", hit="+actualHit
                +", sourceBox="+offsetSource+", point="+x+","+y+", fit="+fit+", actualScale="+screenField(screen,"scale")
                +", offsets="+screenField(screen,"offsetX")+","+screenField(screen,"offsetY")
                +", scroll="+screenField(screen,"scroll")+", lootScroll="+screenField(screen,"lootScroll")+", revision="+screenField(screen,"revision")
                +", gui="+screen.width+"x"+screen.height+", window="+mc.getWindow().getScreenWidth()+"x"+mc.getWindow().getScreenHeight()
                +", screenPoint="+fit.screenX(x)+","+fit.screenY(y)+", expectedStack="+screenStack(screen,id));
        sameIcon(offsetSource,iconSnapshot(screenField(screen,"dragSourceIcon")),"Captured drag origin changed source drawing bounds");
        sameIcon(offsetSource,iconSnapshot(screenField(screen,"floatingIcon")),"Starting drag teleported or resized the item");
        if(screenField(screen,"wholeDrag")!=null) {
            require(Math.abs(numberField(screen,"dragOffsetX")-(x-numberField(screen,"itemAnchorX")))<0.002
                    && Math.abs(numberField(screen,"dragOffsetY")-(y-numberField(screen,"itemAnchorY")))<0.002,
                    "Multi-cell grab offset did not originate at the complete item root");
        } else require(Math.abs((float)screenField(screen,"grabRatioX")-ratioX)<0.002f
                && Math.abs((float)screenField(screen,"grabRatioY")-ratioY)<0.002f,"Click-relative icon anchor was lost");
        require(floatingSource(screen,id),"Picked item did not request a source gray mask");
        trackedDrag=id; dragFrames.clear(); wholeDragFrames.clear(); singleDragFrames.clear(); firstDragScreenshot=firstScreenshot;
        offsetTargetX=x+dx; offsetTargetY=y+dy;
        offsetPendingMove=true; offsetButton=button;
    }
    private static void requireSmoothOffset(BagScreen screen) throws Exception {
        require(dragFrames.size()>=2,"No actual rendered floating icon frames were captured");
        var mask=screenField(screen,"sourceMask");
        require(mask!=null && hitField(mask,"id")==offsetId
                && hitField(mask,"x")<=offsetSource.x && hitField(mask,"y")<=offsetSource.y
                && hitField(mask,"x")+hitField(mask,"w")>=offsetSource.x+offsetSource.w
                && hitField(mask,"y")+hitField(mask,"h")>=offsetSource.y+offsetSource.h,
                "Gray source mask did not cover the source item's full painted rectangle");
        if(screenField(screen,"wholeDrag")!=null) {
            // Complete-item dragging uses the live mouse so its preview and release cannot lag.
            // Historic frames must be compared with their own mouse/direction, since
            // automatic orientation and Frame.Post input may already have changed.
            sameIcon(offsetSource,dragFrames.getFirst(),"First actual multi-cell frame moved before the mouse moved");
            require(wholeDragFrames.size()==dragFrames.size(),"Missing actual-frame whole-item anchor samples");
            for(int i=1;i<wholeDragFrames.size();i++) {
                var painted=wholeDragFrames.get(i);
                sameIcon(painted.expected,painted.actual,"Complete item used a delayed or per-cell floating anchor in frame "+i+", rotation="+painted.rotation);
            }
            sameIcon(expectedWholeIcon(screen),iconSnapshot(screenField(screen,"floatingIcon")),"Latest complete-item paint disagrees with its actual rendered mouse/rotation");
            require(screenStack(screen,offsetId).getCount()==offsetCount && (int)screenField(screen,"revision")==offsetRevision,
                    "Whole-item rendering mutated source item/count or server revision");
            return;
        }
        sameIcon(offsetSource,dragFrames.getFirst(),"First rendered drag frame jumped from the source icon");
        require(singleDragFrames.size()==dragFrames.size(),"Missing actual-frame single-item mouse samples: offsetStage="+offsetStage);
        boolean intermediate=false;
        for(int i=1;i<singleDragFrames.size();i++) {
            var painted=singleDragFrames.get(i); var previous=singleDragFrames.get(i-1).actual; var frame=painted.actual;
            String detail="offsetStage="+offsetStage+", frame="+i+", prev="+previous+", current="+frame
                    +", goal=("+painted.goalX+","+painted.goalY+"), mouse=("+painted.mouseX+","+painted.mouseY+")";
            require(frame.w==offsetSource.w && frame.h==offsetSource.h
                    && frame.paintW==offsetSource.paintW && frame.paintH==offsetSource.paintH,"Following mouse changed the captured icon size: "+detail);
            // GLFW may slightly change the integer GUI pointer after explicit onMove.
            // Compare both endpoints of this step against this frame's own goal.
            double before=Math.hypot(previous.x-painted.goalX,previous.y-painted.goalY);
            double remaining=Math.hypot(frame.x-painted.goalX,frame.y-painted.goalY);
            double step=Math.hypot(frame.x-previous.x,frame.y-previous.y);
            require(remaining<=before+0.006,"Floating icon moved away from its click-relative target: "+detail+", distance="+before+" -> "+remaining);
            require(frame.x>=Math.min(previous.x,painted.goalX)-0.006 && frame.x<=Math.max(previous.x,painted.goalX)+0.006
                    && frame.y>=Math.min(previous.y,painted.goalY)-0.006 && frame.y<=Math.max(previous.y,painted.goalY)+0.006,
                    "Floating icon overshot its current frame's goal: "+detail);
            if(step>0.02 && remaining>0.02 && step<before-0.02) intermediate=true;
        }
        var last=singleDragFrames.getLast(); double initial=Math.hypot(offsetSource.x-last.goalX,offsetSource.y-last.goalY);
        double remaining=Math.hypot(last.actual.x-last.goalX,last.actual.y-last.goalY);
        require(intermediate && remaining<initial-1,"Mouse following jumped immediately or did not progress smoothly: offsetStage="+offsetStage
                +", frames="+singleDragFrames.size()+", source="+offsetSource+", current="+last.actual+", goal=("+last.goalX+","+last.goalY+"), distance="+initial+" -> "+remaining+", intermediate="+intermediate);
        require(screenStack(screen,offsetId).getCount()==offsetCount && (int)screenField(screen,"revision")==offsetRevision,
                "Visual-only drag mutated source item/count or server revision");
    }
    private static void cancelOffsetDrag(BagScreen screen) throws Exception {
        trackedDrag=-1; firstDragScreenshot=null; offsetPendingMove=false;
        screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);
        require(!floatingSource(screen,offsetId),"Cancelling drag left the source gray mask active");
    }
    private static void requireSourceRestored(BagScreen screen) throws Exception {
        require((int)screenField(screen,"selected")==-1 && !floatingSource(screen,offsetId),"Ended drag left a source gray mask");
        sameIcon(offsetSource,sourceIcon(screen,offsetId),"Ended drag changed source geometry");
        require(screenStack(screen,offsetId).getCount()==offsetCount && (int)screenField(screen,"revision")==offsetRevision,
                "Cancelled/rejected drag changed the actual source data");
    }
    private static boolean verifyDragOffset(Minecraft mc) throws Exception {
        require(mc.screen instanceof BagScreen,"Offset audit lost inventory screen");
        var screen=(BagScreen)mc.screen; var fit=BagLayout.fit(screen.width,screen.height);
        if(offsetStage==0) {
            screen.mouseScrolled(fit.screenX(535),fit.screenY(250),0,-100);
            offsetStage=1; offsetFrame=renderedFrames; return false;
        }
        if(renderedFrames<offsetFrame+4) return false;
        var state=(BagState)screenField(screen,"state");
        if(offsetStage==1) {
            var tool=state.entries.stream().filter(entry->entry.area==1 && entry.stack.is(Items.IRON_PICKAXE)).findFirst().orElseThrow();
            var source=sourceIcon(screen,tool.id);
            int scroll=(int)screenField(screen,"scroll");
            require(source.y==BagLayout.backpackY(5)+BagLayout.SECTION_HEADER-scroll+tool.y*28+3,
                    "Scrolled source capture did not include backpack origin and scroll");
            var hit=screenHit(screen,source.x+source.w*0.8,source.y+source.h*0.8);
            require(hitField(hit,"id")==tool.id && hitField(hit,"cellY")>tool.y,"Tool offset audit did not click a non-first source cell");
            offsetColor=sourceColor(mc,screen,source,false); require(offsetColor>100,"Tool fixture icon was not visible before drag");
            beginOffsetDrag(mc,screen,tool.id,0.8f,0.8f,0,90,-45,"tactical-drag-offset-tool-first-frame.png");
        } else if(offsetStage==2) {
            requireSmoothOffset(screen);
            long masked=sourceColor(mc,screen,offsetSource,false);
            require(masked>offsetColor*0.18 && masked<offsetColor*0.75,"Source icon was hidden or not dimmed by a gray mask: "+masked+" / "+offsetColor);
            previewScreenshot(mc,"tactical-drag-source-gray-mask.png"); cancelOffsetDrag(screen);
        } else if(offsetStage==3) {
            requireSourceRestored(screen);
            require(sourceColor(mc,screen,offsetSource,false)>offsetColor*0.85,"Cancelling drag did not restore the source icon color");
            var weapon=state.entries.stream().filter(entry->entry.area==1 && Rules.gun(entry.stack)).findFirst().orElseThrow();
            var source=sourceIcon(screen,weapon.id);
            var hit=screenHit(screen,source.x+source.w*0.85,source.y+source.h*0.8);
            require(hitField(hit,"cellX")>=weapon.x+3 && hitField(hit,"cellY")==weapon.y+1,
                    "Weapon offset audit did not click the lower-right non-first cell");
            // This source-offset audit deliberately stays outside every grid. Automatic
            // orientation is tested separately with real fitting targets below.
            beginOffsetDrag(mc,screen,weapon.id,0.85f,0.8f,0,-200,65,"tactical-drag-offset-weapon-first-frame.png");
        } else if(offsetStage==4) {
            requireSmoothOffset(screen); previewScreenshot(mc,"tactical-drag-offset-weapon.png");
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_R,0,0); movePreviewCursor(mc,offsetTargetX,offsetTargetY);
        } else if(offsetStage==5) {
            var floating=iconSnapshot(screenField(screen,"floatingIcon"));
            require(floating.w==offsetSource.h && floating.h==offsetSource.w
                    && floating.paintW==offsetSource.paintH && floating.paintH==offsetSource.paintW,"R did not swap floating dimensions around the captured anchor");
            requireWholeFloatingAnchor(screen,floating,true);
            previewScreenshot(mc,"tactical-drag-offset-rotated.png"); cancelOffsetDrag(screen);
        } else if(offsetStage==6) {
            requireSourceRestored(screen);
            var source=sourceIcon(screen,0);
            require(source.w==104 && source.h==46,"Equipment weapon fixture did not use its actual 114x56 slot inset");
            beginOffsetDrag(mc,screen,0,0.78f,0.65f,0,85,-75,"tactical-drag-offset-equipment-first-frame.png");
        } else if(offsetStage==7) {
            requireSmoothOffset(screen); previewScreenshot(mc,"tactical-drag-offset-equipment.png");
            int scroll=(int)screenField(screen,"scroll");
            double x=BagLayout.GRID_X+4*28+14,y=BagLayout.pocketY(5)-scroll+14;
            trackedDrag=-1; screen.mouseDragged(fit.screenX(x),fit.screenY(y),0,1,1);
            screen.mouseReleased(fit.screenX(x),fit.screenY(y),0);
            require(!floatingSource(screen,offsetId),"Rejected rifle/pocket release left a source gray mask");
        } else if(offsetStage==8) {
            requireSourceRestored(screen);
            var diamonds=state.entries.stream().filter(entry->entry.area==1 && entry.stack.is(Items.DIAMOND)).findFirst().orElseThrow();
            offsetColor=sourceColor(mc,screen,sourceIcon(screen,diamonds.id),true);
            require(offsetColor>100,"Diamond fixture was not visible before half-stack drag");
            beginOffsetDrag(mc,screen,diamonds.id,0.75f,0.55f,1,90,40,"tactical-drag-offset-half-first-frame.png");
            require((boolean)screenField(screen,"half"),"Right-click half-stack semantics were changed");
        } else if(offsetStage==9) {
            requireSmoothOffset(screen);
            long masked=sourceColor(mc,screen,offsetSource,true);
            require(masked>offsetColor*0.18 && masked<offsetColor*0.75,"Half-stack source was hidden or lacked gray mask: "+masked+" / "+offsetColor);
            previewScreenshot(mc,"tactical-drag-half-source-gray-mask.png"); cancelOffsetDrag(screen);
        } else if(offsetStage==10) {
            requireSourceRestored(screen);
            require(sourceColor(mc,screen,offsetSource,true)>offsetColor*0.85,"Half-stack cancel did not restore source color");
            beginOffsetDrag(mc,screen,4,0.25f,0.7f,0,-90,-35,"tactical-drag-offset-pocket-first-frame.png");
        } else if(offsetStage==11) {
            requireSmoothOffset(screen); trackedDrag=-1;
            movePreviewCursor(mc,25,200);
            screen.mouseDragged(fit.screenX(25),fit.screenY(200),0,1,1);
            screen.mouseReleased(fit.screenX(25),fit.screenY(200),0);
            require(!floatingSource(screen,offsetId),"Outside-slot release left a source gray mask");
        } else if(offsetStage==12) {
            requireSourceRestored(screen);
            requireCapacity(screen,0,"3/20"); requireCapacity(screen,1,"15/36"); requireCapacity(screen,-1,"4/5");
            screen.mouseScrolled(fit.screenX(535),fit.screenY(250),0,100);
            LoggerFactory.getLogger("BagSmoke").info("BAG_DRAG_OFFSET_PASS: source overlap at press, lower-right complete-item root, live multi-cell mouse progression, smooth single-cell progression, equipment source size, R anchor, scrolled source, gray mask retains icon/count, half-stack, cancel/rejected/outside release restore, accepted drop mask clearing, no data/revision changes");
            return true;
        }
        offsetStage++; offsetFrame=renderedFrames; return false;
    }
    private static boolean verifyLootDragOffset(Minecraft mc) throws Exception {
        require(mc.screen instanceof BagScreen screen && screen.hasLoot(),"Loot offset audit lost search panel");
        var screen=(BagScreen)mc.screen;
        if(lootOffsetStage==0) {
            var view=(LootView)screenField(screen,"loot");
            var tool=view.entries.values().stream().filter(entry->entry.revealed() && entry.stack().is(Items.IRON_PICKAXE)).findFirst().orElseThrow();
            var source=sourceIcon(screen,tool.id()); var rect=tool.rectangle();
            int lootScroll=(int)screenField(screen,"lootScroll");
            require(source.x==BagLayout.LOOT_X+rect.x()*BagLayout.LOOT_CELL+3
                    && source.y==BagLayout.LOOT_GRID_TOP-lootScroll+rect.y()*BagLayout.LOOT_CELL+3,
                    "Revealed loot source captured grid origin rather than its actually drawn icon");
            offsetColor=sourceColor(mc,screen,source,false); require(offsetColor>100,"Loot tool icon was not visible before drag");
            beginOffsetDrag(mc,screen,tool.id(),0.75f,0.75f,0,-100,-20,"tactical-drag-offset-loot-first-frame.png");
            lootOffsetStage=1; lootOffsetFrame=renderedFrames; return false;
        }
        if(renderedFrames<lootOffsetFrame+4) return false;
        if(lootOffsetStage==1) {
            requireSmoothOffset(screen);
            long masked=sourceColor(mc,screen,offsetSource,false);
            require(masked>offsetColor*0.18 && masked<offsetColor*0.75,"Loot source was hidden or lacked gray mask: "+masked+" / "+offsetColor);
            previewScreenshot(mc,"tactical-drag-loot-source-gray-mask.png"); cancelOffsetDrag(screen);
            lootOffsetStage=2; lootOffsetFrame=renderedFrames; return false;
        }
        requireSourceRestored(screen);
        require(sourceColor(mc,screen,offsetSource,false)>offsetColor*0.85,"Loot cancellation did not restore source icon color");
        LoggerFactory.getLogger("BagSmoke").info("BAG_LOOT_DRAG_OFFSET_PASS: revealed native loot icon first-frame overlap, click-relative offset, smooth mouse follow, gray source mask retains icon/count, cancel restores unmasked source, no loot mutations");
        return true;
    }
    private static double numberField(BagScreen screen,String name) throws ReflectiveOperationException {
        return ((Number)screenField(screen,name)).doubleValue();
    }
    private static void requireWholeFloatingAnchor(BagScreen screen,IconSnapshot floating,boolean swapped) throws Exception {
        require(screenField(screen,"wholeDrag")!=null,"Multi-cell source was not captured as one complete item");
        double w=numberField(screen,"wholeItemWidth"),h=numberField(screen,"wholeItemHeight");
        double dx=numberField(screen,"dragOffsetX")*(swapped?h/w:1),dy=numberField(screen,"dragOffsetY")*(swapped?w/h:1);
        double px=offsetSource.x-numberField(screen,"itemAnchorX"),py=offsetSource.y-numberField(screen,"itemAnchorY");
        double rootX=(numberField(screen,"dragMouseX")-numberField(screen,"offsetX"))/numberField(screen,"scale")-dx;
        double rootY=(numberField(screen,"dragMouseY")-numberField(screen,"offsetY"))/numberField(screen,"scale")-dy;
        require(Math.abs(floating.x-rootX-(swapped?py:px))<0.003
                && Math.abs(floating.y-rootY-(swapped?px:py))<0.003,
                "Floating whole-item root disagreed with the raw mouse and complete-item grab offset");
    }
    private static IconSnapshot expectedWholeIcon(BagScreen screen) throws ReflectiveOperationException {
        boolean swapped=(boolean)screenField(screen,"rotated")!=(boolean)screenField(screen,"dragInitialRotation");
        double w=numberField(screen,"wholeItemWidth"),h=numberField(screen,"wholeItemHeight");
        double dx=numberField(screen,"dragOffsetX")*(swapped?h/w:1),dy=numberField(screen,"dragOffsetY")*(swapped?w/h:1);
        double padX=offsetSource.x-numberField(screen,"itemAnchorX"),padY=offsetSource.y-numberField(screen,"itemAnchorY");
        double rootX=(numberField(screen,"dragMouseX")-numberField(screen,"offsetX"))/numberField(screen,"scale")-dx;
        double rootY=(numberField(screen,"dragMouseY")-numberField(screen,"offsetY"))/numberField(screen,"scale")-dy;
        return new IconSnapshot((float)(rootX+(swapped?padY:padX)),(float)(rootY+(swapped?padX:padY)),
                swapped?offsetSource.h:offsetSource.w,swapped?offsetSource.w:offsetSource.h,
                swapped?offsetSource.paintH:offsetSource.paintW,swapped?offsetSource.paintW:offsetSource.paintH);
    }
    private static double anchorMouseX,anchorMouseY;
    private static Object dropTarget(BagScreen screen,double guiX,double guiY) throws ReflectiveOperationException {
        var method=BagScreen.class.getDeclaredMethod("dropTarget",double.class,double.class); method.setAccessible(true);
        return method.invoke(screen,guiX,guiY);
    }
    private static boolean canDrop(BagScreen screen,Object target) throws ReflectiveOperationException {
        var method=java.util.Arrays.stream(BagScreen.class.getDeclaredMethods()).filter(m->m.getName().equals("canDrop")).findFirst().orElseThrow();
        method.setAccessible(true); return (boolean)method.invoke(screen,target);
    }
    private static double guiX(BagScreen screen,double layoutX) throws ReflectiveOperationException {
        return numberField(screen,"offsetX")+layoutX*numberField(screen,"scale");
    }
    private static double guiY(BagScreen screen,double layoutY) throws ReflectiveOperationException {
        return numberField(screen,"offsetY")+layoutY*numberField(screen,"scale");
    }
    private static int bagGridY(BagScreen screen) throws ReflectiveOperationException {
        var state=(BagState)screenField(screen,"state");
        return BagLayout.backpackY(state.capacity(0).h())+BagLayout.SECTION_HEADER-(int)screenField(screen,"scroll");
    }
    private static void beginWholeDrag(Minecraft mc,BagScreen screen,int id,int sourceArea,int rootX,int rootY,int grabColumn,int grabRow) throws Exception {
        int originX=sourceArea==dev.tactical.loot.LootSession.AREA?BagLayout.LOOT_X:BagLayout.GRID_X;
        int originY=sourceArea==dev.tactical.loot.LootSession.AREA?BagLayout.LOOT_GRID_TOP-(int)screenField(screen,"lootScroll"):bagGridY(screen);
        double rootLayoutX=originX+rootX*28,rootLayoutY=originY+rootY*28;
        double x=rootLayoutX+grabColumn*28+14,y=rootLayoutY+grabRow*28+14;
        movePreviewCursor(mc,x,y);
        var hit=screenHit(screen,x,y);
        require(hitField(hit,"id")==id && hitField(hit,"cellX")==rootX+grabColumn && hitField(hit,"cellY")==rootY+grabRow,
                "Whole-item test did not press the intended source subcell");
        var source=sourceIcon(screen,id);
        screen.mouseClicked(guiX(screen,x),guiY(screen,y),0);
        require((int)screenField(screen,"selected")==id && screenField(screen,"wholeDrag")!=null,"Whole-item source was not selected");
        require(Math.abs(numberField(screen,"itemAnchorX")-rootLayoutX)<0.002
                && Math.abs(numberField(screen,"itemAnchorY")-rootLayoutY)<0.002,
                "Pressing a non-first subcell changed the complete-item root");
        require(Math.abs(numberField(screen,"dragOffsetX")-(grabColumn*28+14))<0.002
                && Math.abs(numberField(screen,"dragOffsetY")-(grabRow*28+14))<0.002,
                "Press offset was relative to a subcell or padded icon instead of the complete item");
        sameIcon(source,iconSnapshot(screenField(screen,"floatingIcon")),"Pressing a complete item resized or teleported its floating icon");
    }
    private static Object moveWholeDrag(Minecraft mc,BagScreen screen,int targetArea,int rootX,int rootY,boolean valid) throws Exception {
        int originX=targetArea==dev.tactical.loot.LootSession.AREA?BagLayout.LOOT_X:BagLayout.GRID_X;
        int originY=targetArea==dev.tactical.loot.LootSession.AREA?BagLayout.LOOT_GRID_TOP-(int)screenField(screen,"lootScroll"):bagGridY(screen);
        boolean swapped=(boolean)screenField(screen,"rotated")!=(boolean)screenField(screen,"dragInitialRotation");
        double w=numberField(screen,"wholeItemWidth"),h=numberField(screen,"wholeItemHeight");
        double dx=numberField(screen,"dragOffsetX")*(swapped?h/w:1),dy=numberField(screen,"dragOffsetY")*(swapped?w/h:1);
        double x=originX+rootX*28+dx,y=originY+rootY*28+dy;
        movePreviewCursor(mc,x,y);
        anchorMouseX=guiX(screen,x); anchorMouseY=guiY(screen,y);
        screen.mouseDragged(anchorMouseX,anchorMouseY,0,anchorMouseX-numberField(screen,"pressX"),anchorMouseY-numberField(screen,"pressY"));
        var target=dropTarget(screen,anchorMouseX,anchorMouseY);
        require(hitField(target,"area")==targetArea && hitField(target,"cellX")==rootX && hitField(target,"cellY")==rootY,
                "Whole-item release resolver used the mouse subcell rather than the complete-item root");
        require(hitField(target,"x")==originX+rootX*28 && hitField(target,"y")==originY+rootY*28,
                "Whole-item resolved coordinates disagreed with the destination grid origin");
        require(canDrop(screen,target)==valid,"Whole-item destination validity did not cover its entire rectangle");
        return target;
    }
    private static void releaseWholeDrag(BagScreen screen) {
        screen.mouseReleased(anchorMouseX,anchorMouseY,0);
    }
    private static BagState.Entry wholeWeapon(BagScreen screen) throws ReflectiveOperationException {
        return ((BagState)screenField(screen,"state")).entries.stream().filter(entry->entry.area==1 && Rules.gun(entry.stack)).findFirst().orElse(null);
    }
    private static boolean weaponAt(BagState state,ItemStack expected,int x,int y,boolean rotated) {
        var guns=state.entries.stream().filter(entry->entry.area==1 && Rules.gun(entry.stack)).toList();
        return guns.size()==1 && guns.getFirst().x==x && guns.getFirst().y==y && guns.getFirst().rotated==rotated
                && guns.getFirst().stack.getCount()==expected.getCount() && ItemStack.isSameItemSameComponents(guns.getFirst().stack,expected);
    }
    private static boolean anchorReady(Minecraft mc,BagScreen screen,int x,int y,boolean rotated) throws Exception {
        var uuid=mc.player.getUUID();
        boolean server=mc.getSingleplayerServer().submit(()->weaponAt(BagState.of(mc.getSingleplayerServer().getPlayerList().getPlayer(uuid)),anchorWeapon,x,y,rotated)).join();
        boolean client=weaponAt((BagState)screenField(screen,"state"),anchorWeapon,x,y,rotated);
        if(server && client && renderedFrames>=anchorFrame+2) return true;
        require(++anchorWait<120,"Whole-item network synchronization timed out: stage "+anchorStage+", case "+anchorCase+", server "+server+", client "+client);
        return false;
    }
    private static boolean nextAnchorStage(int stage) {
        anchorStage=stage; anchorFrame=renderedFrames; anchorWait=0; return false;
    }
    private static void restoreStorage(ServerPlayer player,net.minecraft.nbt.CompoundTag saved) {
        var state=BagState.of(player); var original=BagState.read(saved,player.registryAccess());
        state.entries.clear(); state.entries.addAll(original.entries);
        System.arraycopy(original.gear,0,state.gear,0,state.gear.length); state.nextId=original.nextId;
        state.save(player); player.containerMenu.broadcastChanges();
    }
    private static boolean verifyWholeItemAnchor(Minecraft mc) throws Exception {
        require(mc.screen instanceof BagScreen,"Whole-item anchor audit lost inventory screen");
        var screen=(BagScreen)mc.screen; var uuid=mc.player.getUUID();
        if(anchorStage==0) {
            mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid); var state=BagState.of(player);
                anchorSavedState=state.write(player.registryAccess()); anchorWeapon=gun("m590");
                state.entries.clear(); state.entries.add(new BagState.Entry(state.nextId++,1,0,0,false,anchorWeapon.copy()));
                state.entries.add(new BagState.Entry(state.nextId++,1,5,3,false,new ItemStack(Items.DIAMOND,8)));
                state.save(player); player.containerMenu.broadcastChanges();
            }).join();
            screen.mouseScrolled(guiX(screen,535),guiY(screen,250),0,-100);
            return nextAnchorStage(1);
        }
        if(anchorStage==1) {
            if(!anchorReady(mc,screen,0,0,false)) return false;
            var gun=wholeWeapon(screen);
            beginWholeDrag(mc,screen,gun.id,1,0,0,anchorCase%5,anchorCase/5);
            moveWholeDrag(mc,screen,1,1,4,true);
            // Intentionally release before another rendered frame. Real packet root must match.
            releaseWholeDrag(screen); require((int)screenField(screen,"selected")==-1,"Fast complete-item release kept the item selected");
            return nextAnchorStage(2);
        }
        if(anchorStage==2) {
            if(!anchorReady(mc,screen,1,4,false)) return false;
            var gun=wholeWeapon(screen);
            beginWholeDrag(mc,screen,gun.id,1,1,4,anchorCase%5,anchorCase/5);
            moveWholeDrag(mc,screen,1,0,0,true); releaseWholeDrag(screen);
            return nextAnchorStage(3);
        }
        if(anchorStage==3) {
            if(!anchorReady(mc,screen,0,0,false)) return false;
            if(++anchorCase<10) return nextAnchorStage(1);
            beginWholeDrag(mc,screen,wholeWeapon(screen).id,1,0,0,4,1);
            moveWholeDrag(mc,screen,1,-4,2,false);
            return nextAnchorStage(4);
        }
        if(anchorStage==4) {
            if(renderedFrames<anchorFrame+2) return false;
            requirePreview(screen,BagLayout.GRID_X-4*28,bagGridY(screen)+2*28,140,56,false);
            previewScreenshot(mc,"tactical-whole-item-negative-root-red.png"); releaseWholeDrag(screen);
            return nextAnchorStage(5);
        }
        if(anchorStage==5) {
            if(!anchorReady(mc,screen,0,0,false)) return false;
            mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid); var state=BagState.of(player);
                var gun=state.entries.stream().filter(entry->Rules.gun(entry.stack)).findFirst().orElseThrow();
                state.entries.clear(); state.entries.add(gun);
                state.entries.add(new BagState.Entry(state.nextId++,1,3,2,false,new ItemStack(Tactical.BACKPACK.get())));
                for(int row=0;row<6;row++) for(int col=0;col<6;col++) {
                    final int x=col,y=row;
                    if(x==1 && y==2 || state.entries.stream().anyMatch(entry->x>=entry.x && y>=entry.y && x<entry.x+entry.rect().w() && y<entry.y+entry.rect().h())) continue;
                    state.entries.add(new BagState.Entry(state.nextId++,1,x,y,false,new ItemStack(Items.DIAMOND)));
                }
                state.save(player); anchorRejectState=state.write(player.registryAccess()); player.containerMenu.broadcastChanges();
            }).join();
            return nextAnchorStage(51);
        }
        if(anchorStage==51) {
            if(!anchorRejectState.equals(((BagState)screenField(screen,"state")).write(mc.player.registryAccess())) || renderedFrames<anchorFrame+2) {
                require(++anchorWait<120,"Unpackable overlap fixture did not synchronize"); return false;
            }
            beginWholeDrag(mc,screen,wholeWeapon(screen).id,1,0,0,4,1);
            var target=moveWholeDrag(mc,screen,1,1,2,false);
            require(hitField(target,"id")==-1,"Resolved root retained the occupied mouse subcell's target ID");
            require(hitField(screenHit(screen,BagLayout.GRID_X+5*28+14,bagGridY(screen)+3*28+14),"id")!=-1,
                    "Overlap fixture did not place an obstruction under the mouse");
            return nextAnchorStage(6);
        }
        if(anchorStage==6) {
            if(renderedFrames<anchorFrame+2) return false;
            requirePreview(screen,BagLayout.GRID_X+28,bagGridY(screen)+2*28,140,56,false);
            previewScreenshot(mc,"tactical-whole-item-overlap-red.png"); releaseWholeDrag(screen);
            return nextAnchorStage(7);
        }
        if(anchorStage==7) {
            if(!anchorReady(mc,screen,0,0,false)) return false;
            mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid); var state=BagState.of(player);
                require(anchorRejectState.equals(state.write(player.registryAccess())),"Rejected overlap mutated the full native layout");
                var gun=state.entries.stream().filter(entry->Rules.gun(entry.stack)).findFirst().orElseThrow();
                state.entries.clear(); state.entries.add(gun);
                state.entries.add(new BagState.Entry(state.nextId++,1,5,3,false,new ItemStack(Items.DIAMOND,8)));
                state.save(player); anchorRejectState=state.write(player.registryAccess()); player.containerMenu.broadcastChanges();
            }).join();
            return nextAnchorStage(71);
        }
        if(anchorStage==71) {
            if(!anchorRejectState.equals(((BagState)screenField(screen,"state")).write(mc.player.registryAccess())) || renderedFrames<anchorFrame+2) {
                require(++anchorWait<120,"Whole-item rotation fixture did not restore"); return false;
            }
            beginWholeDrag(mc,screen,wholeWeapon(screen).id,1,0,0,4,1);
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_R,0,0);
            moveWholeDrag(mc,screen,1,3,0,true); return nextAnchorStage(8);
        }
        if(anchorStage==8) {
            if(renderedFrames<anchorFrame+2) return false;
            requirePreview(screen,BagLayout.GRID_X+3*28,bagGridY(screen),56,140,true);
            previewScreenshot(mc,"tactical-whole-item-rotated-root-green.png"); releaseWholeDrag(screen);
            return nextAnchorStage(9);
        }
        if(anchorStage==9) {
            if(!anchorReady(mc,screen,3,0,true)) return false;
            beginWholeDrag(mc,screen,wholeWeapon(screen).id,1,3,0,1,4);
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_R,0,0);
            moveWholeDrag(mc,screen,1,0,0,true); releaseWholeDrag(screen);
            return nextAnchorStage(10);
        }
        if(anchorStage==10) {
            if(!anchorReady(mc,screen,0,0,false)) return false;
            beginWholeDrag(mc,screen,wholeWeapon(screen).id,1,0,0,4,1);
            // Keep the pre-existing click-to-carry path, and confirm its second click uses root.
            screen.mouseReleased(numberField(screen,"pressX"),numberField(screen,"pressY"),0);
            require((int)screenField(screen,"selected")!=-1 && !(boolean)screenField(screen,"dragged"),"Click-to-carry behavior changed");
            moveWholeDrag(mc,screen,1,1,4,true);
            return nextAnchorStage(13);
        }
        if(anchorStage==13) {
            if(renderedFrames<anchorFrame+2) return false;
            requirePreview(screen,BagLayout.GRID_X+28,bagGridY(screen)+4*28,140,56,true);
            previewScreenshot(mc,"tactical-whole-item-lower-right-root-green.png");
            screen.mouseClicked(anchorMouseX,anchorMouseY,0); screen.mouseReleased(anchorMouseX,anchorMouseY,0);
            return nextAnchorStage(11);
        }
        if(anchorStage==11) {
            if(!anchorReady(mc,screen,1,4,false)) return false;
            mc.getSingleplayerServer().submit(()->restoreStorage(mc.getSingleplayerServer().getPlayerList().getPlayer(uuid),anchorSavedState)).join();
            return nextAnchorStage(12);
        }
        var client=(BagState)screenField(screen,"state");
        if(!anchorSavedState.equals(client.write(mc.player.registryAccess())) || renderedFrames<anchorFrame+2) {
            require(++anchorWait<120,"Whole-item fixture did not restore the original inventory"); return false;
        }
        requireCapacity(screen,0,"3/20"); requireCapacity(screen,1,"15/36"); requireCapacity(screen,-1,"4/5");
        screen.mouseScrolled(guiX(screen,535),guiY(screen,250),0,100);
        LoggerFactory.getLogger("BagSmoke").info("BAG_WHOLE_ITEM_ANCHOR_PASS: all 10 subcells of native 5x2 GWO gun, 20 immediate network moves to identical roots, component/ammo preservation, negative-root and full-rectangle overlap red, root target-ID requery, 2x5 rotation and return, second-click confirmation, fixture restoration");
        return true;
    }
    private static boolean nextLootAnchorStage(int stage) {
        lootAnchorStage=stage; lootAnchorFrame=renderedFrames; lootAnchorWait=0; return false;
    }
    private static boolean nextVideoStage(int stage) {
        videoStage=stage; videoFrame=renderedFrames; videoWait=0; return false;
    }
    private static boolean videoAwait(boolean ready,String context) {
        if(ready && renderedFrames>=videoFrame+2) return true;
        require(++videoWait<240,"Video native UI synchronization timed out: stage "+videoStage+", "+context); return false;
    }
    private static boolean videoToolAt(BagState state,int x,int y,boolean rotated) {
        var entries=state.entries.stream().filter(entry->entry.stack.is(Items.IRON_PICKAXE)).toList();
        return entries.size()==1 && entries.getFirst().x==x && entries.getFirst().y==y && entries.getFirst().rotated==rotated
                && ItemStack.isSameItemSameComponents(entries.getFirst().stack,videoTool) && entries.getFirst().stack.getCount()==1;
    }
    private static boolean videoToolReady(Minecraft mc,BagScreen screen,int x,int y,boolean rotated) throws Exception {
        var uuid=mc.player.getUUID();
        boolean server=mc.getSingleplayerServer().submit(()->videoToolAt(BagState.of(mc.getSingleplayerServer().getPlayerList().getPlayer(uuid)),x,y,rotated)).join();
        return videoAwait(server && videoToolAt((BagState)screenField(screen,"state"),x,y,rotated),"tool "+x+","+y+" rotation="+rotated);
    }
    private static void videoMove(Minecraft mc,BagScreen screen,int rootX,int rootY,boolean effective) throws Exception {
        boolean swapped=effective!=(boolean)screenField(screen,"dragInitialRotation");
        double w=numberField(screen,"wholeItemWidth"),h=numberField(screen,"wholeItemHeight");
        double dx=numberField(screen,"dragOffsetX")*(swapped?h/w:1),dy=numberField(screen,"dragOffsetY")*(swapped?w/h:1);
        double x=BagLayout.GRID_X+rootX*28+dx+1,y=bagGridY(screen)+rootY*28+dy+1;
        movePreviewCursor(mc,x,y); anchorMouseX=guiX(screen,x); anchorMouseY=guiY(screen,y);
        screen.mouseDragged(anchorMouseX,anchorMouseY,0,anchorMouseX-numberField(screen,"pressX"),anchorMouseY-numberField(screen,"pressY"));
        var target=dropTarget(screen,anchorMouseX,anchorMouseY);
        require(hitField(target,"area")==1 && hitField(target,"cellX")==rootX && hitField(target,"cellY")==rootY
                        && (boolean)screenField(screen,"rotated")==effective && canDrop(screen,target),
                "Video automatic orientation root/effective state was inconsistent");
    }
    private static void videoDirection(BagScreen screen,int x,int y,boolean effective,boolean preferred) throws Exception {
        require((boolean)screenField(screen,"preferredRotation")==preferred && (boolean)screenField(screen,"rotated")==effective,
                "Automatic rotation overwrote the manually preferred direction");
        requirePreview(screen,BagLayout.GRID_X+x*28,bagGridY(screen)+y*28,effective?84:28,effective?28:84,true);
        var floating=iconSnapshot(screenField(screen,"floatingIcon"));
        require((effective?floating.w>floating.h:floating.h>floating.w) && (effective?floating.paintW>floating.paintH:floating.paintH>floating.paintW),
                "Actual rendered floating glyph dimensions did not follow its automatic rotation");
    }
    private static boolean videoMultiValid(BagState state) {
        if(state.entries.size()!=4) return false;
        for(var stack:videoComponents) {
            var matches=state.entries.stream().filter(entry->ItemStack.isSameItemSameComponents(entry.stack,stack)).toList();
            if(matches.size()!=1 || matches.getFirst().stack.getCount()!=stack.getCount()) return false;
        }
        var weapon=state.entries.stream().filter(entry->Rules.gun(entry.stack)).findFirst().orElseThrow();
        if(!weapon.rect().equals(new Grid.Rect(1,2,5,2))) return false;
        if(!state.entries.stream().filter(entry->entry.stack.is(Items.IRON_CHESTPLATE)).findFirst().orElseThrow().rotated) return false;
        return state.entries.stream().allMatch(entry->state.fits(entry.area,entry.x,entry.y,entry.rotated,entry.stack,entry.id));
    }
    private static boolean verifyVideoBehaviorUI(Minecraft mc) throws Exception {
        require(mc.screen instanceof BagScreen,"Video native audit lost inventory screen");
        var screen=(BagScreen)mc.screen; var uuid=mc.player.getUUID();
        if(videoStage==0) {
            mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid); var state=BagState.of(player);
                videoSavedState=state.write(player.registryAccess()); videoSavedInventory=new ItemStack[41];
                for(int slot=0;slot<41;slot++) videoSavedInventory[slot]=player.getInventory().getItem(slot).copy();
                videoTool=new ItemStack(Items.IRON_PICKAXE); videoTool.setDamageValue(7);
                state.entries.clear(); state.entries.add(new BagState.Entry(state.nextId++,1,0,0,false,videoTool.copy()));
                state.save(player); player.containerMenu.broadcastChanges();
            }).join();
            screen.mouseScrolled(guiX(screen,535),guiY(screen,250),0,-100); return nextVideoStage(1);
        }
        if(videoStage==1) {
            if(!videoToolReady(mc,screen,0,0,false)) return false;
            var tool=((BagState)screenField(screen,"state")).entries.getFirst();
            previewScreenshot(mc,"tactical-video-auto-source-vertical.png"); beginWholeDrag(mc,screen,tool.id,1,0,0,0,0);
            videoMove(mc,screen,3,5,true); return nextVideoStage(2);
        }
        if(videoStage==2) {
            if(renderedFrames<videoFrame+2) return false;
            videoDirection(screen,3,5,true,false); previewScreenshot(mc,"tactical-video-auto-horizontal.png");
            videoMove(mc,screen,4,0,false); return nextVideoStage(3);
        }
        if(videoStage==3) {
            if(renderedFrames<videoFrame+2) return false;
            videoDirection(screen,4,0,false,false); previewScreenshot(mc,"tactical-video-auto-vertical-restored.png");
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_R,0,0); videoMove(mc,screen,3,0,true); return nextVideoStage(4);
        }
        if(videoStage==4) {
            if(renderedFrames<videoFrame+2) return false;
            videoDirection(screen,3,0,true,true); videoMove(mc,screen,5,0,false); return nextVideoStage(5);
        }
        if(videoStage==5) {
            if(renderedFrames<videoFrame+2) return false;
            videoDirection(screen,5,0,false,true); previewScreenshot(mc,"tactical-video-manual-preference-auto-fallback.png");
            videoMove(mc,screen,3,5,true); releaseWholeDrag(screen); return nextVideoStage(6);
        }
        if(videoStage==6) {
            if(!videoToolReady(mc,screen,3,5,true)) return false;
            require((int)screenField(screen,"selected")==-1,"Automatic rotation release retained a floating source mask");
            var tool=((BagState)screenField(screen,"state")).entries.getFirst(); beginWholeDrag(mc,screen,tool.id,1,3,5,0,0);
            screen.mouseReleased(numberField(screen,"pressX"),numberField(screen,"pressY"),0);
            require((int)screenField(screen,"selected")==tool.id && !(boolean)screenField(screen,"dragged"),"Auto-rotation changed click-to-carry selection");
            videoMove(mc,screen,5,0,false); return nextVideoStage(7);
        }
        if(videoStage==7) {
            if(renderedFrames<videoFrame+2) return false;
            videoDirection(screen,5,0,false,true); previewScreenshot(mc,"tactical-video-click-carry-auto-vertical.png");
            screen.mouseClicked(anchorMouseX,anchorMouseY,0); screen.mouseReleased(anchorMouseX,anchorMouseY,0); return nextVideoStage(8);
        }
        if(videoStage==8) {
            if(!videoToolReady(mc,screen,5,0,false)) return false;
            mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid); var state=BagState.of(player); state.entries.clear();
                videoComponents=new ItemStack[]{gun("ak103"),new ItemStack(Items.IRON_CHESTPLATE),new ItemStack(Items.BREAD,13),new ItemStack(Items.DIAMOND,9)};
                int[][] roots={{0,0},{4,2},{1,2},{2,3}};
                for(int index=0;index<roots.length;index++) state.entries.add(new BagState.Entry(state.nextId++,1,roots[index][0],roots[index][1],false,videoComponents[index].copy()));
                state.save(player); videoFixtureState=state.write(player.registryAccess()); player.containerMenu.broadcastChanges();
            }).join();
            return nextVideoStage(9);
        }
        if(videoStage==9) {
            if(!videoAwait(videoFixtureState.equals(((BagState)screenField(screen,"state")).write(mc.player.registryAccess())),"multi-item fixture")) return false;
            var weapon=wholeWeapon(screen); beginWholeDrag(mc,screen,weapon.id,1,0,0,4,1); moveWholeDrag(mc,screen,1,1,2,true); return nextVideoStage(10);
        }
        if(videoStage==10) {
            if(renderedFrames<videoFrame+2) return false;
            requirePreview(screen,BagLayout.GRID_X+28,bagGridY(screen)+2*28,140,56,true);
            require(floatingSource(screen,(int)screenField(screen,"selected")),"Multi-item replacement lost its gray source mask");
            previewScreenshot(mc,"tactical-video-multiple-replacement-green.png"); releaseWholeDrag(screen); return nextVideoStage(11);
        }
        if(videoStage==11) {
            var server=mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid); return BagState.of(player).write(player.registryAccess());
            }).join();
            if(!videoAwait(videoMultiValid(BagState.read(server,mc.player.registryAccess()))
                    && server.equals(((BagState)screenField(screen,"state")).write(mc.player.registryAccess()))
                    && videoMultiValid((BagState)screenField(screen,"state")),"multi-item atomic commit")) return false;
            require((int)screenField(screen,"selected")==-1,"Multi-item release began an unwanted held-item chain");
            previewScreenshot(mc,"tactical-video-multiple-replacement-complete.png");
            int pocketY=BagLayout.pocketY(5)-(int)screenField(screen,"scroll");
            double sx=BagLayout.GRID_X+14,tx=BagLayout.GRID_X+28+14,ty=pocketY+14;
            movePreviewCursor(mc,sx,ty); screen.mouseClicked(guiX(screen,sx),guiY(screen,ty),0);
            movePreviewCursor(mc,tx,ty); anchorMouseX=guiX(screen,tx); anchorMouseY=guiY(screen,ty);
            screen.mouseDragged(anchorMouseX,anchorMouseY,0,28*numberField(screen,"scale"),0);
            require(canDrop(screen,dropTarget(screen,anchorMouseX,anchorMouseY)),"Different 1x1 pocket stacks could not replace each other"); return nextVideoStage(12);
        }
        if(videoStage==12) {
            if(renderedFrames<videoFrame+2) return false;
            requirePreview(screen,BagLayout.GRID_X+28,BagLayout.pocketY(5)-(int)screenField(screen,"scroll"),28,28,true);
            previewScreenshot(mc,"tactical-video-pocket-swap-green.png"); releaseWholeDrag(screen); return nextVideoStage(13);
        }
        if(videoStage==13) {
            boolean server=mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                return ItemStack.matches(player.getInventory().getItem(4),videoSavedInventory[5]) && ItemStack.matches(player.getInventory().getItem(5),videoSavedInventory[4]);
            }).join();
            var inventory=(ItemStack[])screenField(screen,"inventory");
            if(!videoAwait(server && ItemStack.matches(inventory[4],videoSavedInventory[5]) && ItemStack.matches(inventory[5],videoSavedInventory[4]),"single-cell pocket replacement")) return false;
            mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                for(int slot=0;slot<41;slot++) player.getInventory().setItem(slot,videoSavedInventory[slot].copy()); restoreStorage(player,videoSavedState); player.inventoryMenu.broadcastChanges();
            }).join(); return nextVideoStage(14);
        }
        var inventory=(ItemStack[])screenField(screen,"inventory"); boolean original=videoSavedState.equals(((BagState)screenField(screen,"state")).write(mc.player.registryAccess()));
        for(int slot=0;slot<41;slot++) original&=ItemStack.matches(inventory[slot],videoSavedInventory[slot]);
        if(!videoAwait(original,"complete fixture restore")) return false;
        screen.mouseScrolled(guiX(screen,535),guiY(screen,250),0,100);
        LoggerFactory.getLogger("BagSmoke").info("BAG_VIDEO_UI_PASS: actual LDLib mouse/packet 1x3 auto-to-3x1 and restore with glyph screenshots, explicit R preference retained across automatic fallback, immediate release and second-click confirm agree with preview orientation/root, native 5x2 GWO four-item replacement with armor auto-rotation and count/component preservation, different pocket swap, gray source mask and full fixture restoration");
        return true;
    }
    private static LootView.Entry lootAnchorEntry(BagScreen screen) throws ReflectiveOperationException {
        var view=(LootView)screenField(screen,"loot");
        return view.entries.values().stream().filter(entry->entry.revealed() && Rules.gun(entry.stack())).findFirst().orElse(null);
    }
    private static boolean lootAnchorReady(Minecraft mc,BagScreen screen,boolean inBag,int x,int y,boolean rotated) throws Exception {
        var uuid=mc.player.getUUID();
        boolean server=mc.getSingleplayerServer().submit(()-> {
            var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid); var state=BagState.of(player);
            var safe=(dev.tactical.loot.SafeBlockEntity)player.serverLevel().getBlockEntity(LootWorldChecks.safePosition);
            if(inBag) return weaponAt(state,lootAnchorWeapon,x,y,rotated) && safe.getItem(0).isEmpty();
            return state.entries.stream().noneMatch(entry->Rules.gun(entry.stack)) && safe.getItem(0).getCount()==lootAnchorWeapon.getCount()
                    && ItemStack.isSameItemSameComponents(safe.getItem(0),lootAnchorWeapon);
        }).join();
        var entry=lootAnchorEntry(screen); var state=(BagState)screenField(screen,"state");
        boolean client;
        if(inBag) client=weaponAt(state,lootAnchorWeapon,x,y,rotated) && ((LootView)screenField(screen,"loot")).entries.isEmpty();
        else {
            var size=Rules.size(lootAnchorWeapon);
            client=state.entries.isEmpty() && entry!=null && entry.rectangle().x()==x && entry.rectangle().y()==y
                    && entry.rectangle().w()==(rotated?size.h():size.w()) && entry.rectangle().h()==(rotated?size.w():size.h())
                    && entry.stack().getCount()==lootAnchorWeapon.getCount() && ItemStack.isSameItemSameComponents(entry.stack(),lootAnchorWeapon)
                    && net.minecraft.Util.getMillis()-entry.appeared()>320;
        }
        if(server && client && renderedFrames>=lootAnchorFrame+2) return true;
        require(++lootAnchorWait<240,"Whole-item cross-loot synchronization timed out: stage "+lootAnchorStage+", server "+server+", client "+client);
        return false;
    }
    private static boolean verifyWholeItemLootAnchor(Minecraft mc) throws Exception {
        require(mc.screen instanceof BagScreen screen && screen.hasLoot(),"Whole-item cross-loot audit lost the loot panel");
        var screen=(BagScreen)mc.screen; var uuid=mc.player.getUUID();
        if(lootAnchorStage==0) {
            mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid); var state=BagState.of(player);
                lootAnchorSavedState=state.write(player.registryAccess()); lootAnchorWeapon=gun("ak103");
                state.entries.clear(); state.save(player);
                var safe=(dev.tactical.loot.SafeBlockEntity)player.serverLevel().getBlockEntity(LootWorldChecks.safePosition);
                lootAnchorSavedSlots=new ItemStack[safe.getContainerSize()];
                for(int slot=0;slot<lootAnchorSavedSlots.length;slot++) { lootAnchorSavedSlots[slot]=safe.getItem(slot).copy(); safe.setItem(slot,ItemStack.EMPTY); }
                safe.setItem(0,lootAnchorWeapon.copy()); safe.setChanged(); player.containerMenu.broadcastChanges();
            }).join();
            screen.mouseScrolled(guiX(screen,535),guiY(screen,250),0,-100);
            screen.mouseScrolled(guiX(screen,BagLayout.LOOT_X+14),guiY(screen,100),0,100);
            return nextLootAnchorStage(1);
        }
        if(lootAnchorStage==1) {
            if(!lootAnchorReady(mc,screen,false,0,0,false)) return false;
            var gun=lootAnchorEntry(screen);
            beginWholeDrag(mc,screen,gun.id(),dev.tactical.loot.LootSession.AREA,0,0,4,1);
            moveWholeDrag(mc,screen,1,1,4,true); releaseWholeDrag(screen);
            return nextLootAnchorStage(2);
        }
        if(lootAnchorStage==2) {
            if(!lootAnchorReady(mc,screen,true,1,4,false)) return false;
            var gun=wholeWeapon(screen); beginWholeDrag(mc,screen,gun.id,1,1,4,4,1);
            moveWholeDrag(mc,screen,dev.tactical.loot.LootSession.AREA,1,3,true);
            return nextLootAnchorStage(3);
        }
        if(lootAnchorStage==3) {
            if(renderedFrames<lootAnchorFrame+2) return false;
            requirePreview(screen,BagLayout.LOOT_X+28,BagLayout.LOOT_GRID_TOP+3*28-(int)screenField(screen,"lootScroll"),140,56,true);
            previewScreenshot(mc,"tactical-whole-item-cross-loot-root-green.png"); releaseWholeDrag(screen);
            return nextLootAnchorStage(4);
        }
        if(lootAnchorStage==4) {
            if(!lootAnchorReady(mc,screen,false,1,3,false)) return false;
            var gun=lootAnchorEntry(screen); beginWholeDrag(mc,screen,gun.id(),dev.tactical.loot.LootSession.AREA,1,3,4,1);
            moveWholeDrag(mc,screen,dev.tactical.loot.LootSession.AREA,0,6,true); releaseWholeDrag(screen);
            return nextLootAnchorStage(5);
        }
        if(lootAnchorStage==5) {
            if(!lootAnchorReady(mc,screen,false,0,6,false)) return false;
            var gun=lootAnchorEntry(screen); beginWholeDrag(mc,screen,gun.id(),dev.tactical.loot.LootSession.AREA,0,6,4,1);
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_R,0,0);
            moveWholeDrag(mc,screen,dev.tactical.loot.LootSession.AREA,0,0,true); releaseWholeDrag(screen);
            return nextLootAnchorStage(6);
        }
        if(lootAnchorStage==6) {
            if(!lootAnchorReady(mc,screen,false,0,0,true)) return false;
            mc.getSingleplayerServer().submit(()-> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                restoreStorage(player,lootAnchorSavedState);
                var safe=(dev.tactical.loot.SafeBlockEntity)player.serverLevel().getBlockEntity(LootWorldChecks.safePosition);
                for(int slot=0;slot<lootAnchorSavedSlots.length;slot++) safe.setItem(slot,lootAnchorSavedSlots[slot].copy());
                safe.setChanged(); player.containerMenu.broadcastChanges();
            }).join();
            return nextLootAnchorStage(7);
        }
        if(!lootAnchorSavedState.equals(((BagState)screenField(screen,"state")).write(mc.player.registryAccess())) || renderedFrames<lootAnchorFrame+2) {
            require(++lootAnchorWait<120,"Cross-loot fixture did not restore the original inventory"); return false;
        }
        LoggerFactory.getLogger("BagSmoke").info("BAG_WHOLE_ITEM_LOOT_ANCHOR_PASS: native revealed 5x2 GWO gun grabbed from lower-right subcell, immediate safe-to-backpack transfer, matching 5x2 backpack-to-safe preview/release root, same-safe root relocation, 2x5 rotation, real network/server ammo and component preservation, fixture restoration");
        return true;
    }
    private static void prepareGlassBackdrop(ServerPlayer player) {
        // A small high-contrast fixture in the isolated world makes glass blur and translucency visible.
        // Yaw 0 faces south; loot checks use a separate position only two blocks east of the player.
        var origin=player.blockPosition().offset(0,0,8);
        for(int x=-7;x<=7;x++) for(int y=0;y<=6;y++) {
            var block=(Math.abs(x)==7 || y==0 || y==6)
                    ? net.minecraft.world.level.block.Blocks.STONE_BRICKS
                    : ((x+y)&1)==0 ? net.minecraft.world.level.block.Blocks.LIGHT_GRAY_CONCRETE
                    : net.minecraft.world.level.block.Blocks.GRAY_CONCRETE;
            player.serverLevel().setBlockAndUpdate(origin.offset(x,y,0),block.defaultBlockState());
        }
        player.serverLevel().setDayTime(6000);
        player.setYRot(0); player.setXRot(0);
    }
    private static void installUnprotectedFixtures() {
        // Authored test configurations use GWO's public bootstrap API and no protected pack assets.
        Map<ResourceLocation,WeaponDefinition> definitions=new LinkedHashMap<>(WeaponContentRegistry.weaponDefinitions());
        for(String path:new String[]{"ak103","m4","m590","pistol_test"}) {
            if(definitions.keySet().stream().anyMatch(id->id.getPath().contains(path))) continue;
            var json=JsonParser.parseString("""
                    {"display_name":"Unprotected inventory QA", "weapon_type":"firearm",
                     "magazine_size":30,"damage":8,"range":96,"fire_modes":["semi"],
                     "mechanics":{"rpm":600,"fire_interval_ms":100},
                     "ballistics":{"muzzle_velocity":100,"gravity":0,"life_seconds":2.5},
                     "bullet_tracer":{"enabled":false},"first_person_arms":false}
                    """).getAsJsonObject();
            json.addProperty("display_name","Inventory QA "+path);
            var definition=GunDefinition.fromJson(json);
            require(definition.gltfModel()==null,"Fixture unexpectedly references graphics");
            definitions.put(ResourceLocation.fromNamespaceAndPath("inventory_smoke",path),new FirearmDefinition(definition));
        }
        WeaponContentRegistry.installBootstrapDefinitions(definitions);
        LoggerFactory.getLogger("BagSmoke").info("BAG_UNPROTECTED_FIXTURE_READY: native GWO definitions, no protected graphics");
    }
    private static void hoverFirstPocket(Minecraft mc) throws ReflectiveOperationException {
        var fit=BagLayout.fit(mc.screen.width,mc.screen.height);
        double guiX=fit.screenX(BagLayout.GRID_X+BagLayout.CELL_SIZE/2.0);
        double guiY=fit.screenY(BagLayout.pocketY(5)+BagLayout.CELL_SIZE/2.0);
        double windowX=guiX*mc.getWindow().getScreenWidth()/mc.screen.width;
        double windowY=guiY*mc.getWindow().getScreenHeight()/mc.screen.height;
        long window=mc.getWindow().getWindow();
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(window,windowX,windowY);
        // Unfocused windows may not receive GLFW's cursor callback; dispatch its actual handler too.
        var onMove=net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove",long.class,double.class,double.class);
        onMove.setAccessible(true);
        onMove.invoke(mc.mouseHandler,window,windowX,windowY);
    }
    private static ItemStack gun(String path) {
        var id=WeaponContentRegistry.ids().stream().filter(i->i.getPath().contains(path)).findFirst().orElseThrow();
        var stack=GwoMod.weaponStack(id); GunData.initialize(stack,id,WeaponContentRegistry.get(id)); GunData.setAmmo(stack,7); return stack;
    }
    private static void verify(ServerPlayer p) {
        var state=BagState.of(p); p.getInventory().clearContent(); state.entries.clear();
        for(int i=0;i<3;i++) state.gear[i]=ItemStack.EMPTY;
        Item[] small={Items.BREAD,Items.DIAMOND,Items.IRON_INGOT,Items.GOLD_INGOT,Items.EMERALD};
        for(var item:small) require(p.getInventory().add(new ItemStack(item,64)),"Initial pocket rejected");
        var overflow=new ItemStack(Items.COAL,12);
        p.setGameMode(GameType.CREATIVE);
        require(!p.getInventory().add(overflow) && overflow.getCount()==12,"Creative overflow lost items"); p.setGameMode(GameType.SURVIVAL);
        p.getInventory().setItem(9,overflow); state.normalize(p);
        require(p.getInventory().getItem(9).isEmpty() && state.entries.stream().anyMatch(e->e.area==2 && e.stack.getCount()==12),"Legacy migration lost items");
        require(p.getInventory().add(new ItemStack(Tactical.BACKPACK.get())),"Backpack not equipped");
        require(p.getInventory().add(new ItemStack(Tactical.RIG.get())),"Rig not equipped");
        var custom=new net.minecraft.nbt.CompoundTag(); var capacity=new net.minecraft.nbt.CompoundTag();
        capacity.putInt("columns",4); capacity.putInt("rows",3); custom.put("tactical_inventory",capacity);
        state.gear[2].set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(custom));
        require(state.capacity(1).equals(new Rules.Size(4,3)),"Custom capacity ignored");
        require(!state.fits(1,4,0,false,new ItemStack(Items.DIAMOND),-1),"Custom capacity boundary ignored");
        state.gear[2].remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        var smallBag=new ItemStack(Tactical.BACKPACK.get());
        smallBag.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(custom));
        var spare=new BagState.Entry(state.nextId++,2,0,0,false,smallBag);
        var edge=new BagState.Entry(state.nextId++,1,5,5,false,new ItemStack(Items.DIAMOND));
        state.entries.add(spare); state.entries.add(edge);
        var beforeSwap=state.write(p.registryAccess());
        require(!state.swapGear(p,spare.id,43,true),"Undersized backpack replacement accepted");
        require(beforeSwap.equals(state.write(p.registryAccess())),"Rejected replacement changed inventory");
        edge.x=0; edge.y=0;
        require(state.swapGear(p,spare.id,43,true) && state.capacity(1).equals(new Rules.Size(4,3)),"Compatible capacity replacement rejected");
        require(state.swapGear(p,spare.id,43,true),"Restore large backpack failed");
        state.entries.remove(spare); state.entries.remove(edge);
        var emptyMenu=new BagMenu(98,p.getInventory()); emptyMenu.broadcastChanges();
        var emptyStorage=state.write(p.registryAccess());
        action(emptyMenu,1,43,1,0,0,false,false);
        require(emptyStorage.equals(state.write(p.registryAccess())),"Backpack could be placed inside itself");
        var original=gun("ak103"); require(p.getInventory().add(original.copy()),"GWO weapon insertion failed");
        var pistol=gun("pistol_test");
        require(Rules.pistol(pistol) && Rules.accepts(2,pistol) && !Rules.accepts(0,pistol),"GWO pistol slot classification failed");
        p.getInventory().selected=0;
        p.inventoryMenu.clicked(40,0,net.minecraft.world.inventory.ClickType.SWAP,p);
        require(ItemStack.isSameItemSameComponents(p.getInventory().getItem(0),original),"Number key bypassed weapon filter");
        p.getInventory().offhand.set(0,new ItemStack(Items.APPLE));
        p.connection.handlePlayerAction(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.DOWN,0));
        require(ItemStack.isSameItemSameComponents(p.getInventory().getItem(0),original) && p.getOffhandItem().is(Items.APPLE),"Offhand swap bypassed filter");
        p.getInventory().offhand.set(0,ItemStack.EMPTY);
        require(Rules.accepts(0,original) && !Rules.accepts(2,original) && !Rules.accepts(4,original),"Weapon slot classification failed");
        var menu=new BagMenu(99,p.getInventory()); menu.broadcastChanges();
        action(menu,1,0,1,0,0,false,false);
        var entry=state.entries.stream().filter(e->e.area==1 && Rules.gun(e.stack)).findFirst().orElseThrow();
        require(ItemStack.isSameItemSameComponents(entry.stack,original),"Gun components changed");
        int id=entry.id; int rev=menu.revision;
        var saved=state.write(p.registryAccess());
        action(menu,1,43,-1,40,0,false,false);
        require(saved.equals(state.write(p.registryAccess())),"Removed nonempty backpack");
        action(menu,1,4,1,0,0,false,true);
        require(saved.equals(state.write(p.registryAccess())),"Partial pocket stack displaced an occupied item");
        action(menu,1,id,-1,5,0,false,false);
        require(saved.equals(state.write(p.registryAccess())),"Gun accepted into pocket");
        action(menu,1,id,1,4,0,true,false);
        entry=state.entries.stream().filter(e->e.area==1 && Rules.gun(e.stack)).findFirst().orElseThrow();
        require(entry.rotated && entry.x==4 && entry.rect().h()==5,"Rotation failed");
        var after=state.write(p.registryAccess());
        menu.action(new Packets.Action(99,rev,3,entry.id,0,0,0,false,false));
        require(after.equals(state.write(p.registryAccess())),"Stale packet changed inventory");
        require(after.equals(BagState.read(after,p.registryAccess()).write(p.registryAccess())),"Persistence roundtrip changed contents");
        p.getInventory().dropAll();
        require(state.entries.isEmpty() && ArraysEmpty(state.gear),"Extra inventory did not drop on death path");
        var drops=p.serverLevel().getEntitiesOfClass(ItemEntity.class,p.getBoundingBox().inflate(10));
        require(drops.stream().anyMatch(e->ItemStack.isSameItemSameComponents(e.getItem(),original)),"Dropped gun lost its data");
        drops.forEach(ItemEntity::discard);
        // Final screenshot fixture; all previous checks used a fresh isolated test world.
        p.getInventory().clearContent(); state.entries.clear();
        // Retired headset gear is normalized into storage; keep this four-pocket visual fixture free of legacy gear.
        state.gear[0]=ItemStack.EMPTY; state.gear[1]=new ItemStack(Tactical.RIG.get()); state.gear[2]=new ItemStack(Tactical.BACKPACK.get());
        p.getInventory().setItem(0,gun("ak103")); p.getInventory().setItem(1,gun("m4"));
        p.getInventory().setItem(2,pistol);
        p.getInventory().setItem(3,new ItemStack(Items.IRON_AXE));
        p.getInventory().setItem(39,new ItemStack(Items.NETHERITE_HELMET)); p.getInventory().setItem(38,new ItemStack(Items.NETHERITE_CHESTPLATE));
        p.getInventory().setItem(4,new ItemStack(Items.BREAD,12)); p.getInventory().setItem(5,new ItemStack(Items.GOLDEN_APPLE,2));
        p.getInventory().setItem(6,new ItemStack(Items.TORCH,16)); p.getInventory().setItem(7,new ItemStack(Items.ENDER_PEARL,3));
        state.entries.add(new BagState.Entry(state.nextId++,0,0,0,false,new ItemStack(Items.IRON_INGOT,32)));
        state.entries.add(new BagState.Entry(state.nextId++,0,2,0,false,new ItemStack(Items.POTION)));
        state.entries.add(new BagState.Entry(state.nextId++,1,0,0,false,gun("m590")));
        state.entries.add(new BagState.Entry(state.nextId++,1,0,2,false,new ItemStack(Items.IRON_PICKAXE)));
        state.entries.add(new BagState.Entry(state.nextId++,1,2,2,false,new ItemStack(Items.DIAMOND,8)));
        state.entries.add(new BagState.Entry(state.nextId++,1,3,2,false,new ItemStack(Items.LEATHER,12)));
        state.save(p); p.inventoryMenu.broadcastChanges();
    }
    private static boolean ArraysEmpty(ItemStack[] stacks) { for(var s:stacks) if(!s.isEmpty()) return false; return true; }
    private static void action(BagMenu menu,int op,int source,int area,int x,int y,boolean rot,boolean half) {
        menu.action(new Packets.Action(menu.containerId,menu.revision,op,source,area,x,y,rot,half));
    }
}
