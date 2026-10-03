package dev.tactical.crack.client;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.tactical.crack.CrackPackets;
import dev.tactical.crack.CrackRules;
import dev.tactical.loot.SafeBlockEntity;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Entire visual tree is hosted and drawn by LDLib2, with no vanilla container widgets. */
public final class CrackScreen extends ModularUIScreen {
    private static final class Panel extends UIElement {
        CrackScreen host;
        @Override public void drawBackgroundAdditional(GUIContext context) { if(host!=null) host.draw(context.graphics); }
    }
    public final SafeBlockEntity safe;
    public final UUID session;
    private long offset,opened=Util.getMillis(),terminalAt,lastClick;
    private int sequence;
    private boolean suppressCancel,spaceDown;
    private String notice="";
    public CrackScreen(SafeBlockEntity safe) { this(safe,new Panel()); }
    private CrackScreen(SafeBlockEntity safe,Panel panel) {
        super(ModularUI.of(UI.of(panel.layout(l->l.widthPercent(100).heightPercent(100)))),Component.literal("保险箱破译"));
        this.safe=safe; this.session=UUID.fromString(safe.hackSession); panel.host=this;
        offset=safe.hackServerTime-Util.getMillis()+halfPing();
    }
    public void accept(String notice) {
        long target=safe.hackServerTime-Util.getMillis()+halfPing();
        offset=Math.abs(target-offset)>180?target:(long)(offset*.8+target*.2);
        this.notice=notice;
        if(!safe.hackStatus.equals("active") && terminalAt==0) terminalAt=Util.getMillis();
        if(safe.hackStatus.equals("success")) closeFromServer();
    }
    private static int halfPing() {
        var mc=Minecraft.getInstance();
        var info=mc.getConnection()==null || mc.player==null?null:mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info==null?0:Math.min(75,Math.max(0,info.getLatency()/2));
    }
    public double serverNow() { return Util.getMillis()+offset; }
    public double pointerAngle() { return CrackRules.angle(safe.hackSeed,safe.hackSuccesses,
            serverNow()-safe.hackPhaseStart,safe.hackShakeStart<0?-1:serverNow()-safe.hackShakeStart); }
    public void hit() {
        long now=Util.getMillis();
        if(!safe.hackStatus.equals("active") || now-lastClick<180) return;
        lastClick=now;
        PacketDistributor.sendToServer(new CrackPackets.Input(session,safe.hackRevision,sequence++,false));
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if(key==GLFW.GLFW_KEY_SPACE && !spaceDown) { spaceDown=true; hit(); }
        return true;
    }
    @Override public boolean keyReleased(int key,int scan,int modifiers) { if(key==GLFW.GLFW_KEY_SPACE) spaceDown=false; return true; }
    @Override public boolean charTyped(char character,int modifiers) { return true; }
    @Override public boolean mouseClicked(double x,double y,int button) { if(button==0) hit(); return true; }
    @Override public boolean mouseReleased(double x,double y,int button) { return true; }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) { return true; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        if(!suppressCancel && safe.hackStatus.equals("active")) {
            suppressCancel=true;
            CrackClient.cancelled(session);
            if(Minecraft.getInstance().getConnection()!=null)
                PacketDistributor.sendToServer(new CrackPackets.Input(session,safe.hackRevision,sequence++,true));
        }
        if(minecraft!=null && minecraft.screen==this) minecraft.setScreen(null);
    }
    public void closeFromServer() { suppressCancel=true; onClose(); }
    @Override public void removed() {
        if(!suppressCancel && safe.hackStatus.equals("active")) {
            suppressCancel=true;
            CrackClient.cancelled(session);
            if(Minecraft.getInstance().getConnection()!=null)
                PacketDistributor.sendToServer(new CrackPackets.Input(session,safe.hackRevision,sequence++,true));
        }
        super.removed();
    }
    @Override public void tick() {
        super.tick();
        if(terminalAt!=0 && Util.getMillis()-terminalAt>650) closeFromServer();
        if(minecraft.player==null || !minecraft.player.isAlive() || safe.isRemoved()
                || minecraft.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(safe.getBlockPos()))>12.25) onClose();
    }
    @Override public void renderBackground(GuiGraphics graphics,int x,int y,float partialTick) { renderBlurredBackground(partialTick); }
    private void draw(GuiGraphics g) {
        long now=Util.getMillis();
        float fade=Math.min(1,(now-opened)/220f); fade=1-(1-fade)*(1-fade)*(1-fade);
        g.fill(0,0,width,height,(int)(0x58*fade)<<24|0x071019);
        float scale=Math.min(1.25f,Math.min((width-24)/500f,(height-20)/360f));
        g.pose().pushPose();
        g.pose().translate(width/2f,height/2f+(1-fade)*10,200); g.pose().scale(scale,scale,1);
        g.fill(-250,-180,250,180,(int)(0xCC*fade)<<24|0x1A2B3C);
        int blue=alpha(0xFF5B8DB8,fade);
        for(int glow=4;glow>=1;glow--) {
            int color=alpha(0x355B8DB8,fade/(glow*.9f));
            line(g,-247,-168,247,-168,glow*2,color); line(g,-247,171,247,171,glow*2,color);
            line(g,-250,-168,-250,168,glow*2,color); line(g,250,-168,250,168,glow*2,color);
        }
        line(g,-250,-168,-228,-168,1,blue); line(g,-228,-168,-220,-176,1,blue);
        line(g,-220,-176,220,-176,1,blue); line(g,220,-176,228,-168,1,blue); line(g,228,-168,250,-168,1,blue);
        line(g,-250,-168,-250,168,1,blue); line(g,250,-168,250,168,1,blue);
        line(g,-250,168,-228,168,1,blue); line(g,-228,168,-220,176,1,blue);
        line(g,-220,176,220,176,1,blue); line(g,220,176,228,168,1,blue); line(g,228,168,250,168,1,blue);
        // Discrete circuitry and scan lines rather than a flat menu background.
        for(int y=-113;y<155;y+=12) g.fill(-234,y,234,y+1,alpha(0x095B8DB8,fade));
        for(int x=-232;x<=-204;x+=6) line(g,x,148,x+8,158,1,alpha(0x775B8DB8,fade));
        badge(g,-232,-150,blue);
        label(g,Component.literal("破译中").withStyle(ChatFormatting.BOLD),0,-151,2.05f,alpha(0x335B8DB8,fade),true);
        label(g,Component.literal("破译中").withStyle(ChatFormatting.BOLD),0,-152,1.95f,alpha(0xFFFFFFFF,fade),true);
        label(g,Component.literal("战术行动 v0.1.4"),232,-155,.65f,alpha(0xFFADB8C5,fade),false);
        float cy=20, radius=112;
        float feedback=(float)Math.max(0,1-(serverNow()-safe.hackFeedbackTime)/430);
        int base=0xFF4A6B85;
        if(safe.hackFeedback!=0 && feedback>0) base=safe.hackFeedback>0?0xFF23E988:0xFFFF495C;
        arc(g,0,cy,radius,14,0,360,alpha(base,fade));
        arc(g,0,cy,radius+1,1,0,360,alpha(0xFF87ACCC,fade*.8f));
        var round=CrackRules.round(safe.hackSeed,safe.hackSuccesses);
        for(int glow=5;glow>=1;glow--) arc(g,0,cy,radius,14+glow*3,
                round.center()-round.width()/2,round.width(),alpha(0x1700FF00,fade));
        arc(g,0,cy,radius,14,round.center()-round.width()/2,round.width(),alpha(0xFF00FF00,fade));
        arc(g,0,cy,radius+7,1.5f,round.center()-round.width()/2,round.width(),alpha(0xFFE3FFEC,fade));
        for(int mark=0;mark<4;mark++) {
            double a=Math.toRadians(mark*90-90); float x=(float)Math.cos(a),y=(float)Math.sin(a);
            line(g,x*124,cy+y*124,x*96,cy+y*96,1,alpha(0xFFD6E7F0,fade));
        }
        double radians=Math.toRadians(pointerAngle()-90);
        float px=(float)Math.cos(radians),py=(float)Math.sin(radians);
        for(int glow=4;glow>=1;glow--) line(g,px*42,cy+py*42,px*124,cy+py*124,glow*2,alpha(0x2650C8FF,fade));
        line(g,0,cy,px*42,cy+py*42,1,alpha(0x775BBAE5,fade));
        line(g,px*42,cy+py*42,px*124,cy+py*124,2.4f,alpha(0xFFF0FAFF,fade));
        line(g,px*118,cy+py*118,px*128,cy+py*128,5,alpha(0xFFFFFFFF,fade));
        line(g,-18,cy-20,18,cy-20,1,alpha(0xAA9EDFF6,fade));
        line(g,-18,cy+22,18,cy+22,1,alpha(0xAA9EDFF6,fade));
        String text=safe.hackStatus.equals("failed")?"尝试次数 3/3":safe.hackStatus.equals("aborted")?"破译中断":
                "尝试次数 "+Math.min(3,safe.hackFailures+1)+"/3";
        label(g,Component.literal(text),0,cy-5,1.05f,alpha(0xFFFFFFFF,fade),true);
        label(g,Component.literal("成功 "+safe.hackSuccesses+"/3"),0,cy+43,.7f,alpha(0xFFC4D6E1,fade),true);
        label(g,Component.literal(notice.isBlank()?"空格 / 左键判定 · Esc 中断":notice),0,155,.7f,alpha(0xFFC1D4E2,fade),true);
        g.pose().popPose();
    }
    private static int alpha(int color,float amount) { return Math.round((color>>>24)*Math.max(0,Math.min(1,amount)))<<24|color&0xFFFFFF; }
    private static void label(GuiGraphics g,Component text,float x,float y,float scale,int color,boolean center) {
        g.pose().pushPose(); g.pose().translate(x,y,1); g.pose().scale(scale,scale,1);
        int offset=center?-Minecraft.getInstance().font.width(text)/2:-Minecraft.getInstance().font.width(text);
        g.drawString(Minecraft.getInstance().font,text,offset,0,color,true); g.pose().popPose();
    }
    private static void badge(GuiGraphics g,float x,float y,int color) {
        line(g,x-6,y-10,x+6,y-10,1.5f,color); line(g,x-6,y-10,x-6,y+3,1.5f,color);
        line(g,x+6,y-10,x+6,y+3,1.5f,color); line(g,x-6,y+3,x,y+9,1.5f,color); line(g,x,y+9,x+6,y+3,1.5f,color);
        line(g,x-4,y-3,x,y+1,1.5f,color); line(g,x,y+1,x+4,y-3,1.5f,color);
        line(g,x-4,y+1,x,y+5,1.5f,color); line(g,x,y+5,x+4,y+1,1.5f,color);
    }
    private static void line(GuiGraphics g,float x1,float y1,float x2,float y2,float width,int color) {
        double length=Math.hypot(x2-x1,y2-y1); if(length<.001) return;
        float nx=(float)(-(y2-y1)/length*width/2),ny=(float)((x2-x1)/length*width/2);
        quad(g,x1+nx,y1+ny,x2+nx,y2+ny,x2-nx,y2-ny,x1-nx,y1-ny,color);
    }
    private static void arc(GuiGraphics g,float cx,float cy,float radius,float thickness,double start,double sweep,int color) {
        int segments=Math.max(1,(int)Math.ceil(sweep/2));
        g.flush(); RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var matrix=g.pose().last().pose(); var buffer=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        for(int i=0;i<segments;i++) {
            double a=Math.toRadians(start+sweep*i/segments-90),b=Math.toRadians(start+sweep*(i+1)/segments-90);
            float inner=radius-thickness/2,outer=radius+thickness/2;
            buffer.addVertex(matrix,cx+(float)Math.cos(a)*inner,cy+(float)Math.sin(a)*inner,0).setColor(color);
            buffer.addVertex(matrix,cx+(float)Math.cos(b)*inner,cy+(float)Math.sin(b)*inner,0).setColor(color);
            buffer.addVertex(matrix,cx+(float)Math.cos(b)*outer,cy+(float)Math.sin(b)*outer,0).setColor(color);
            buffer.addVertex(matrix,cx+(float)Math.cos(a)*outer,cy+(float)Math.sin(a)*outer,0).setColor(color);
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow()); RenderSystem.disableBlend();
    }
    private static void quad(GuiGraphics g,float x1,float y1,float x2,float y2,float x3,float y3,float x4,float y4,int color) {
        g.flush(); RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var matrix=g.pose().last().pose(); var buffer=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        buffer.addVertex(matrix,x1,y1,0).setColor(color); buffer.addVertex(matrix,x2,y2,0).setColor(color);
        buffer.addVertex(matrix,x3,y3,0).setColor(color); buffer.addVertex(matrix,x4,y4,0).setColor(color);
        BufferUploader.drawWithShader(buffer.buildOrThrow()); RenderSystem.disableBlend();
    }
}
