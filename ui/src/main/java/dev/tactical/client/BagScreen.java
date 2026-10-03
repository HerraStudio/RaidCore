package dev.tactical.client;

import dev.tactical.*;
import dev.tactical.loot.LootSession;
import dev.tactical.profile.ItemProfiles;
import dev.tactical.profile.client.ItemVisuals;
import dev.tactical.profile.client.ProfileClient;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import java.util.*;

public final class BagScreen extends AbstractContainerScreen<BagMenu> {
    private static ResourceLocation texture(String name) { return ResourceLocation.fromNamespaceAndPath("tactical_inventory","equipment/"+name); }
    private static final ResourceLocation EMPTY_SLOT=texture("slot");
    private static final int PANEL=0xBF151F29, SLOT_BACKGROUND=0xA0263743, SLOT_BORDER=0x946B818D;
    private static final int BACKPACK_BACKGROUND=0xA0223A54;
    private static final int TEXT=0xFFFFFFFF, TEXT_SHADOW=0x80000000, EMPHASIS=0xCC3C5160, ACTIVE_BORDER=0xE69AAEB7;
    private static final int PLACE_ALLOWED=0x6639D67D, PLACE_BLOCKED=0x66EE5252;
    private static final int SOURCE_MASK=0x99585858;
    private static final List<Integer> FIXED_SLOTS=List.of(0,1,2,3,4,5,6,7,8,36,37,38,39,40,42,43);
    private static final Map<Integer,ResourceLocation> EMPTY_ICONS=Map.of(
            39,texture("helmet"),38,texture("vest"),2,texture("pistol"),3,texture("knife"),
            0,texture("rifle"),1,texture("rifle"),42,texture("rig"),43,texture("backpack"));
    private record Hit(int id,int area,int cellX,int cellY,int x,int y,int w,int h) {
        boolean contains(double px,double py) { return px>=x && py>=y && px<x+w && py<y+h; }
    }
    private record PlacementPreview(int area,int x,int y,int w,int h,boolean valid) {}
    private record IconBox(float x,float y,float w,float h,int paintW,int paintH) {}
    private final List<Hit> hits=new ArrayList<>();
    private final Map<Integer,IconBox> iconBounds=new HashMap<>();
    private final Map<Integer,Hit> itemBounds=new HashMap<>();
    private IconBox dragSourceIcon,floatingIcon;
    private WholeItemDrag wholeDrag;
    private double itemAnchorX,itemAnchorY,dragOffsetX,dragOffsetY,dragMouseX,dragMouseY;
    private int wholeItemWidth,wholeItemHeight;
    private Hit sourceMask;
    private int floatingSelection=-1;
    private boolean dragInitialRotation;
    private boolean preferredRotation;
    private record PlanKey(int revision,int source,int area,int slot,int x,int y,boolean rotated,boolean half) {}
    private final Map<PlanKey,Boolean> placementValidity=new HashMap<>();
    private float grabRatioX,grabRatioY,followX,followY;
    private long lastDragFrame;
    private final ItemStack[] inventory=new ItemStack[41];
    private BagState state=new BagState();
    private LootView loot;
    private int lootScroll;
    private int revision=-1,selected=-1,scroll;
    private boolean rotated,half,dragged;
    private double pressX,pressY;
    private float scale,offsetX,offsetY;
    private ModularUI ui;
    private BagPlayerPreview previewPlayer;
    private int mouseX,mouseY;
    private String notice="";
    private final long openedAt=net.minecraft.Util.getMillis();
    private long closingAt;
    private float motion=1,ghostX,ghostY;

    public BagScreen(BagMenu menu,Inventory inv,Component title) {
        super(menu,inv,title); Arrays.fill(inventory,ItemStack.EMPTY);
    }
    public void accept(Packets.State packet) {
        if(packet.menu()!=menu.containerId) return;
        placementValidity.clear();
        revision=packet.revision(); state=BagState.read(packet.data(),minecraft.player.registryAccess());
        if(packet.data().contains("loot",Tag.TAG_COMPOUND)) {
            if(loot==null) loot=new LootView();
            int previousActive=loot.active;
            loot.accept(packet.data().getCompound("loot"),minecraft.player.registryAccess());
            var searching=loot.entries.get(loot.active);
            if(previousActive!=loot.active && searching!=null && selected==-1) {
                int top=searching.rectangle().y()*BagLayout.LOOT_CELL;
                int bottom=top+searching.rectangle().h()*BagLayout.LOOT_CELL;
                int visible=BagLayout.LOOT_BOTTOM-BagLayout.LOOT_GRID_TOP;
                if(top<lootScroll || bottom>lootScroll+visible) lootScroll=Math.max(0,Math.min(top,bottom-visible));
            }
        } else { loot=null; lootScroll=0; }
        var list=packet.data().getList("inventory",Tag.TAG_COMPOUND);
        for(int i=0;i<41;i++) inventory[i]=ItemStack.parseOptional(minecraft.player.registryAccess(),list.getCompound(i));
        // The vanilla player preview must reflect armor changed in this menu immediately.
        for(int i=36;i<=39;i++) minecraft.player.getInventory().setItem(i,inventory[i].copy());
        if(selected!=-1 && stack(selected).isEmpty()) selected=-1;
    }
    public void profilesChanged() {
        selected=-1; dragged=false; wholeDrag=null; floatingSelection=-1; placementValidity.clear();
    }
    @Override protected void init() {
        super.init();
        if(ui!=null) ui.onRemoved();
        var layout=BagLayout.fit(width,height);
        scale=layout.scale(); offsetX=layout.x(); offsetY=layout.y();
        previewPlayer=new BagPlayerPreview(minecraft.player);
        var root=new UIElement() {
            @Override public void drawBackgroundAdditional(GUIContext context) { draw(context.graphics); }
        }.layout(l->l.widthPercent(100).heightPercent(100));
        ui=ModularUI.of(UI.of(root)); ui.setScreenAndInit(this); addRenderableWidget(ui.getWidget());
    }
    private ItemStack stack(int id) {
        if(LootSession.isLootId(id)) return loot==null?ItemStack.EMPTY:loot.stack(id);
        if(id>=0 && id<=40) return inventory[id];
        if(id>=41 && id<=43) return state.gear[id-41];
        var e=state.entry(id); return e==null?ItemStack.EMPTY:e.stack;
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        long now=net.minecraft.Util.getMillis();
        float progress=Math.min(1,(now-(closingAt==0?openedAt:closingAt))/180f);
        motion=closingAt==0?1-(float)Math.pow(1-progress,3):1-progress*progress;
        if(closingAt!=0 && progress>=1) { super.onClose(); return; }
        var layout=BagLayout.fit(width,height,loot!=null);
        scale=layout.scale(); offsetX=layout.x(); offsetY=layout.y()+(1-motion)*14*scale;
        renderBlurredBackground(partial);
        ghostX=mx; ghostY=my;
        dragMouseX=wholeDrag==null?mx:minecraft.mouseHandler.xpos()*width/minecraft.getWindow().getScreenWidth();
        dragMouseY=wholeDrag==null?my:minecraft.mouseHandler.ypos()*height/minecraft.getWindow().getScreenHeight();
        mouseX=mx; mouseY=my; ui.getWidget().render(g,mx,my,partial);
    }
    @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my) {}
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float partial) {}
    @Override protected void renderLabels(GuiGraphics g,int mx,int my) {}
    private void draw(GuiGraphics g) {
        hits.clear();
        iconBounds.clear();
        itemBounds.clear();
        sourceMask=null;
        g.fill(0,0,width,height,((int)((PANEL>>>24)*motion)<<24)|(PANEL&0xFFFFFF));
        border(g,0,0,width,height,((int)((SLOT_BORDER>>>24)*motion)<<24)|(SLOT_BORDER&0xFFFFFF));
        renderPlayer(g);
        g.pose().pushPose(); g.pose().translate(offsetX,offsetY,0); g.pose().scale(scale,scale,1);
        g.pose().pushPose(); g.pose().translate(0,0,200);
        for(var box:BagLayout.EQUIPMENT) equipmentSlot(g,box);
        g.pose().popPose();
        text(g,"储物空间",BagLayout.STORAGE_X,22,TEXT);
        // GuiGraphics scissor takes screen coordinates, independent of the pose matrix.
        g.enableScissor((int)(offsetX+BagLayout.STORAGE_LEFT*scale),(int)(offsetY+BagLayout.STORAGE_TOP*scale),
                (int)(offsetX+BagLayout.STORAGE_RIGHT*scale),(int)(offsetY+BagLayout.STORAGE_BOTTOM*scale));
        scroll=Math.min(scroll,maxScroll());
        int top=BagLayout.STORAGE_CONTENT_TOP-scroll;
        var rig=state.capacity(0); var bag=state.capacity(1);
        slot(g,42,"胸挂",BagLayout.STORAGE_X,top,60,78); grid(g,0,BagLayout.GRID_X,top+16,rig.w(),rig.h());
        int pocketSection=BagLayout.pocketSectionY(rig.h())-scroll;
        pocketLabel(g,BagLayout.STORAGE_X,pocketSection);
        int pockets=BagLayout.pocketY(rig.h())-scroll;
        border(g,BagLayout.GRID_X,pockets,5*BagLayout.GRID_STEP,BagLayout.CELL_SIZE,SLOT_BORDER);
        for(int pocket=0;pocket<5;pocket++) {
            int pocketX=BagLayout.GRID_X+pocket*BagLayout.GRID_STEP;
            gridItemBox(g,pocket+4,pocketX,pockets,BagLayout.CELL_SIZE,BagLayout.CELL_SIZE,SLOT_BACKGROUND);
            hits.add(new Hit(pocket+4,-1,0,0,pocketX,pockets,BagLayout.CELL_SIZE,BagLayout.CELL_SIZE));
        }
        int bagTop=BagLayout.backpackY(rig.h())-scroll;
        slot(g,43,"背包",BagLayout.STORAGE_X,bagTop,60,78); grid(g,1,BagLayout.GRID_X,bagTop+16,bag.w(),bag.h());
        int recoveryTop=bagTop+BagLayout.storageSectionHeight(bag.h())+26;
        var recovery=state.entries.stream().filter(e->e.area==2).toList();
        if(!recovery.isEmpty()) {
            text(g,"待整理 · 只可取出（旧存档物品）",BagLayout.STORAGE_X,recoveryTop,TEXT);
            for(int i=0;i<recovery.size();i++) {
                var e=recovery.get(i); int x=BagLayout.STORAGE_X+(i%6)*40,y=recoveryTop+18+(i/6)*44;
                itemBox(g,e.id,x,y,36,38); hits.add(new Hit(e.id,2,0,0,x,y,36,38));
            }
        }
        g.disableScissor();
        g.fill(554,44,557,400,SLOT_BACKGROUND);
        int max=maxScroll(),thumb=Math.max(30,356*360/(360+max));
        int pos=max==0?0:scroll*(356-thumb)/max; g.fill(554,44+pos,557,44+pos+thumb,SLOT_BORDER);
        if(loot!=null) renderLoot(g);
        drawItemName(g);
        g.fill(509,18,553,33,EMPHASIS); border(g,509,18,44,15,SLOT_BORDER);
        text(g,ProfileClient.editable()?"物品配置":"物品预览",512,22,TEXT);
        if(selected!=-1) {
            drawSourceMask(g);
            drawPlacementPreview(g,placementPreview());
            drawFloatingIcon(g);
        } else { floatingSelection=-1; dragSourceIcon=null; floatingIcon=null; wholeDrag=null; lastDragFrame=0; }
        g.pose().popPose();
    }
    private void drawItemName(GuiGraphics g) {
        var target=hit(mouseX,mouseY); int id=selected!=-1?selected:target==null?-1:target.id;
        var item=stack(id); if(item.isEmpty()) return;
        var rarity=ItemProfiles.rarity(item);
        String label=item.getHoverName().getString()+" · "+rarity.label;
        text(g,font.plainSubstrByWidth(label,150),BagLayout.STORAGE_X+76,22,rarity.color(255));
    }
    private void itemCaption(GuiGraphics g,ItemStack item,int x,int y,int w,int h) {
        if(w<56 || h<45) return;
        g.pose().pushPose(); g.pose().translate(x+3,y+3,200); g.pose().scale(.65f,.65f,1);
        g.drawString(font,font.plainSubstrByWidth(item.getHoverName().getString(),(int)((w-6)/.65f)),0,0,ItemProfiles.rarity(item).color(255),true);
        g.pose().popPose();
    }
    private void beginDragVisual(double mx,double my) {
        preferredRotation=rotated; placementValidity.clear();
        var source=iconBounds.get(selected);
        floatingSelection=source==null?-1:selected;
        dragSourceIcon=source; floatingIcon=source; lastDragFrame=0;
        wholeDrag=null;
        if(source==null) return;
        dragInitialRotation=rotated;
        followX=(float)((mx-offsetX)/scale); followY=(float)((my-offsetY)/scale);
        grabRatioX=(followX-source.x)/source.w; grabRatioY=(followY-source.y)/source.h;
        var size=Rules.size(stack(selected));
        var bounds=itemBounds.get(selected);
        if(bounds!=null && (size.w()>1 || size.h()>1)) {
            itemAnchorX=bounds.x; itemAnchorY=bounds.y;
            wholeItemWidth=(rotated?size.h():size.w())*BagLayout.GRID_STEP;
            wholeItemHeight=(rotated?size.w():size.h())*BagLayout.GRID_STEP;
            double pressX=(mx-offsetX)/scale,pressY=(my-offsetY)/scale;
            wholeDrag=WholeItemDrag.capture(pressX,pressY,itemAnchorX,itemAnchorY,wholeItemWidth,wholeItemHeight);
            dragOffsetX=wholeDrag.offsetX(); dragOffsetY=wholeDrag.offsetY();
        }
    }
    private boolean isFloatingSource(int id) {
        return selected==id && floatingSelection==id && dragSourceIcon!=null;
    }
    private void sourceIcon(GuiGraphics g,int id,ItemStack item,int x,int y,int w,int h) {
        iconBounds.put(id,new IconBox(x,y,w,h,w,h));
        orientedIcon(g,item,x,y,w,h,storedRotation(id));
    }
    private void markSourceMask(int id,int area,int x,int y,int w,int h) {
        itemBounds.put(id,new Hit(id,area,0,0,x,y,w,h));
        if(isFloatingSource(id)) sourceMask=new Hit(id,area,0,0,x,y,w,h);
    }
    private void drawSourceMask(GuiGraphics g) {
        if(sourceMask==null) return;
        boolean clipped=overlayScissor(g,sourceMask.area,sourceMask.x);
        g.pose().pushPose(); g.pose().translate(0,0,360);
        g.fill(sourceMask.x,sourceMask.y,sourceMask.x+sourceMask.w,sourceMask.y+sourceMask.h,SOURCE_MASK);
        g.pose().popPose();
        if(clipped) g.disableScissor();
    }
    private void drawFloatingIcon(GuiGraphics g) {
        if(!isFloatingSource(selected)) return;
        long now=net.minecraft.Util.getMillis();
        if(wholeDrag==null && lastDragFrame!=0) {
            float dt=Math.min(0.05f,Math.max(0,(now-lastDragFrame)/1000f));
            float follow=1-(float)Math.exp(-35*dt);
            float targetX=(ghostX-offsetX)/scale,targetY=(ghostY-offsetY)/scale;
            followX+=(targetX-followX)*follow; followY+=(targetY-followY)*follow;
        }
        lastDragFrame=now;
        boolean swapped=rotated!=dragInitialRotation;
        float w=swapped?dragSourceIcon.h:dragSourceIcon.w,h=swapped?dragSourceIcon.w:dragSourceIcon.h;
        int paintW=swapped?dragSourceIcon.paintH:dragSourceIcon.paintW,paintH=swapped?dragSourceIcon.paintW:dragSourceIcon.paintH;
        float x=followX-grabRatioX*w,y=followY-grabRatioY*h;
        if(wholeDrag!=null) {
            double pointerX=(dragMouseX-offsetX)/scale,pointerY=(dragMouseY-offsetY)/scale;
            var root=wholeDrag.root(pointerX,pointerY,swapped);
            float paddingX=(float)(dragSourceIcon.x-itemAnchorX),paddingY=(float)(dragSourceIcon.y-itemAnchorY);
            x=(float)root.x()+(swapped?paddingY:paddingX);
            y=(float)root.y()+(swapped?paddingX:paddingY);
            followX=(float)pointerX; followY=(float)pointerY;
        }
        floatingIcon=new IconBox(x,y,w,h,paintW,paintH);
        g.pose().pushPose(); g.pose().translate(x,y,400); g.pose().scale(w/paintW,h/paintH,1);
        orientedIcon(g,stack(selected),0,0,paintW,paintH,rotated); g.pose().popPose();
    }
    private PlacementPreview placementPreview() {
        if(selected==-1) return null;
        var target=dropTarget(wholeDrag==null?mouseX:dragMouseX,wholeDrag==null?mouseY:dragMouseY);
        if(target==null) return null;
        if(target.area<0 || target.area==2)
            return new PlacementPreview(target.area,target.x,target.y,target.w,target.h,canDrop(target));
        var size=Rules.size(stack(selected));
        int columns=rotated?size.h():size.w(),rows=rotated?size.w():size.h();
        int width=target.area==LootSession.AREA?columns*BagLayout.LOOT_CELL:BagLayout.gridItemSize(target.area,columns);
        int height=target.area==LootSession.AREA?rows*BagLayout.LOOT_CELL:BagLayout.gridItemSize(target.area,rows);
        // Grid hits carry the target cell's origin, independently of the freely moving icon.
        return new PlacementPreview(target.area,target.x,target.y,width,height,canDrop(target));
    }
    private void drawPlacementPreview(GuiGraphics g,PlacementPreview preview) {
        if(preview==null) return;
        boolean clipped=overlayScissor(g,preview.area,preview.x);
        g.pose().pushPose(); g.pose().translate(0,0,380);
        g.fill(preview.x,preview.y,preview.x+preview.w,preview.y+preview.h,preview.valid?PLACE_ALLOWED:PLACE_BLOCKED);
        g.pose().popPose();
        if(clipped) g.disableScissor();
    }
    private boolean overlayScissor(GuiGraphics g,int area,int x) {
        if(area<0 && x<BagLayout.STORAGE_LEFT) return false;
        int left=area==LootSession.AREA?BagLayout.LOOT_X:BagLayout.STORAGE_LEFT;
        int right=area==LootSession.AREA?BagLayout.LOOT_X+BagLayout.LOOT_WIDTH:BagLayout.STORAGE_RIGHT;
        int top=area==LootSession.AREA?BagLayout.LOOT_GRID_TOP:BagLayout.STORAGE_TOP;
        int bottom=area==LootSession.AREA?BagLayout.LOOT_BOTTOM:BagLayout.STORAGE_BOTTOM;
        g.enableScissor((int)(offsetX+left*scale),(int)(offsetY+top*scale),
                (int)Math.ceil(offsetX+right*scale),(int)Math.ceil(offsetY+bottom*scale));
        return true;
    }
    public boolean hasLoot() { return loot!=null; }
    private int maxLootScroll() { return loot==null?0:Math.max(0,loot.rows*BagLayout.LOOT_CELL-(BagLayout.LOOT_BOTTOM-BagLayout.LOOT_GRID_TOP)); }
    private void renderLoot(GuiGraphics graphics) {
        int left=BagLayout.LOOT_X,top=BagLayout.LOOT_GRID_TOP,cell=BagLayout.LOOT_CELL,width=BagLayout.LOOT_WIDTH;
        lootScroll=Math.min(lootScroll,maxLootScroll());
        graphics.fill(left,BagLayout.LOOT_TOP,left+width,top,PANEL);
        border(graphics,left,BagLayout.LOOT_TOP,width,top-BagLayout.LOOT_TOP,SLOT_BORDER);
        text(graphics,font.plainSubstrByWidth(loot.title.getString(),width-48),left+4,BagLayout.LOOT_TOP+4,TEXT);
        long known=loot.entries.values().stream().filter(LootView.Entry::revealed).count();
        String count=known+"/"+loot.entries.size();
        text(graphics,count,left+width-4-font.width(count),BagLayout.LOOT_TOP+4,TEXT);
        graphics.enableScissor((int)(offsetX+left*scale),(int)(offsetY+top*scale),
                (int)Math.ceil(offsetX+(left+width)*scale),(int)Math.ceil(offsetY+BagLayout.LOOT_BOTTOM*scale));
        int originY=top-lootScroll;
        int firstRow=lootScroll/cell,lastRow=Math.min(loot.rows,(lootScroll+BagLayout.LOOT_BOTTOM-top+cell-1)/cell);
        for(int row=firstRow;row<lastRow;row++) for(int column=0;column<6;column++) {
            int x=left+column*cell,y=originY+row*cell;
            int cx=column,cy=row;
            boolean occupied=loot.entries.values().stream().anyMatch(entry-> {
                var rect=entry.rectangle();
                return cx>=rect.x() && cx<rect.x()+rect.w() && cy>=rect.y() && cy<rect.y()+rect.h();
            });
            if(!occupied) {
                graphics.fill(x,y,x+cell,y+cell,SLOT_BACKGROUND);
                graphics.fill(x,y,x+cell,y+1,SLOT_BORDER); graphics.fill(x,y,x+1,y+cell,SLOT_BORDER);
                if(hovered(x,y,cell,cell)) {
                    graphics.fill(x,y,x+cell,y+cell,EMPHASIS);
                    border(graphics,x,y,cell,cell,ACTIVE_BORDER,2);
                }
            }
            hits.add(new Hit(-1,LootSession.AREA,column,row,x,y,cell,cell));
        }
        for(var entry:loot.entries.values()) {
            var rectangle=entry.rectangle();
            int x=left+rectangle.x()*cell,y=originY+rectangle.y()*cell,w=rectangle.w()*cell,h=rectangle.h()*cell;
            if(y+h<=top || y>=BagLayout.LOOT_BOTTOM) continue;
            if(!entry.revealed()) {
                boolean emphasized=entry.id()==loot.active || hovered(x,y,w,h);
                graphics.fill(x+1,y+1,x+w,y+h,emphasized?EMPHASIS:SLOT_BACKGROUND);
                border(graphics,x,y,w,h,emphasized?ACTIVE_BORDER:SLOT_BORDER,emphasized?2:1);
                if(entry.id()==loot.active) {
                    int sweep=(int)(loot.progress()*Math.max(1,h-2));
                    graphics.fillGradient(x+1,y+Math.max(1,sweep-8),x+w-1,y+sweep+1,0x00FFFFFF,0x80FFFFFF);
                    int iconSize=Math.min(20,Math.min(w,h)-8);
                    sprite(graphics,texture("search"),x+(w-iconSize)/2,y+(h-iconSize)/2,iconSize,iconSize);
                    graphics.fill(x+1,y+h-3,x+1+(int)((w-2)*loot.progress()),y+h-1,TEXT);
                }
            } else {
                boolean emphasized=selected==entry.id() || hovered(x,y,w,h);
                graphics.fill(x+1,y+1,x+w,y+h,emphasized?EMPHASIS:SLOT_BACKGROUND);
                ItemVisuals.surface(graphics,ItemProfiles.rarity(entry.stack()),x,y,w,h,emphasized);
                float progress=Math.min(1,(net.minecraft.Util.getMillis()-entry.appeared())/260f);
                float revealScale=0.76f+0.24f*(1-(float)Math.pow(1-progress,3));
                graphics.pose().pushPose(); graphics.pose().translate(x+w/2f,y+h/2f,0);
                graphics.pose().scale(revealScale,revealScale,1); graphics.pose().translate(-x-w/2f,-y-h/2f,0);
                iconBounds.put(entry.id(),new IconBox(x+w/2f+(3-w/2f)*revealScale,
                        y+h/2f+(3-h/2f)*revealScale,(w-6)*revealScale,(h-6)*revealScale,w-6,h-6));
                orientedIcon(graphics,entry.stack(),x+3,y+3,w-6,h-6,entry.rotated());
                graphics.pose().popPose();
                itemCount(graphics,entry.stack(),x,y,w,h);
                itemCaption(graphics,entry.stack(),x,y,w,h);
                if(progress<1) border(graphics,x+1,y+1,w-2,h-2,((int)(180*(1-progress))<<24)|0xFFFFFF);
                markSourceMask(entry.id(),LootSession.AREA,x,y,w,h);
            }
            for(int row=0;row<rectangle.h();row++) for(int column=0;column<rectangle.w();column++)
                hits.add(new Hit(entry.id(),LootSession.AREA,rectangle.x()+column,rectangle.y()+row,x+column*cell,y+row*cell,cell,cell));
        }
        graphics.disableScissor();
        int maximum=maxLootScroll();
        if(maximum>0) {
            int track=BagLayout.LOOT_BOTTOM-top,thumb=Math.max(20,track*track/(track+maximum));
            int position=lootScroll*(track-thumb)/maximum;
            graphics.fill(left+width+4,top,left+width+7,BagLayout.LOOT_BOTTOM,SLOT_BACKGROUND);
            graphics.fill(left+width+4,top+position,left+width+7,top+position+thumb,SLOT_BORDER);
        }
    }
    private void renderPlayer(GuiGraphics graphics) {
        previewPlayer.syncArmor();
        int modelScale=inventory[38].isEmpty()?190:170;
        var dispatcher=minecraft.getEntityRenderDispatcher();
        boolean showHitboxes=dispatcher.shouldRenderHitBoxes();
        try {
            dispatcher.setRenderHitBoxes(false);
            InventoryScreen.renderEntityInInventoryFollowsAngle(graphics,
                    Math.round(offsetX),Math.round(offsetY),Math.round(offsetX+196*scale),Math.round(offsetY+420*scale),
                    Math.max(1,Math.round(modelScale*scale)),BagLayout.playerOffset(modelScale,!inventory[38].isEmpty(),previewPlayer.getBbHeight()),0f,0f,previewPlayer);
        } finally { dispatcher.setRenderHitBoxes(showHitboxes); }
    }
    private void equipmentSlot(GuiGraphics graphics,BagLayout.EquipmentBox box) {
        var item=stack(box.id());
        slotSurface(graphics,box.x(),box.y(),box.width(),box.height(),
                selected==box.id() || hovered(box.x(),box.y(),box.width(),box.height()));
        if(item.isEmpty()) emptyIcon(graphics,box.id(),box.x(),box.y(),box.width(),box.height());
        else {
            ItemVisuals.surface(graphics,ItemProfiles.rarity(item),box.x(),box.y(),box.width(),box.height(),selected==box.id() || hovered(box.x(),box.y(),box.width(),box.height()));
            sourceIcon(graphics,box.id(),item,box.x()+5,box.y()+5,box.width()-10,box.height()-10);
            itemCaption(graphics,item,box.x(),box.y(),box.width(),box.height());
        }
        markSourceMask(box.id(),-1,box.x(),box.y(),box.width(),box.height());
        var target=new Hit(box.id(),-1,0,0,box.x(),box.y(),box.width(),box.height());
        hits.add(target);
    }
    private void emptyIcon(GuiGraphics graphics,int id,int x,int y,int width,int height) {
        var sprite=EMPTY_ICONS.get(id);
        if(sprite==null) return;
        int iconWidth=Math.min(width-8,height-8),iconHeight=iconWidth;
        if(id==0 || id==1) { iconWidth=width-12; iconHeight=iconWidth*96/256; }
        sprite(graphics,sprite,x+(width-iconWidth)/2,y+(height-iconHeight)/2,iconWidth,iconHeight);
    }
    private void text(GuiGraphics g,String text,int x,int y,int color) {
        g.drawString(font,text,x,y+1,TEXT_SHADOW,false);
        g.drawString(font,text,x,y,color,false);
    }
    private boolean hovered(int x,int y,int w,int h) {
        double px=(mouseX-offsetX)/scale,py=(mouseY-offsetY)/scale;
        return px>=x && py>=y && px<x+w && py<y+h;
    }
    private void slotSurface(GuiGraphics g,int x,int y,int w,int h,boolean emphasized) {
        if(emphasized) {
            g.fill(x,y,x+w,y+h,EMPHASIS);
            border(g,x,y,w,h,ACTIVE_BORDER,2);
        } else sprite(g,EMPTY_SLOT,x,y,w,h);
    }
    private void pocketLabel(GuiGraphics g,int x,int y) {
        sectionHeader(g,"口袋",capacityText(-1),x,y);
        sprite(g,EMPTY_SLOT,x,y+15,60,BagLayout.POCKET_SECTION_HEIGHT-15);
        sprite(g,texture("pocket"),x+10,y+17,40,40);
    }
    private String capacityText(int area) {
        if(area<0) {
            int used=0;
            for(int slot=4;slot<=8;slot++) if(!inventory[slot].isEmpty()) used++;
            return used+"/5";
        }
        int used=0;
        for(var entry:state.entries) if(entry.area==area && !entry.stack.isEmpty()) {
            var rect=entry.rect(); used+=rect.w()*rect.h();
        }
        var capacity=state.capacity(area);
        return used+"/"+(capacity.w()*capacity.h());
    }
    private void sectionHeader(GuiGraphics g,String label,String count,int x,int y) {
        int width=BagLayout.SECTION_WIDTH;
        g.fill(x,y,x+width,y+14,PANEL);
        border(g,x,y,width,14,SLOT_BORDER);
        text(g,label,x+4,y+3,TEXT);
        text(g,count,x+width-4-font.width(count),y+3,TEXT);
    }
    private void sprite(GuiGraphics g,ResourceLocation sprite,int x,int y,int w,int h) {
        // Minecraft's uncolored sprite blit does not enable alpha blending itself.
        g.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        try { g.blitSprite(sprite,x,y,w,h); }
        finally { RenderSystem.disableBlend(); }
    }
    private void border(GuiGraphics g,int x,int y,int w,int h,int color) {
        border(g,x,y,w,h,color,1);
    }
    private void border(GuiGraphics g,int x,int y,int w,int h,int color,int thickness) {
        g.fill(x,y,x+w,y+thickness,color); g.fill(x,y+h-thickness,x+w,y+h,color);
        g.fill(x,y,x+thickness,y+h,color); g.fill(x+w-thickness,y,x+w,y+h,color);
    }
    private void slot(GuiGraphics g,int id,String name,int x,int y,int w,int h) {
        sectionHeader(g,name,capacityText(id-42),x,y);
        itemBox(g,id,x,y+15,w,h-15); hits.add(new Hit(id,-1,0,0,x,y+15,w,h-15));
    }
    private void itemBox(GuiGraphics g,int id,int x,int y,int w,int h) {
        var stack=stack(id);
        slotSurface(g,x,y,w,h,selected==id || hovered(x,y,w,h));
        if(stack.isEmpty()) {
            emptyIcon(g,id,x,y,w,h);
        } else {
            ItemVisuals.surface(g,ItemProfiles.rarity(stack),x,y,w,h,selected==id || hovered(x,y,w,h));
            sourceIcon(g,id,stack,x+3,y+3,w-6,h-6);
            itemCount(g,stack,x,y,w,h);
            itemCaption(g,stack,x,y,w,h);
            var entry=state.entry(id);
            markSourceMask(id,entry==null?-1:entry.area,x,y,w,h);
        }
    }
    private void itemCount(GuiGraphics graphics,ItemStack stack,int x,int y,int width,int height) {
        if(stack.getCount()<=1) return;
        String count=Integer.toString(stack.getCount());
        graphics.pose().pushPose(); graphics.pose().translate(0,0,200);
        text(graphics,count,x+width-3-font.width(count),y+height-10,TEXT);
        graphics.pose().popPose();
    }
    private void gridCellSurface(GuiGraphics g,int x,int y,int w,int h,int background) {
        g.fill(x,y,x+w,y+h,background);
        g.fill(x,y,x+w,y+1,SLOT_BORDER);
        g.fill(x,y,x+1,y+h,SLOT_BORDER);
        if(hovered(x,y,w,h)) {
            g.fill(x,y,x+w,y+h,EMPHASIS);
            border(g,x,y,w,h,ACTIVE_BORDER,2);
        }
    }
    private void gridItemBox(GuiGraphics g,int id,int x,int y,int w,int h,int background) {
        var item=stack(id);
        if(item.isEmpty()) { gridCellSurface(g,x,y,w,h,background); return; }
        boolean emphasized=selected==id || hovered(x,y,w,h);
        g.fill(x+1,y+1,x+w,y+h,emphasized?EMPHASIS:background);
        ItemVisuals.surface(g,ItemProfiles.rarity(item),x,y,w,h,emphasized);
        sourceIcon(g,id,item,x+3,y+3,w-6,h-6);
        itemCount(g,item,x,y,w,h);
        itemCaption(g,item,x,y,w,h);
        var entry=state.entry(id);
        markSourceMask(id,entry==null?-1:entry.area,x,y,w,h);
    }
    private void grid(GuiGraphics g,int area,int x,int y,int w,int h) {
        boolean enabled=state.unlocked(area);
        if(!enabled) return;
        int cellSize=BagLayout.gridCellSize(area);
        int background=area==1?BACKPACK_BACKGROUND:SLOT_BACKGROUND;
        for(int row=0;row<h;row++) for(int col=0;col<w;col++) {
            int px=x+col*BagLayout.GRID_STEP,py=y+row*BagLayout.GRID_STEP;
            int cx=col,cy=row;
            boolean occupied=state.entries.stream().anyMatch(entry-> {
                if(entry.area!=area) return false;
                var rect=entry.rect();
                return cx>=rect.x() && cx<rect.x()+rect.w() && cy>=rect.y() && cy<rect.y()+rect.h();
            });
            if(!occupied) {
                gridCellSurface(g,px,py,cellSize,cellSize,background);
            }
            hits.add(new Hit(-1,area,col,row,px,py,cellSize,cellSize));
        }
        border(g,x,y,w*BagLayout.GRID_STEP,h*BagLayout.GRID_STEP,SLOT_BORDER);
        for(var e:state.entries) if(e.area==area) {
            var rect=e.rect();
            int px=x+e.x*BagLayout.GRID_STEP,py=y+e.y*BagLayout.GRID_STEP;
            int itemWidth=BagLayout.gridItemSize(area,rect.w()),itemHeight=BagLayout.gridItemSize(area,rect.h());
            gridItemBox(g,e.id,px,py,itemWidth,itemHeight,background);
            // Add per-cell hits so a multi-cell item's top-left placement is explicit.
            for(int row=0;row<rect.h();row++) for(int col=0;col<rect.w();col++)
                hits.add(new Hit(e.id,area,e.x+col,e.y+row,x+(e.x+col)*BagLayout.GRID_STEP,y+(e.y+row)*BagLayout.GRID_STEP,
                        cellSize,cellSize));
        }
    }
    private void icon(GuiGraphics g,ItemStack stack,int x,int y,int w,int h) {
        ItemVisuals.icon(g,stack,x,y,w,h,false);
    }
    private boolean storedRotation(int id) {
        if(LootSession.isLootId(id)) {
            var entry=loot==null?null:loot.entries.get(id);
            return entry!=null && entry.rotated();
        }
        var entry=state.entry(id); return entry!=null && entry.rotated;
    }
    private void orientedIcon(GuiGraphics g,ItemStack item,int x,int y,int w,int h,boolean rotation) {
        if(!rotation) { icon(g,item,x,y,w,h); return; }
        g.pose().pushPose(); g.pose().translate(x+w,y,0);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90));
        icon(g,item,0,0,h,w); g.pose().popPose();
    }
    private Hit hit(double mx,double my) {
        double x=(mx-offsetX)/scale,y=(my-offsetY)/scale;
        for(int i=hits.size()-1;i>=0;i--) {
            var h=hits.get(i);
            if(h.area==LootSession.AREA) {
                if(x<BagLayout.LOOT_X || x>=BagLayout.LOOT_X+BagLayout.LOOT_WIDTH || y<BagLayout.LOOT_GRID_TOP || y>=BagLayout.LOOT_BOTTOM) continue;
            } else if(h.x>=BagLayout.STORAGE_LEFT && (x<BagLayout.STORAGE_LEFT || x>=BagLayout.STORAGE_RIGHT || y<BagLayout.STORAGE_TOP || y>=BagLayout.STORAGE_BOTTOM)) continue;
            if(h.contains(x,y)) return h;
        }
        return null;
    }
    private Hit dropTarget(double mx,double my) {
        if(selected==-1) return hit(mx,my);
        rotated=preferredRotation;
        var preferred=dropTargetFor(mx,my,preferredRotation);
        if(canDrop(preferred)) return preferred;
        var size=Rules.size(stack(selected));
        if(preferred!=null && (preferred.area==0 || preferred.area==1 || preferred.area==LootSession.AREA)
                && size.w()!=size.h()) {
            rotated=!preferredRotation;
            var alternate=dropTargetFor(mx,my,rotated);
            if(canDrop(alternate)) return alternate;
        }
        rotated=preferredRotation;
        return preferred;
    }
    private Hit dropTargetFor(double mx,double my,boolean orientation) {
        var pointer=hit(mx,my);
        if(pointer==null || wholeDrag==null || pointer.area<0 || pointer.area==2) return pointer;
        int area=pointer.area;
        int gridX=area==LootSession.AREA?BagLayout.LOOT_X:BagLayout.GRID_X;
        int gridY=area==LootSession.AREA?BagLayout.LOOT_GRID_TOP-lootScroll
                :area==0?BagLayout.STORAGE_CONTENT_TOP+BagLayout.SECTION_HEADER-scroll
                :BagLayout.backpackY(state.capacity(0).h())+BagLayout.SECTION_HEADER-scroll;
        int cellSize=area==LootSession.AREA?BagLayout.LOOT_CELL:BagLayout.GRID_STEP;
        var cell=wholeDrag.cell((mx-offsetX)/scale,(my-offsetY)/scale,gridX,gridY,cellSize,orientation!=dragInitialRotation);
        int id=-1;
        // Resolve occupancy at the item's root, not at the cursor's grabbed cell.
        if(area==LootSession.AREA) {
            for(var entry:loot.entries.values()) {
                var rect=entry.rectangle();
                if(cell.x()>=rect.x() && cell.x()<rect.x()+rect.w() && cell.y()>=rect.y() && cell.y()<rect.y()+rect.h()) {
                    id=entry.id(); break;
                }
            }
        } else for(var entry:state.entries) if(entry.area==area) {
            var rect=entry.rect();
            if(cell.x()>=rect.x() && cell.x()<rect.x()+rect.w() && cell.y()>=rect.y() && cell.y()<rect.y()+rect.h()) {
                id=entry.id; break;
            }
        }
        return new Hit(id,area,cell.x(),cell.y(),gridX+cell.x()*cellSize,gridY+cell.y()*cellSize,cellSize,cellSize);
    }
    private void send(int op,Hit target) {
        if(selected==-1 || revision<0) return;
        if(!state.canRemove(selected)) { notice="请先清空胸挂或背包，再卸下装备"; return; }
        if(op==1 && !canDrop(target)) { notice="无法放置：类型不符、空间不足或位置被占用"; return; }
        notice="";
        PacketDistributor.sendToServer(new Packets.Action(menu.containerId,revision,op,selected,
                target==null?-1:target.area,target==null?0:target.area<0?target.id:target.cellX,
                target==null?0:target.cellY,rotated,half)); selected=-1;
    }
    private boolean canDrop(Hit target) {
        if(target==null || selected==-1 || !state.canRemove(selected)) return false;
        var source=stack(selected); var dest=stack(target.id);
        if(target.area<0 && (target.id==42 || target.id==43) && !dest.isEmpty())
            return !half && state.swapGear(minecraft.player,selected,target.id,false);
        boolean merge=target.id!=selected && !dest.isEmpty() && ItemStack.isSameItemSameComponents(source,dest)
                && dest.getCount()<(target.area==LootSession.AREA && loot!=null?loot.maxCount(dest):dest.getMaxStackSize());
        if(target.area<0 && (target.id==selected || !Rules.accepts(target.id,source))) return false;
        if(target.area==2 || target.area==LootSession.AREA && loot==null) return false;
        if(target.area>=0 && target.area!=LootSession.AREA && (target.area>1 || !state.unlocked(target.area))) return false;
        if((selected==42 || selected==43) && target.area==selected-42) return false;
        if(merge) return target.area!=LootSession.AREA || loot.canPlace(source,selected,target.cellX,target.cellY,rotated,!half || source.getCount()==1);
        var key=new PlanKey(revision,selected,target.area,target.id,target.cellX,target.cellY,rotated,half);
        var cached=placementValidity.get(key); if(cached!=null) return cached;
        int amount=BagPlacement.amount(source.getCount(),half,target.area,target.id);
        if(target.area==LootSession.AREA) amount=Math.min(amount,loot.maxCount(source));
        boolean entire=amount==source.getCount();
        var model=placementModel(source.copyWithCount(amount));
        int region=target.area<0?BagPlacement.fixedRegion(target.id):target.area;
        boolean valid=PlacementPlanner.plan(model,selected,region,target.area<0?0:target.cellX,
                target.area<0?0:target.cellY,rotated,entire)!=null;
        if(placementValidity.size()>256) placementValidity.clear();
        placementValidity.put(key,valid);
        return valid;
    }
    private PlacementPlanner.Model placementModel(ItemStack incoming) {
        var regions=new ArrayList<PlacementPlanner.Region>();
        for(int area=0;area<=1;area++) if(state.unlocked(area)) {
            var capacity=state.capacity(area);
            regions.add(new PlacementPlanner.Region(area,capacity.w(),capacity.h(),area==0,false));
        }
        for(int slot:FIXED_SLOTS) regions.add(new PlacementPlanner.Region(BagPlacement.fixedRegion(slot),1,1,false,true));
        if(loot!=null) regions.add(new PlacementPlanner.Region(LootSession.AREA,6,loot.rows,false,false,loot.slots));
        var items=new ArrayList<PlacementPlanner.Item>();
        for(int slot:FIXED_SLOTS) {
            var item=stack(slot);
            if(!item.isEmpty()) addPlacementItem(items,slot,BagPlacement.fixedRegion(slot),new Grid.Rect(0,0,1,1),false,
                    item,slot==selected?incoming:item,state.canRemove(slot));
        }
        for(var entry:state.entries) if(!entry.stack.isEmpty() && (entry.area!=2 || entry.id==selected))
            addPlacementItem(items,entry.id,entry.area,entry.rect(),entry.rotated,entry.stack,
                    entry.id==selected?incoming:entry.stack,true);
        if(loot!=null) for(var entry:loot.entries.values()) {
            if(entry.revealed()) addPlacementItem(items,entry.id(),LootSession.AREA,entry.rectangle(),entry.rotated(),entry.stack(),
                    entry.id()==selected?incoming:entry.stack(),true);
            else items.add(new PlacementPlanner.Item(entry.id(),LootSession.AREA,entry.rectangle(),entry.rectangle().w(),entry.rectangle().h(),
                    false,false,Set.of()));
        }
        return new PlacementPlanner.Model(regions,items);
    }
    private void addPlacementItem(List<PlacementPlanner.Item> items,int id,int region,Grid.Rect rectangle,boolean orientation,
                                  ItemStack original,ItemStack part,boolean movable) {
        var size=Rules.size(original); var allowed=new LinkedHashSet<Integer>();
        for(int slot:FIXED_SLOTS) if(Rules.accepts(slot,part) && (!(slot<=3 || slot>=36) || part.getCount()==1))
            allowed.add(BagPlacement.fixedRegion(slot));
        for(int area=0;area<=1;area++) if(state.unlocked(area) && !((id==42 || id==43) && area==id-42)) allowed.add(area);
        if(loot!=null && part.getCount()<=loot.maxCount(part)) allowed.add(LootSession.AREA);
        items.add(new PlacementPlanner.Item(id,region,rectangle,size.w(),size.h(),orientation,movable,allowed));
    }
    @Override public boolean mouseClicked(double mx,double my,int button) {
        if(closingAt!=0) return true;
        double localX=(mx-offsetX)/scale,localY=(my-offsetY)/scale;
        if(button==0 && localX>=509 && localX<553 && localY>=18 && localY<33) {
            profilesChanged(); ProfileClient.requestOpen(); return true;
        }
        if(button!=0 && button!=1) return false;
        var target=hit(mx,my);
        if(selected!=-1) { target=dropTarget(mx,my); if(target!=null) send(1,target); else selected=-1; return true; }
        if(target==null || stack(target.id).isEmpty()) return true;
        selected=target.id; half=button==1; var e=state.entry(selected); rotated=e!=null && e.rotated;
        if(LootSession.isLootId(selected) && loot!=null) {
            rotated=loot.entries.get(selected).rotated();
        }
        ghostX=(float)mx; ghostY=(float)my;
        beginDragVisual(mx,my);
        pressX=mx; pressY=my; dragged=false;
        if(hasShiftDown()) send(2,null); return true;
    }
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy) {
        if(selected!=-1 && Math.hypot(mx-pressX,my-pressY)>3) dragged=true; return true;
    }
    @Override public boolean mouseReleased(double mx,double my,int button) {
        if(selected!=-1 && dragged) {
            var target=dropTarget(mx,my);
            if(target!=null) send(1,target);
            // Selection is only a preview: a rejected move leaves the server's original
            // slot untouched. Always release that preview when the drag ends.
            selected=-1;
            dragged=false;
            rotated=false;
            half=false;
        }
        return true;
    }
    private int maxScroll() {
        long count=state.entries.stream().filter(e->e.area==2).count();
        int bottom=BagLayout.backpackY(state.capacity(0).h())+BagLayout.storageSectionHeight(state.capacity(1).h());
        if(count>0) bottom+=44+(int)((count+5)/6)*44;
        return Math.max(0,bottom-402);
    }
    @Override public boolean mouseScrolled(double mx,double my,double dx,double dy) {
        double x=(mx-offsetX)/scale;
        if(loot!=null && x>=BagLayout.LOOT_X) { lootScroll=Math.max(0,Math.min(maxLootScroll(),lootScroll-(int)(dy*BagLayout.LOOT_CELL))); return true; }
        if(x>=BagLayout.STORAGE_LEFT && x<BagLayout.WIDTH) scroll=Math.max(0,Math.min(maxScroll(),scroll-(int)(dy*28))); return true;
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==GLFW.GLFW_KEY_R && selected!=-1) {
            preferredRotation=!rotated; rotated=preferredRotation; placementValidity.clear(); return true;
        }
        if(key==GLFW.GLFW_KEY_Q) { if(selected==-1) { var h=hit(mouseX,mouseY); if(h!=null) selected=h.id; } send(3,null); return true; }
        if(key==GLFW.GLFW_KEY_ESCAPE || minecraft.options.keyInventory.matches(key,scan)) { if(selected!=-1) selected=-1; else onClose(); return true; }
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void removed() { if(ui!=null) ui.onRemoved(); super.removed(); }
    @Override public void onClose() {
        if(closingAt==0) { closingAt=net.minecraft.Util.getMillis(); selected=-1; dragged=false; }
    }
}
