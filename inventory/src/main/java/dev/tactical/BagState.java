package dev.tactical;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.HolderLookup;

public final class BagState {
    private static final String KEY = "tactical_inventory:storage";
    private static final Map<Player, BagState> CACHE = new WeakHashMap<>();
    public static final class Entry {
        public int id, area, x, y;
        public boolean rotated;
        public ItemStack stack;
        public Entry(int id, int area, int x, int y, boolean rotated, ItemStack stack) {
            this.id=id; this.area=area; this.x=x; this.y=y; this.rotated=rotated; this.stack=stack;
        }
        public Grid.Rect rect() {
            var size = Rules.size(stack);
            return new Grid.Rect(x, y, rotated ? size.h() : size.w(), rotated ? size.w() : size.h());
        }
    }
    public final List<Entry> entries = new ArrayList<>();
    public final ItemStack[] gear = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
    public int nextId = 100;
    private int profileRevision;
    public static BagState of(Player player) {
        return CACHE.computeIfAbsent(player, p -> read(p.getPersistentData().getCompound(KEY), p.registryAccess()));
    }
    public void save(Player p) { p.getPersistentData().put(KEY, write(p.registryAccess())); p.getInventory().setChanged(); }
    public static void copy(Player old, Player fresh) {
        BagState source = of(old);
        var result = read(source.write(old.registryAccess()), fresh.registryAccess());
        CACHE.put(fresh, result); result.save(fresh);
    }
    public CompoundTag write(HolderLookup.Provider registries) {
        var data = new CompoundTag(); var list = new ListTag(); var gearList = new ListTag();
        for (var entry : entries) {
            if (entry.stack.isEmpty()) continue;
            var tag = new CompoundTag(); tag.putInt("id", entry.id); tag.putInt("area", entry.area);
            tag.putInt("x", entry.x); tag.putInt("y", entry.y); tag.putBoolean("rot", entry.rotated);
            tag.put("stack", entry.stack.saveOptional(registries)); list.add(tag);
        }
        for (var stack : gear) gearList.add(stack.saveOptional(registries));
        data.put("items", list); data.put("gear", gearList); data.putInt("next", nextId);
        data.putInt("profiles",profileRevision); return data;
    }
    public static BagState read(CompoundTag data, HolderLookup.Provider registries) {
        var state = new BagState(); var list = data.getList("items", Tag.TAG_COMPOUND);
        for (int i=0; i<list.size(); i++) {
            var tag=list.getCompound(i); var stack=ItemStack.parseOptional(registries, tag.getCompound("stack"));
            if (!stack.isEmpty()) state.entries.add(new Entry(tag.getInt("id"),tag.getInt("area"),tag.getInt("x"),tag.getInt("y"),tag.getBoolean("rot"),stack));
        }
        var gear = data.getList("gear", Tag.TAG_COMPOUND);
        for (int i=0; i<Math.min(3,gear.size()); i++) state.gear[i]=ItemStack.parseOptional(registries,gear.getCompound(i));
        state.nextId=Math.max(100,data.getInt("next")); state.profileRevision=data.getInt("profiles"); return state;
    }
    public Entry entry(int id) { return entries.stream().filter(e -> e.id==id).findFirst().orElse(null); }
    public ItemStack get(Player p, int id) {
        if (id>=0 && id<=40) return p.getInventory().getItem(id);
        if (id>=41 && id<=43) return gear[id-41];
        var entry=entry(id); return entry==null ? ItemStack.EMPTY : entry.stack;
    }
    public void set(Player p,int id,ItemStack stack) {
        if (id>=0 && id<=40) p.getInventory().setItem(id,stack);
        else if (id>=41 && id<=43) gear[id-41]=stack;
        else { var e=entry(id); if(e!=null) { if(stack.isEmpty()) entries.remove(e); else e.stack=stack; } }
    }
    public boolean unlocked(int area) { return area==0 ? !gear[1].isEmpty() : area==1 && !gear[2].isEmpty(); }
    public Rules.Size capacity(int area) {
        return unlocked(area)?Rules.capacity(gear[area+1],area):new Rules.Size(0,0);
    }
    public boolean canRemove(int id) { return (id!=42 && id!=43) || entries.stream().noneMatch(e -> e.area==id-42); }
    public boolean swapGear(Player player,int source,int destination,boolean commit) {
        if(destination<42 || destination>43 || source==destination || !canRemove(source)) return false;
        var incoming=get(player,source); var outgoing=get(player,destination);
        if(incoming.isEmpty() || incoming.getCount()!=1 || outgoing.isEmpty() || !Rules.accepts(destination,incoming)) return false;
        var entry=entry(source);
        if(entry==null && !Rules.accepts(source,outgoing)) return false;
        gear[destination-41]=incoming;
        if(entry!=null) entry.stack=outgoing;
        boolean fits=true;
        for(var item:entries) if(item.area<2 && !fits(item.area,item.x,item.y,item.rotated,item.stack,item.id)) { fits=false; break; }
        if(!fits || !commit) {
            gear[destination-41]=outgoing; if(entry!=null) entry.stack=incoming;
        } else if(entry==null) set(player,source,outgoing);
        return fits;
    }
    public boolean fits(int area,int x,int y,boolean rot,ItemStack stack,int ignore) {
        if (!unlocked(area)) return false;
        var size=Rules.size(stack);
        var capacity=capacity(area);
        return Grid.fits(area,capacity.w(),capacity.h(),new Grid.Rect(x,y,rot?size.h():size.w(),rot?size.w():size.h()),
                entries.stream().filter(e->e.area==area && e.id!=ignore).map(Entry::rect).toList());
    }
    public boolean place(ItemStack stack) {
        for(int area=0;area<2;area++) if(unlocked(area)) {
            for(int rot=0;rot<2;rot++) for(int y=0;y<capacity(area).h();y++) for(int x=0;x<capacity(area).w();x++)
                if(fits(area,x,y,rot==1,stack,-1)) {
                    entries.add(new Entry(nextId++,area,x,y,rot==1,stack.copy())); stack.setCount(0); return true;
                }
        }
        return false;
    }
    /** Consumes only accepted items; never uses vanilla creative-overflow deletion. */
    public boolean insert(Player p,ItemStack stack) {
        if(stack.isEmpty()) return false;
        int before=stack.getCount();
        for(int i=0;i<9;i++) if(Rules.accepts(i,stack)) merge(p.getInventory().getItem(i),stack);
        for(var e:entries) if(e.area<2) merge(e.stack,stack);
        for(int i=0;i<9 && !stack.isEmpty();i++) if(Rules.accepts(i,stack) && get(p,i).isEmpty()) {
            set(p,i,stack.split(Math.min(stack.getCount(), stack.getMaxStackSize())));
        }
        for(int i=41;i<=43 && !stack.isEmpty();i++) if(Rules.accepts(i,stack) && get(p,i).isEmpty()) set(p,i,stack.split(1));
        while(!stack.isEmpty()) {
            var part=stack.copyWithCount(Math.min(stack.getCount(),stack.getMaxStackSize())); int amount=part.getCount();
            if(!place(part)) break; stack.shrink(amount);
        }
        if(before!=stack.getCount()) save(p);
        return before!=stack.getCount();
    }
    public static void merge(ItemStack into, ItemStack from) {
        if(!into.isEmpty() && !from.isEmpty() && ItemStack.isSameItemSameComponents(into,from)) {
            int n=Math.min(from.getCount(),Math.max(0,into.getMaxStackSize()-into.getCount()));
            into.grow(n); from.shrink(n);
        }
    }
    public void normalize(Player p) {
        boolean changed=false;
        int current=dev.tactical.profile.ItemProfiles.current().revision();
        if(profileRevision!=current) {
            // Keep legal roots, then re-home resized rectangles without deleting or dropping stacks.
            var invalid=new ArrayList<Entry>();
            var reserved=new HashMap<Integer,List<Grid.Rect>>();
            for(var e:entries) if(e.area<2) {
                var capacity=capacity(e.area); var taken=reserved.computeIfAbsent(e.area,k->new ArrayList<>());
                if(!Grid.fits(e.area,capacity.w(),capacity.h(),e.rect(),taken)) invalid.add(e);
                else taken.add(e.rect());
            }
            entries.removeAll(invalid);
            for(var e:invalid) {
                var remainder=e.stack.copy();
                if(!place(remainder)) { e.area=2; e.x=0; e.y=0; e.rotated=false; entries.add(e); }
            }
            profileRevision=current; changed=true;
        }
        if(!gear[0].isEmpty()) {
            var retired=gear[0]; gear[0]=ItemStack.EMPTY;
            insert(p,retired);
            if(!retired.isEmpty()) entries.add(new Entry(nextId++,2,0,0,false,retired));
            changed=true;
        }
        for(int i=0;i<36;i++) {
            var stack=p.getInventory().getItem(i);
            if(!stack.isEmpty() && !Rules.accepts(i,stack)) {
                p.getInventory().setItem(i,ItemStack.EMPTY);
                insert(p,stack);
                if(!stack.isEmpty()) entries.add(new Entry(nextId++,2,0,0,false,stack));
                changed=true;
            }
        }
        if(changed) save(p);
    }
}
