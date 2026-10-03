package dev.tactical.client;

import dev.tactical.Grid;
import dev.tactical.Rules;
import dev.tactical.loot.LootPacking;
import dev.tactical.loot.LootSession;
import java.util.*;
import net.minecraft.Util;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class LootView {
    public record Entry(int id,Grid.Rect rectangle,ItemStack stack,boolean revealed,long appeared,boolean rotated) {}
    public final Map<Integer,Entry> entries=new LinkedHashMap<>();
    public Component title=Component.empty();
    public int rows,slots,active=-1,duration;
    public int limit=64;
    private long serverStarted,animationStarted;

    public void accept(CompoundTag data,HolderLookup.Provider registries) {
        long now=Util.getMillis();
        title=Component.Serializer.fromJson(data.getString("title"),registries);
        if(title==null) title=Component.literal("容器");
        rows=data.getInt("rows"); slots=data.getInt("slots"); duration=data.getInt("duration");
        limit=data.contains("limit")?Math.max(1,data.getInt("limit")):64;
        int nextActive=data.getInt("active"); long nextStarted=data.getLong("started");
        if(nextActive!=active || nextStarted!=serverStarted) animationStarted=now;
        active=nextActive; serverStarted=nextStarted;
        var previous=new HashMap<>(entries); entries.clear();
        var list=data.getList("items",Tag.TAG_COMPOUND);
        for(int index=0;index<list.size();index++) {
            var tag=list.getCompound(index); int id=tag.getInt("id");
            boolean revealed=tag.getBoolean("revealed");
            var stack=revealed?ItemStack.parseOptional(registries,tag.getCompound("stack")):ItemStack.EMPTY;
            var old=previous.get(id);
            long appeared=old!=null && old.revealed() && ItemStack.isSameItemSameComponents(old.stack(),stack)?old.appeared():now;
            boolean rotated=tag.contains("rot")?tag.getBoolean("rot")
                    :revealed && (tag.getInt("w")!=Rules.size(stack).w() || tag.getInt("h")!=Rules.size(stack).h());
            entries.put(id,new Entry(id,new Grid.Rect(tag.getInt("x"),tag.getInt("y"),tag.getInt("w"),tag.getInt("h")),stack,revealed,appeared,rotated));
        }
    }
    public float progress() { return Math.min(1,(Util.getMillis()-animationStarted)/Math.max(1f,duration*50f)); }
    public ItemStack stack(int id) { var entry=entries.get(id); return entry==null?ItemStack.EMPTY:entry.stack(); }
    public int maxCount(ItemStack stack) { return Math.min(limit,stack.getMaxStackSize()); }
    public boolean canPlace(ItemStack stack,int source,int x,int y,boolean rotated,boolean entire) {
        var target=entries.values().stream().filter(entry->x>=entry.rectangle().x() && y>=entry.rectangle().y()
                && x<entry.rectangle().x()+entry.rectangle().w() && y<entry.rectangle().y()+entry.rectangle().h()).findFirst().orElse(null);
        if(target!=null && target.id()!=source)
            return target.revealed() && ItemStack.isSameItemSameComponents(target.stack(),stack) && target.stack().getCount()<maxCount(target.stack());
        int ignore=LootSession.isLootId(source) && entire?source:-1;
        var size=Rules.size(stack);
        return (ignore!=-1 || entries.size()<slots) && LootPacking.fits(rows,
                new Grid.Rect(x,y,rotated?size.h():size.w(),rotated?size.w():size.h()),
                entries.values().stream().filter(entry->entry.id()!=ignore).map(Entry::rectangle).toList());
    }
}
