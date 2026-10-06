package dev.tactical.loot.client;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import dev.tactical.loot.*;
import dev.tactical.profile.client.ItemVisuals;
import dev.tactical.profile.client.ProfileClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaPositionType;

/** Uses the existing item editor's panel, borders, inputs and navigation conventions. */
public final class LootAdminScreen extends ModularUIScreen {
    private static final int W=680,H=430,ROWS=8;
    private static final class Panel extends UIElement {
        LootAdminScreen host;
        @Override public void drawBackgroundAdditional(GUIContext context) { if(host!=null) host.draw(context.graphics); }
    }
    private record Entry(String id,String name,net.minecraft.world.item.ItemStack icon) {}
    private record Control(int x,int y,int w,int h,Runnable action) {}
    private final Panel panel;
    private final List<Control> controls=new ArrayList<>();
    private final List<Entry> catalog;
    private List<Entry> filtered=List.of();
    private Entry chosen;
    public TextField search,nameField,multiplierField;
    private boolean editable,enabled,registeredOnly,pending,dirty;
    private int page,revision,request,pendingRequest=-1;
    private float unit,left,top;
    private String query="",notice="",draftName="",draftMultiplier="1";
    public LootAdminScreen(boolean editable) { this(editable,new Panel()); }
    private LootAdminScreen(boolean editable,Panel panel) {
        super(ModularUI.of(UI.of(panel.layout(l->l.widthPercent(100).heightPercent(100)))),Component.literal("搜刮管理"));
        this.panel=panel; panel.host=this; this.editable=editable;
        catalog=BuiltInRegistries.BLOCK.stream().filter(block->!block.defaultBlockState().isAir()).map(block->
                new Entry(BuiltInRegistries.BLOCK.getKey(block).toString(),block.getName().getString(),block.asItem().getDefaultInstance()))
                .sorted(java.util.Comparator.comparing(Entry::name).thenComparing(Entry::id)).toList();
        filter(); chosen=filtered.isEmpty()?null:filtered.getFirst(); loadDraft();
        var mc=Minecraft.getInstance();
        if(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && !mc.level.getBlockState(hit.getBlockPos()).isAir())
            select(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(hit.getBlockPos()).getBlock()).toString());
    }
    @Override public void init() {
        if(nameField!=null) { draftName=nameField.getText(); draftMultiplier=multiplierField.getText(); }
        super.init(); unit=Math.min(1.2f,Math.min((width-24f)/W,(height-24f)/H));
        left=(width-W*unit)/2; top=(height-H*unit)/2; panel.clearAllChildren();
        search=field(22,70,202,23).setText(query,false).setTextResponder(value->{query=value;page=0;filter();});
        search.textFieldStyle(style->style.placeholder(Component.literal("名称 / 方块 ID / 模组 ID")));
        nameField=field(260,155,394,25).setText(draftName,false).setTextValidator(value->value.length()<=LootRule.MAX_NAME)
                .setTextResponder(value->{draftName=value;dirty=true;});
        nameField.textFieldStyle(style->style.placeholder(Component.literal("留空使用方块 / 容器原名称")));
        multiplierField=field(260,219,112,25).setNumbersOnlyDouble(0,LootRule.MAX_MULTIPLIER).setText(draftMultiplier,false)
                .setTextResponder(value->{draftMultiplier=value;dirty=true;});
    }
    private TextField field(int x,int y,int w,int h) {
        var field=new TextField();
        field.layout(l->l.positionType(YogaPositionType.ABSOLUTE).left(left+x*unit).top(top+y*unit).width(w*unit).height(h*unit).paddingHorizontal(4*unit));
        field.style(style->style.background(new ColorRectTexture(0xFF182B3E)));
        field.textFieldStyle(style->style.fontSize(9*unit).textColor(0xFFFFFFFF).cursorColor(0xFFB9D9F0));
        panel.addChild(field); return field;
    }
    private void filter() {
        String text=query.trim().toLowerCase(Locale.ROOT);
        filtered=catalog.stream().filter(entry->!registeredOnly || LootSettings.client().rule(entry.id())!=null
                        && LootSettings.client().rule(entry.id()).enabled())
                .filter(entry->text.isEmpty() || (entry.name()+" "+entry.id()+" "+
                        (LootSettings.client().rule(entry.id())==null?"":LootSettings.client().rule(entry.id()).name())).toLowerCase(Locale.ROOT).contains(text)).toList();
        page=Math.max(0,Math.min(page,Math.max(0,(filtered.size()-1)/ROWS)));
    }
    private void loadDraft() {
        revision=LootSettings.client().revision(); dirty=false;
        var rule=chosen==null?null:LootSettings.client().rule(chosen.id());
        enabled=rule!=null && rule.enabled(); draftName=rule==null?"":rule.name();
        draftMultiplier=rule==null?"1":format(rule.multiplier());
        if(nameField!=null) { nameField.setText(draftName,false); multiplierField.setText(draftMultiplier,false); }
    }
    public void select(String id) {
        if(pending) return;
        chosen=catalog.stream().filter(entry->entry.id().equals(id)).findFirst().orElse(chosen); loadDraft();
        if(filtered.stream().noneMatch(entry->entry.id().equals(id))) {
            registeredOnly=false;query="";if(search!=null)search.setText("",false);filter();
        }
        for(int i=0;i<filtered.size();i++) if(filtered.get(i).id().equals(id)) {page=i/ROWS;break;}
    }
    public String selectedBlock() { return chosen==null?null:chosen.id(); }
    public void setEnabled(boolean value) { enabled=value;dirty=true; }
    public void saveDraft() {
        if(!editable || pending || chosen==null) return;
        try {
            var rule=new LootRule(chosen.id(),nameField.getText(),Double.parseDouble(multiplierField.getText()),enabled);
            pending=true; pendingRequest=++request; notice="正在保存…";
            PacketDistributor.sendToServer(new LootPackets.Edit(pendingRequest,revision,rule.block(),rule.name(),rule.multiplier(),rule.enabled()));
        } catch(IllegalArgumentException invalid) { notice="请输入有效名称和 0–100 的系数。"; }
    }
    public void accept(LootPackets.State packet) {
        editable=packet.editable(); filter();
        if(packet.request()==pendingRequest && pendingRequest>=0) {
            pending=false;pendingRequest=-1;notice=packet.notice(); if(packet.accepted()) loadDraft(); else revision=LootSettings.client().revision();
        } else if(!dirty && !pending) loadDraft();
        else if(revision!=LootSettings.client().revision() && !pending) notice="配置已更新，保存前请确认最新设置。";
    }
    private void chooseTarget() {
        var mc=Minecraft.getInstance();
        if(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && !mc.level.getBlockState(hit.getBlockPos()).isAir())
            select(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(hit.getBlockPos()).getBlock()).toString());
        else notice="请先对准要配置的方块。";
    }
    private void chooseHeld() {
        var mc=Minecraft.getInstance();
        if(mc.player.getMainHandItem().getItem() instanceof BlockItem item) select(BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString());
        else notice="请先把要配置的方块拿在手中。";
    }
    private void draw(GuiGraphics g) {
        controls.clear();g.fill(0,0,width,height,0x98081018);
        g.pose().pushPose();g.pose().translate(left,top,0);g.pose().scale(unit,unit,1);
        g.fill(0,0,W,H,0xEE142638);ItemVisuals.border(g,0,0,W,H,0xFF5B8DB8);
        g.fillGradient(1,1,W-1,52,0x902D5071,0x002D5071);
        label(g,"搜刮管理",22,19,1.5f,0xFFFFFFFF,true);
        button(g,"容器",260,15,90,26,0xFFC6DCEC,()->{});
        button(g,"物品 / 爆率",359,15,126,26,0xFF86ADCC,()->{if(!pending) ProfileClient.requestOpen();});
        button(g,"×",644,14,23,23,0xFF94B6D5,this::onClose);
        g.fill(241,59,242,395,0x665B8DB8);
        for(int row=0;row<ROWS;row++) {
            int index=page*ROWS+row,y=103+row*30;if(index>=filtered.size()) break;
            var entry=filtered.get(index);var rule=LootSettings.client().rule(entry.id());
            boolean selected=chosen!=null && chosen.id().equals(entry.id());
            g.fill(22,y,224,y+27,selected?0xFF2D4861:0xB0192E42);
            if(selected) ItemVisuals.border(g,22,y,202,27,0xFF86ADCC);
            if(!entry.icon().isEmpty()) ItemVisuals.icon(g,entry.icon(),28,y+3,21,21,false);
            label(g,trim(rule!=null && !rule.name().isEmpty()?rule.name():entry.name(),190),57,y+4,.8f,0xFFFFFFFF,false);
            label(g,rule!=null && rule.enabled()?"已启用 · ×"+format(rule.multiplier()):"未启用",57,y+16,.65f,0xFF9EB2C6,false);
            controls.add(new Control(22,y,202,27,()->{if(!pending) {chosen=entry;notice="";loadDraft();}}));
        }
        if(filtered.isEmpty()) label(g,"未找到匹配容器",40,160,.9f,0xFFADBECF,false);
        button(g,"‹",22,349,26,22,0xFF8FAECB,()->page=Math.max(0,page-1));
        button(g,"›",198,349,26,22,0xFF8FAECB,()->page=Math.min(Math.max(0,(filtered.size()-1)/ROWS),page+1));
        label(g,(page+1)+" / "+Math.max(1,(filtered.size()+ROWS-1)/ROWS)+" · "+filtered.size()+" 种",64,356,.75f,0xFFB9CBDE,false);
        button(g,registeredOnly?"显示所有方块":"只看已启用",22,382,202,25,0xFF6C9BC1,()->{registeredOnly=!registeredOnly;page=0;filter();});
        if(chosen!=null) {
            label(g,trim(chosen.name(),340),260,69,1.1f,0xFFFFFFFF,true);
            label(g,trim(chosen.id(),490),260,89,.7f,0xFF91A9C0,false);
            label(g,"容器名称",260,133,.85f,0xFFCFDCEA,false);
            label(g,"爆率系数",260,198,.85f,0xFFCFDCEA,false);
            label(g,"1 = 原概率   0.5 = 减半   2 = 加倍",388,227,.72f,0xFF9EB2C6,false);
            double multiplier=1;try {multiplier=Double.parseDouble(multiplierField.getText());} catch(NumberFormatException ignored) { }
            label(g,"最终爆率 = 基础爆率 × 容器系数（最高 100%）",260,267,.8f,0xFFCFDCEA,false);
            label(g,"例如：基础 20% → "+format(Math.min(100,20*multiplier))+"%",260,289,.85f,0xFFC6DCEC,false);
            button(g,enabled?"✓ 允许 F 搜刮":"+ 设为可搜刮容器",260,322,394,30,enabled?0xFFB9E4CD:0xFF8CACC7,()->{if(!pending)setEnabled(!enabled);});
            button(g,"准星方块",260,382,90,25,0xFF8CACC7,this::chooseTarget);
            button(g,"手持方块",359,382,84,25,0xFF8CACC7,this::chooseHeld);
            button(g,pending?"保存中…":editable?"保存并同步":"只读 · 无保存权限",453,382,201,25,0xFF8BB6D4,this::saveDraft);
        }
        label(g,notice.isEmpty()?(editable?"管理员配置 · 已生成的库存不会反复刷新":"只读预览 · 由管理员保存配置"):trim(notice,850),22,413,.67f,0xFFB2C6D9,false);
        g.pose().popPose();
    }
    private static String format(double value) { return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static String trim(String text,int width) { return Minecraft.getInstance().font.plainSubstrByWidth(text,width); }
    private static void label(GuiGraphics g,String text,int x,int y,float scale,int color,boolean bold) {
        var component=Component.literal(text);if(bold) component.withStyle(ChatFormatting.BOLD);
        g.pose().pushPose();g.pose().translate(x,y,1);g.pose().scale(scale,scale,1);g.drawString(Minecraft.getInstance().font,component,0,0,color,true);g.pose().popPose();
    }
    private void button(GuiGraphics g,String text,int x,int y,int w,int h,int color,Runnable action) {
        g.fill(x,y,x+w,y+h,0xFF213C53);ItemVisuals.border(g,x,y,w,h,color);label(g,text,x+7,y+(h-7)/2,.8f,color,false);
        controls.add(new Control(x,y,w,h,action));
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        double localX=(x-left)/unit,localY=(y-top)/unit;
        if(button==0) for(var control:List.copyOf(controls)) if(localX>=control.x && localY>=control.y && localX<control.x+control.w && localY<control.y+control.h) {
            control.action.run();return true;
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if((x-left)/unit<242) {page=Math.max(0,Math.min(Math.max(0,(filtered.size()-1)/ROWS),page+(vertical<0?1:-1)));return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { Minecraft.getInstance().setScreen(null); }
}
