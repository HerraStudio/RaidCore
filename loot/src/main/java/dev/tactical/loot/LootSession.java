package dev.tactical.loot;

import dev.tactical.Grid;
import dev.tactical.Rules;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

public final class LootSession {
    public static final int AREA=3;
    private static final class Entry {
        final int slot;
        Grid.Rect rectangle;
        ItemStack fingerprint;
        boolean revealed;
        boolean rotated;
        Entry(int slot,Grid.Rect rectangle,ItemStack fingerprint,boolean revealed) {
            this.slot=slot; this.rectangle=rectangle; this.fingerprint=fingerprint.copy(); this.revealed=revealed;
        }
    }
    private final ChestMenu original;
    private final Container container;
    private final Component title;
    private final Map<Integer,Entry> entries=new LinkedHashMap<>();
    private int active=-1,duration;
    private long started;
    private boolean closed;
    private int profileRevision=-1;
    public record LayoutEntry(int id,Grid.Rect rectangle,boolean rotated,boolean revealed,ItemStack stack) {}
    public record Deposit(ItemStack stack,Grid.Rect rectangle,boolean rotated) {}

    public LootSession(ChestMenu original,Component title) {
        this.original=original; this.container=original.getContainer(); this.title=title;
        refresh();
    }

    public static boolean isLootId(int id) { return id<=-2; }
    public static int id(int slot) { return -2-slot; }
    private static int slot(int id) { return -2-id; }
    public boolean stillValid(Player player) { return !closed && original.stillValid(player); }
    public boolean contains(Container candidate) {
        return candidate==container || container instanceof CompoundContainer compound && compound.contains(candidate);
    }
    public void close(Player player) {
        if(!closed) { closed=true; original.removed(player); }
    }
    private List<Grid.Rect> rectangles(int ignore) {
        return entries.values().stream().filter(entry->entry.slot!=ignore).map(entry->entry.rectangle).toList();
    }
    public int rows() { return LootPacking.rows(rectangles(-1)); }
    public int slots() { return container.getContainerSize(); }
    public int maxCount(ItemStack stack) { return container.getMaxStackSize(stack); }
    private Entry at(int x,int y) {
        return entries.values().stream().filter(entry->x>=entry.rectangle.x() && y>=entry.rectangle.y()
                && x<entry.rectangle.x()+entry.rectangle.w() && y<entry.rectangle.y()+entry.rectangle.h()).findFirst().orElse(null);
    }
    public void refresh() {
        int rulesRevision=dev.tactical.profile.ItemProfiles.current().revision();
        if(profileRevision!=rulesRevision) {
            var placed=new ArrayList<Grid.Rect>();
            var moved=new ArrayList<Entry>();
            for(var entry:entries.values()) {
                var size=Rules.size(container.getItem(entry.slot));
                var rectangle=new Grid.Rect(entry.rectangle.x(),entry.rectangle.y(),entry.rotated?size.h():size.w(),entry.rotated?size.w():size.h());
                entry.rectangle=rectangle;
                if(LootPacking.fits(Integer.MAX_VALUE,rectangle,placed)) placed.add(rectangle);
                else moved.add(entry);
            }
            for(var entry:moved) {
                entry.rectangle=LootPacking.firstFree(entry.rectangle.w(),entry.rectangle.h(),placed); placed.add(entry.rectangle);
            }
            profileRevision=rulesRevision;
        }
        entries.values().removeIf(entry-> {
            var current=container.getItem(entry.slot);
            boolean replaced=current.isEmpty() || !ItemStack.isSameItemSameComponents(entry.fingerprint,current);
            if(replaced && entry.slot==active) active=-1;
            return replaced;
        });
        for(int index=0;index<container.getContainerSize();index++) {
            var current=container.getItem(index);
            if(current.isEmpty()) continue;
            var entry=entries.get(index);
            if(entry==null) {
                var size=Rules.size(current);
                var rectangle=LootPacking.firstFree(size.w(),size.h(),rectangles(-1));
                entries.put(index,new Entry(index,rectangle,current,false));
            } else entry.fingerprint=current.copy();
        }
        if(!entries.containsKey(active)) active=-1;
    }
    public void tick(long gameTime) {
        refresh();
        var searching=entries.get(active);
        if(searching!=null && gameTime-started>=duration) { searching.revealed=true; active=-1; }
        if(active<0) {
            var next=entries.values().stream().filter(entry->!entry.revealed)
                    .min(Comparator.comparingInt((Entry entry)->entry.rectangle.y()).thenComparingInt(entry->entry.rectangle.x())).orElse(null);
            if(next!=null) {
                active=next.slot; started=gameTime;
                duration=18+2*next.rectangle.w()*next.rectangle.h();
            }
        }
    }
    public boolean revealed(int id) {
        var entry=isLootId(id)?entries.get(slot(id)):null;
        return entry!=null && entry.revealed && ItemStack.isSameItemSameComponents(entry.fingerprint,container.getItem(entry.slot));
    }
    public ItemStack get(int id) { return revealed(id)?container.getItem(slot(id)):ItemStack.EMPTY; }
    public boolean hasMergeRoom(int id) {
        var stack=get(id);
        return !stack.isEmpty() && stack.getCount()<container.getMaxStackSize(stack);
    }
    /** Unknown contents retain their footprint but never expose their stack. */
    public List<LayoutEntry> layout() {
        return entries.values().stream().map(entry->new LayoutEntry(id(entry.slot),entry.rectangle,entry.rotated,
                entry.revealed,entry.revealed?container.getItem(entry.slot).copy():ItemStack.EMPTY)).toList();
    }
    public boolean acceptsSlot(int id,ItemStack stack) {
        int index=isLootId(id)?slot(id):-1;
        return index>=0 && index<container.getContainerSize() && !stack.isEmpty()
                && stack.getCount()<=container.getMaxStackSize(stack) && container.canPlaceItem(index,stack);
    }
    public boolean acceptsAnySlot(ItemStack stack) {
        for(int index=0;index<container.getContainerSize();index++) if(acceptsSlot(id(index),stack)) return true;
        return false;
    }
    public List<Integer> availableSlots(Set<Integer> removed) {
        var result=new ArrayList<Integer>();
        for(int index=0;index<container.getContainerSize();index++) {
            int id=id(index);
            if(container.getItem(index).isEmpty() || removed.contains(id) && revealed(id)) result.add(id);
        }
        return result;
    }
    /** All contents and layout metadata are installed before the single final refresh. */
    public boolean apply(Set<Integer> removed,Map<Integer,Deposit> deposits) {
        var available=new HashSet<>(availableSlots(removed));
        for(int id:removed) if(!revealed(id)) return false;
        for(var deposit:deposits.entrySet()) {
            if(!available.contains(deposit.getKey()) || !acceptsSlot(deposit.getKey(),deposit.getValue().stack())) return false;
        }
        for(int id:removed) {
            int index=slot(id); container.setItem(index,ItemStack.EMPTY); entries.remove(index);
            if(active==index) active=-1;
        }
        for(var deposit:deposits.entrySet()) {
            int index=slot(deposit.getKey()); var value=deposit.getValue();
            var stack=value.stack().copy(); container.setItem(index,stack);
            var entry=new Entry(index,value.rectangle(),stack,true); entry.rotated=value.rotated(); entries.put(index,entry);
        }
        changed(); return true;
    }
    public void set(int id,ItemStack stack) {
        if(!isLootId(id) || slot(id)<0 || slot(id)>=container.getContainerSize()) return;
        container.setItem(slot(id),stack); changed();
    }
    public void changed() { container.setChanged(); refresh(); }
    private int emptySlot(ItemStack stack) {
        for(int index=0;index<container.getContainerSize();index++)
            if(container.getItem(index).isEmpty() && container.canPlaceItem(index,stack)) return index;
        return -1;
    }
    public boolean canPlace(ItemStack stack,int source,int x,int y,boolean rotated,boolean entire) {
        var target=at(x,y);
        if(target!=null && id(target.slot)!=source) {
            var current=container.getItem(target.slot);
            return target.revealed && container.canPlaceItem(target.slot,stack) && ItemStack.isSameItemSameComponents(current,stack)
                    && current.getCount()<container.getMaxStackSize(current);
        }
        int ignore=isLootId(source) && entire?slot(source):-1;
        var size=Rules.size(stack);
        var rectangle=new Grid.Rect(x,y,rotated?size.h():size.w(),rotated?size.w():size.h());
        return (ignore>=0 || emptySlot(stack)>=0) && LootPacking.fits(rows(),rectangle,rectangles(ignore));
    }
    public int place(ItemStack stack,int source,int x,int y,boolean rotated,boolean entire) {
        if(stack.isEmpty() || !canPlace(stack,source,x,y,rotated,entire)) return 0;
        var target=at(x,y);
        if(target!=null && id(target.slot)!=source) {
            var current=container.getItem(target.slot);
            int amount=Math.min(stack.getCount(),container.getMaxStackSize(current)-current.getCount());
            current.grow(amount); changed(); return amount;
        }
        if(isLootId(source) && entire) {
            var entry=entries.get(slot(source));
            var size=Rules.size(stack);
            entry.rectangle=new Grid.Rect(x,y,rotated?size.h():size.w(),rotated?size.w():size.h());
            entry.rotated=rotated;
            return 0;
        }
        int index=emptySlot(stack);
        if(index<0) return 0;
        int amount=Math.min(stack.getCount(),container.getMaxStackSize(stack));
        var deposited=stack.copyWithCount(amount);
        var size=Rules.size(deposited);
        container.setItem(index,deposited);
        var entry=new Entry(index,new Grid.Rect(x,y,rotated?size.h():size.w(),rotated?size.w():size.h()),deposited,true);
        entry.rotated=rotated; entries.put(index,entry);
        changed(); return amount;
    }
    public void insert(ItemStack remainder) {
        for(var entry:entries.values()) {
            if(remainder.isEmpty()) break;
            var target=container.getItem(entry.slot);
            if(entry.revealed && container.canPlaceItem(entry.slot,remainder) && ItemStack.isSameItemSameComponents(target,remainder)) {
                int amount=Math.min(remainder.getCount(),Math.max(0,container.getMaxStackSize(target)-target.getCount()));
                target.grow(amount); remainder.shrink(amount);
            }
        }
        while(!remainder.isEmpty()) {
            int index=emptySlot(remainder);
            if(index<0) break;
            var part=remainder.copyWithCount(Math.min(remainder.getCount(),container.getMaxStackSize(remainder)));
            var size=Rules.size(part);
            var rectangle=LootPacking.firstFree(size.w(),size.h(),rectangles(-1));
            container.setItem(index,part); entries.put(index,new Entry(index,rectangle,part,true));
            remainder.shrink(part.getCount());
        }
        changed();
    }
    public CompoundTag snapshot(HolderLookup.Provider registries) {
        var data=new CompoundTag();
        data.putString("title",Component.Serializer.toJson(title,registries));
        data.putInt("rows",rows()); data.putInt("slots",container.getContainerSize());
        data.putInt("limit",container.getMaxStackSize());
        data.putInt("active",active<0?-1:id(active)); data.putLong("started",started); data.putInt("duration",duration);
        var items=new ListTag();
        for(var entry:entries.values()) {
            var item=new CompoundTag(); item.putInt("id",id(entry.slot));
            item.putInt("x",entry.rectangle.x()); item.putInt("y",entry.rectangle.y());
            item.putInt("w",entry.rectangle.w()); item.putInt("h",entry.rectangle.h()); item.putBoolean("revealed",entry.revealed);
            item.putBoolean("rot",entry.rotated);
            if(entry.revealed) item.put("stack",container.getItem(entry.slot).saveOptional(registries));
            items.add(item);
        }
        data.put("items",items); return data;
    }
}
