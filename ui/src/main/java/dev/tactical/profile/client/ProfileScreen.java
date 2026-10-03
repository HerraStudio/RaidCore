package dev.tactical.profile.client;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import dev.tactical.Rules;
import dev.tactical.profile.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaPositionType;
import org.lwjgl.glfw.GLFW;

/** LDLib2 visual tree, text inputs, and live footprint/weapon previews. Drafts stay local. */
public final class ProfileScreen extends ModularUIScreen {
    private static final int W=680,H=410,ROWS=8;
    private static final class Panel extends UIElement {
        ProfileScreen host;
        @Override public void drawBackgroundAdditional(GUIContext context) { if(host!=null) host.draw(context.graphics); }
    }
    private record Control(String id,int x,int y,int w,int h,Runnable action) {}
    private final Panel panel;
    private final List<Control> controls=new ArrayList<>();
    private List<ProfileCatalog.Entry> catalog,filtered=List.of();
    private ProfileCatalog.Entry chosen;
    public TextField search,widthField,heightField;
    private Rarity rarity=Rarity.COMMON;
    private boolean editable,configuredOnly,previewRotated,dirty,pending;
    private int page,request,pendingRequest=-1,baseRevision;
    private float unit,left,top;
    private String notice="",query="";
    public ProfileScreen(boolean editable) { this(editable,new Panel()); }
    private ProfileScreen(boolean editable,Panel panel) {
        super(ModularUI.of(UI.of(panel.layout(l->l.widthPercent(100).heightPercent(100)))),Component.literal("物品配置"));
        this.panel=panel; panel.host=this; this.editable=editable;
        catalog=ProfileCatalog.build(); filter();
        var held=Minecraft.getInstance().player.getMainHandItem();
        chosen=catalog.stream().filter(e->e.key().equals(ItemProfiles.key(held))).findFirst().orElse(catalog.isEmpty()?null:catalog.getFirst());
        loadDraft(); locateChosen();
    }
    @Override public void init() {
        String draftWidth=widthField==null?null:widthField.getText(),draftHeight=heightField==null?null:heightField.getText();
        super.init();
        unit=Math.min(1.2f,Math.min((width-24f)/W,(height-24f)/H));
        left=(width-W*unit)/2; top=(height-H*unit)/2;
        panel.clearAllChildren();
        search=field("search",22,63,202,23).setText(query,false).setTextResponder(value->{query=value;page=0;filter();});
        search.textFieldStyle(s->s.placeholder(Component.literal("搜索名称 / 物品 ID / 枪械型号")));
        widthField=field("width",318,162,48,24).setNumbersOnlyInt(1,ItemProfile.MAX_SIZE).setTextResponder(value->dirty=true);
        heightField=field("height",460,162,48,24).setNumbersOnlyInt(1,ItemProfile.MAX_SIZE).setTextResponder(value->dirty=true);
        syncFields();
        if(draftWidth!=null) { widthField.setText(draftWidth,false); heightField.setText(draftHeight,false); }
    }
    private TextField field(String id,int x,int y,int w,int h) {
        var field=new TextField(); field.setId(id);
        field.layout(l->l.positionType(YogaPositionType.ABSOLUTE).left(left+x*unit).top(top+y*unit).width(w*unit).height(h*unit).paddingHorizontal(4*unit));
        field.style(s->s.background(new ColorRectTexture(0xFF182B3E)));
        field.textFieldStyle(s->s.fontSize(9*unit).textColor(0xFFFFFFFF).cursorColor(0xFFB9D9F0));
        panel.addChild(field); return field;
    }
    private void filter() {
        String text=query.toLowerCase(Locale.ROOT).trim();
        filtered=catalog.stream().filter(e->!configuredOnly || ItemProfile.resolve(ItemProfiles.client().entries(),e.key())!=null)
                .filter(e->text.isEmpty() || (e.name()+" "+e.key().encoded()).toLowerCase(Locale.ROOT).contains(text)).toList();
        page=Math.max(0,Math.min(page,Math.max(0,(filtered.size()-1)/ROWS)));
    }
    private void loadDraft() {
        if(chosen==null) return;
        var profile=ItemProfile.resolve(ItemProfiles.client().entries(),chosen.key());
        rarity=profile==null?ItemProfiles.defaultRarity(chosen.stack()):profile.rarity();
        baseRevision=ItemProfiles.client().revision(); dirty=false; previewRotated=false; syncFields();
    }
    private void syncFields() {
        if(chosen==null || widthField==null) return;
        var profile=ItemProfile.resolve(ItemProfiles.client().entries(),chosen.key()); var size=Rules.defaultSize(chosen.stack());
        widthField.setText(Integer.toString(profile==null?size.w():profile.width()),false);
        heightField.setText(Integer.toString(profile==null?size.h():profile.height()),false);
    }
    public void select(ItemProfile.Key key) {
        if(pending) return;
        chosen=catalog.stream().filter(e->e.key().equals(key)).findFirst().orElse(chosen); notice=""; loadDraft(); locateChosen();
    }
    private void locateChosen() {
        if(chosen==null) return;
        for(int i=0;i<filtered.size();i++) if(filtered.get(i).key().equals(chosen.key())) {page=i/ROWS;break;}
    }
    public ItemProfile.Key selectedKey() { return chosen==null?null:chosen.key(); }
    public Rarity draftRarity() { return rarity; }
    public void setRarity(Rarity value) { rarity=value; dirty=true; }
    public void saveDraft(boolean reset) {
        if(!editable || pending || chosen==null) return;
        try {
            int w=reset?1:Integer.parseInt(widthField.getText()),h=reset?1:Integer.parseInt(heightField.getText());
            new ItemProfile(chosen.key(),w,h,rarity);
            pendingRequest=++request; pending=true; notice="正在保存…";
            PacketDistributor.sendToServer(new ProfilePackets.Edit(pendingRequest,baseRevision,chosen.key().item(),chosen.key().content(),w,h,rarity.name(),reset));
        } catch(IllegalArgumentException invalid) { notice="请输入 1–6 的整数宽高。"; }
    }
    public void accept(ProfilePackets.State packet) {
        editable=packet.editable(); catalog=ProfileCatalog.build(); filter();
        if(packet.request()==pendingRequest && pendingRequest>=0) {
            pending=false; pendingRequest=-1; notice=packet.notice();
            if(packet.accepted()) loadDraft();
            else baseRevision=ItemProfiles.client().revision();
        } else if(!dirty && !pending) loadDraft();
        else if(baseRevision!=ItemProfiles.client().revision() && !pending) notice="服务器配置已更新；保存前请确认当前设置。";
    }
    private static int size(TextField field) { try {return Math.max(1,Math.min(6,Integer.parseInt(field.getText())));} catch(NumberFormatException e) {return 1;} }
    private void draw(GuiGraphics g) {
        controls.clear();
        g.fill(0,0,width,height,0x98081018);
        g.pose().pushPose(); g.pose().translate(left,top,0); g.pose().scale(unit,unit,1);
        g.fill(0,0,W,H,0xEE142638); ItemVisuals.border(g,0,0,W,H,0xFF5B8DB8);
        g.fillGradient(1,1,W-1,52,0x902D5071,0x002D5071);
        label(g,"物品配置",22,19,1.5f,0xFFFFFFFF,true);
        label(g,editable?"管理员 · 修改后同步所有玩家":"只读预览 · 管理员统一配置",260,23,.85f,0xFFB2C6D9,false);
        button(g,"close","×",644,14,23,23,0xFF94B6D5,this::onClose);
        g.fill(241,59,242,359,0x665B8DB8);
        for(int row=0;row<ROWS;row++) {
            int index=page*ROWS+row,y=94+row*30; if(index>=filtered.size()) break;
            var entry=filtered.get(index); var r=ItemProfiles.rarity(entry.stack());
            boolean selected=chosen!=null && chosen.key().equals(entry.key());
            g.fill(22,y,224,y+27,selected?0xFF2D4861:0xB0192E42);
            g.fill(22,y,24,y+27,r.color(255));
            if(selected) ItemVisuals.border(g,22,y,202,27,r.color(220));
            ItemVisuals.icon(g,entry.stack(),28,y+3,21,21,false);
            label(g,trim(entry.name(),170),57,y+4,.8f,r.color(255),false);
            var rule=ItemProfile.resolve(ItemProfiles.client().entries(),entry.key()); var footprint=rule==null?Rules.defaultSize(entry.stack()):new Rules.Size(rule.width(),rule.height());
            label(g,r.label+"  "+footprint.w()+"×"+footprint.h(),57,y+16,.65f,0xFF9EB2C6,false);
            controls.add(new Control("row"+row,22,y,202,27,()->{if(!pending) {chosen=entry;notice="";loadDraft();}}));
        }
        if(filtered.isEmpty()) label(g,"未找到匹配物品",41,159,.9f,0xFFADBECF,false);
        button(g,"previous","‹",22,338,26,22,0xFF8FAECB,()->{page=Math.max(0,page-1);});
        button(g,"next","›",198,338,26,22,0xFF8FAECB,()->{page=Math.min(Math.max(0,(filtered.size()-1)/ROWS),page+1);});
        label(g,(page+1)+" / "+Math.max(1,(filtered.size()+ROWS-1)/ROWS)+"  ·  "+filtered.size()+" 件",63,345,.75f,0xFFB9CBDE,false);
        button(g,"filter",configuredOnly?"显示全部":"只看已配置",22,366,202,25,0xFF6C9BC1,()->{configuredOnly=!configuredOnly;page=0;filter();});
        if(chosen!=null) {
            label(g,trim(chosen.name(),318),260,62,1.1f,rarity.color(255),true);
            label(g,trim(chosen.key().encoded(),490),260,81,.65f,0xFF91A9C0,false);
            for(int i=0;i<Rarity.values().length;i++) {
                var value=Rarity.values()[i]; int x=260+i*79;
                g.fill(x,101,x+73,137,rarity==value?value.color(55):0xFF1C3043);
                ItemVisuals.border(g,x,101,73,36,value.color(rarity==value?255:125));
                label(g,value.label,x+8,107,.9f,value.color(255),true);
                label(g,String.format("#%06X",value.rgb),x+8,122,.62f,value.color(200),false);
                controls.add(new Control("rarity"+i,x,101,73,36,()->setRarity(value)));
            }
            label(g,"占格宽",260,170,.9f,0xFFFFFFFF,false); label(g,"占格高",401,170,.9f,0xFFFFFFFF,false);
            button(g,"widthMinus","−",292,162,23,24,0xFF5B8DB8,()->{widthField.setText(Integer.toString(Math.max(1,size(widthField)-1)));});
            button(g,"widthPlus","+",369,162,23,24,0xFF5B8DB8,()->{widthField.setText(Integer.toString(Math.min(6,size(widthField)+1)));});
            button(g,"heightMinus","−",434,162,23,24,0xFF5B8DB8,()->{heightField.setText(Integer.toString(Math.max(1,size(heightField)-1)));});
            button(g,"heightPlus","+",511,162,23,24,0xFF5B8DB8,()->{heightField.setText(Integer.toString(Math.min(6,size(heightField)+1)));});
            button(g,"rotate",previewRotated?"预览 90°":"旋转预览",546,162,108,24,0xFF5B8DB8,()->previewRotated=!previewRotated);
            int w=size(widthField),h=size(heightField),pw=previewRotated?h:w,ph=previewRotated?w:h;
            label(g,"占格预览",260,204,.8f,0xFFC4D7E9,false);
            int gx=260,gy=220,cell=22;
            for(int y=0;y<6;y++) for(int x=0;x<6;x++) {
                g.fill(gx+x*cell,gy+y*cell,gx+(x+1)*cell,gy+(y+1)*cell,0xFF223A54);
                ItemVisuals.border(g,gx+x*cell,gy+y*cell,cell,cell,0x996B818D);
            }
            g.fill(gx,gy,gx+pw*cell,gy+ph*cell,0xFF223A54);
            ItemVisuals.surface(g,rarity,gx,gy,pw*cell,ph*cell,true);
            ItemVisuals.icon(g,chosen.stack(),gx+3,gy+3,pw*cell-6,ph*cell-6,previewRotated);
            label(g,w+"×"+h+" 格 · 占用 "+w*h+" 格",260,353,.73f,0xFFCFDCEA,false);
            g.fill(418,218,654,350,0xAA142536); ItemVisuals.surface(g,rarity,418,218,236,132,false);
            ItemVisuals.icon(g,chosen.stack(),428,226,216,105,previewRotated);
            label(g,trim(chosen.name(),320),428,337,.7f,rarity.color(255),false);
            button(g,"reset","恢复默认",260,366,151,25,0xFF8CACC7,()->saveDraft(true));
            button(g,"save",pending?"保存中…":editable?"保存并同步":"只读 · 无保存权限",418,366,236,25,rarity.color(255),()->saveDraft(false));
        }
        if(!notice.isEmpty()) label(g,trim(notice,750),22,398,.67f,notice.contains("保存") || notice.contains("恢复")?0xFFB9E4CD:0xFFE7BE97,false);
        g.pose().popPose();
    }
    private String trim(String text,int width) { return Minecraft.getInstance().font.plainSubstrByWidth(text,width); }
    private static void label(GuiGraphics g,String text,int x,int y,float scale,int color,boolean bold) {
        var component=Component.literal(text); if(bold) component.withStyle(ChatFormatting.BOLD);
        g.pose().pushPose(); g.pose().translate(x,y,1); g.pose().scale(scale,scale,1);
        g.drawString(Minecraft.getInstance().font,component,0,0,color,true); g.pose().popPose();
    }
    private void button(GuiGraphics g,String id,String text,int x,int y,int w,int h,int color,Runnable action) {
        g.fill(x,y,x+w,y+h,0xFF213C53); ItemVisuals.border(g,x,y,w,h,color);
        label(g,text,x+7,y+(h-7)/2,.8f,color,false); controls.add(new Control(id,x,y,w,h,action));
    }
    @Override public boolean mouseClicked(double mx,double my,int button) {
        double x=(mx-left)/unit,y=(my-top)/unit;
        if(button==0) for(var control:List.copyOf(controls)) if(x>=control.x && y>=control.y && x<control.x+control.w && y<control.y+control.h) {
            control.action.run(); return true;
        }
        return super.mouseClicked(mx,my,button);
    }
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double vertical) {
        if((mx-left)/unit<242) {page=Math.max(0,Math.min(Math.max(0,(filtered.size()-1)/ROWS),page+(vertical<0?1:-1)));return true;}
        return super.mouseScrolled(mx,my,horizontal,vertical);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==GLFW.GLFW_KEY_ESCAPE) {onClose();return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void renderBackground(GuiGraphics g,int x,int y,float partial) { renderBlurredBackground(partial); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { Minecraft.getInstance().setScreen(null); }
}
