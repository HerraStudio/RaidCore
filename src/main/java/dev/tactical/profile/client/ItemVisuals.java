package dev.tactical.profile.client;

import com.sgr792.gwo.client.GltfGunRenderer;
import com.sgr792.gwo.client.hud.HudWeaponIconRenderer;
import com.sgr792.gwo.content.WeaponContentRegistry;
import com.sgr792.gwo.item.GunData;
import dev.tactical.profile.Rarity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

public final class ItemVisuals {
    /** Inside-only glow preserves continuous grid edges and never bleeds into adjacent items. */
    public static void surface(GuiGraphics g,Rarity rarity,int x,int y,int w,int h,boolean active) {
        g.fillGradient(x+1,y+1,x+w-1,y+h-1,rarity.color(active?36:10),rarity.color(active?90:46));
        for(int inset=1;inset<=3 && w>inset*2 && h>inset*2;inset++)
            border(g,x+inset,y+inset,w-inset*2,h-inset*2,rarity.color((active?40:20)/inset));
        border(g,x,y,w,h,rarity.color(active?255:195));
        if(active && w>4 && h>4) border(g,x+1,y+1,w-2,h-2,rarity.color(175));
        g.fill(x+1,y+h-3,x+w-1,y+h-1,rarity.color(active?185:105));
    }
    public static void border(GuiGraphics g,int x,int y,int w,int h,int color) {
        g.fill(x,y,x+w,y+1,color); g.fill(x,y+h-1,x+w,y+h,color);
        g.fill(x,y,x+1,y+h,color); g.fill(x+w-1,y,x+w,y+h,color);
    }
    /** Matches inventory rendering, including GWO's actual wide weapon icon. */
    public static void icon(GuiGraphics g,ItemStack stack,int x,int y,int w,int h,boolean rotated) {
        if(stack.isEmpty() || w<=0 || h<=0) return;
        if(rotated) {
            g.pose().pushPose(); g.pose().translate(x+w,y,0);
            g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90));
            icon(g,stack,0,0,h,w,false); g.pose().popPose(); return;
        }
        var id=GunData.contentId(stack); var def=id==null?null:WeaponContentRegistry.get(id);
        if(def!=null && IClientItemExtensions.of(stack).getCustomRenderer() instanceof GltfGunRenderer renderer
                && HudWeaponIconRenderer.render(g,stack,def,renderer,x,y,w,h)) return;
        float scale=Math.min(w,h)/16f;
        g.pose().pushPose(); g.pose().translate(x+(w-16*scale)/2,y+(h-16*scale)/2,0); g.pose().scale(scale,scale,1);
        g.renderItem(stack,0,0); g.pose().popPose();
    }
    private ItemVisuals() {}
}
