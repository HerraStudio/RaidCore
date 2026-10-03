package dev.tactical;

import dev.tactical.loot.LootSession;
import java.util.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Server-side adapter: validate a complete plan, then commit every affected item once. */
public final class BagPlacement {
    private static final List<Integer> FIXED_SLOTS = List.of(0,1,2,3,4,5,6,7,8,36,37,38,39,40,42,43);

    private BagPlacement() {}

    public static int amount(int count,boolean half,int destinationArea,int destinationSlot) {
        int result=half?count/2+count%2:count;
        return destinationArea<0 && (destinationSlot<=3 || destinationSlot>=36)?Math.min(1,result):result;
    }
    public static int fixedRegion(int slot) { return -10-slot; }
    private static int slotForRegion(int region) { return -10-region; }

    public static boolean move(BagState state,Player player,LootSession loot,int source,int area,int x,int y,
                               boolean rotated,boolean half) {
        var original=LootSession.isLootId(source)?loot==null?ItemStack.EMPTY:loot.get(source):state.get(player,source);
        if(original.isEmpty() || !state.canRemove(source)) return false;
        if(area<0 && (x<0 || x>43 || x==source || !Rules.accepts(x,original))) return false;
        if(area>=0 && area!=0 && area!=1 && area!=LootSession.AREA) return false;
        if(area==LootSession.AREA && loot==null) return false;
        int amount=amount(original.getCount(),half,area,x);
        if(area==LootSession.AREA) amount=Math.min(amount,loot.maxCount(original));
        if(amount<1) return false;
        boolean entire=amount==original.getCount();
        var incoming=original.copyWithCount(amount);
        var stacks=new LinkedHashMap<Integer,ItemStack>();
        var items=new ArrayList<PlacementPlanner.Item>();
        var regions=new ArrayList<PlacementPlanner.Region>();
        for(int region=0;region<=1;region++) if(state.unlocked(region)) {
            var size=state.capacity(region);
            regions.add(new PlacementPlanner.Region(region,size.w(),size.h(),region==0,false));
        }
        for(int slot:FIXED_SLOTS) regions.add(new PlacementPlanner.Region(fixedRegion(slot),1,1,false,true));
        if(loot!=null) regions.add(new PlacementPlanner.Region(LootSession.AREA,6,loot.rows(),false,false,loot.slots()));

        for(int slot:FIXED_SLOTS) {
            var stack=state.get(player,slot);
            if(stack.isEmpty()) continue;
            add(items,stacks,state,loot,slot,fixedRegion(slot),new Grid.Rect(0,0,1,1),false,stack,
                    slot==source?incoming:stack,state.canRemove(slot));
        }
        for(var entry:state.entries) {
            if(entry.area==2 && entry.id!=source || entry.stack.isEmpty()) continue;
            add(items,stacks,state,loot,entry.id,entry.area,entry.rect(),entry.rotated,entry.stack,
                    entry.id==source?incoming:entry.stack,true);
        }
        var lootEntries=new HashMap<Integer,LootSession.LayoutEntry>();
        if(loot!=null) for(var entry:loot.layout()) {
            lootEntries.put(entry.id(),entry);
            if(entry.revealed()) add(items,stacks,state,loot,entry.id(),LootSession.AREA,entry.rectangle(),entry.rotated(),entry.stack(),
                    entry.id()==source?incoming:entry.stack(),true);
            else items.add(new PlacementPlanner.Item(entry.id(),LootSession.AREA,entry.rectangle(),entry.rectangle().w(),entry.rectangle().h(),
                    false,false,Set.of()));
        }
        int destination=area<0?fixedRegion(x):area;
        var model=new PlacementPlanner.Model(regions,items);
        var plan=PlacementPlanner.plan(model,source,destination,area<0?0:x,area<0?0:y,rotated,entire);
        if(plan==null) return false;

        var affected=new LinkedHashSet<Integer>();
        for(var placement:plan.placements()) affected.add(placement.item());
        var removedLoot=new LinkedHashSet<Integer>();
        for(int id:affected) if(LootSession.isLootId(id)) removedLoot.add(id);
        var destinations=new LinkedHashMap<Integer,ItemStack>();
        for(var placement:plan.placements()) if(placement.region()==LootSession.AREA)
            destinations.put(placement.item(),placement.item()==source?incoming:stacks.get(placement.item()));
        var remainder=original.copyWithCount(original.getCount()-amount);
        var deposits=new LinkedHashMap<Integer,LootSession.Deposit>();
        if(!remainder.isEmpty() && LootSession.isLootId(source)) {
            var entry=lootEntries.get(source);
            deposits.put(source,new LootSession.Deposit(remainder,entry.rectangle(),entry.rotated()));
        }
        Map<Integer,Integer> bindings=Map.of();
        if(loot!=null) {
            var available=loot.availableSlots(removedLoot).stream().filter(id->!deposits.containsKey(id)).toList();
            bindings=bindSlots(loot,destinations,available);
            if(bindings==null) return false;
            for(var placement:plan.placements()) if(placement.region()==LootSession.AREA) {
                var stack=destinations.get(placement.item()); var size=Rules.size(stack);
                var rectangle=new Grid.Rect(placement.x(),placement.y(),placement.rotated()?size.h():size.w(),placement.rotated()?size.w():size.h());
                deposits.put(bindings.get(placement.item()),new LootSession.Deposit(stack,rectangle,placement.rotated()));
            }
        }

        // No stack, capacity, slot permission, or rectangle can change between planning and
        // commit: BagMenu runs this synchronously on the server thread after revision checks.
        if(loot!=null && (!removedLoot.isEmpty() || !deposits.isEmpty()) && !loot.apply(removedLoot,deposits)) return false;
        var sourceEntry=state.entry(source);
        for(int id:affected) if(!LootSession.isLootId(id)) state.set(player,id,ItemStack.EMPTY);
        if(!remainder.isEmpty() && !LootSession.isLootId(source)) {
            if(sourceEntry==null) state.set(player,source,remainder);
            else state.entries.add(new BagState.Entry(sourceEntry.id,sourceEntry.area,sourceEntry.x,sourceEntry.y,sourceEntry.rotated,remainder));
        }
        for(var placement:plan.placements()) {
            if(placement.region()==LootSession.AREA) continue;
            var stack=(placement.item()==source?incoming:stacks.get(placement.item())).copy();
            if(placement.region()<0) state.set(player,slotForRegion(placement.region()),stack);
            else {
                // Partial moves retain the source ID for the remainder, so the split needs its own ID.
                int id=placement.item()>=100 && !(placement.item()==source && !entire)?placement.item():state.nextId++;
                state.entries.add(new BagState.Entry(id,placement.region(),placement.x(),placement.y(),placement.rotated(),stack));
            }
        }
        return true;
    }

    private static void add(List<PlacementPlanner.Item> items,Map<Integer,ItemStack> stacks,BagState state,LootSession loot,
                            int id,int region,Grid.Rect rectangle,boolean rotated,ItemStack original,ItemStack incoming,boolean movable) {
        var size=Rules.size(original); var allowed=new LinkedHashSet<Integer>();
        for(int slot:FIXED_SLOTS) if(Rules.accepts(slot,incoming)
                && (!(slot<=3 || slot>=36) || incoming.getCount()==1)) allowed.add(fixedRegion(slot));
        for(int grid=0;grid<=1;grid++) if(state.unlocked(grid) && !((id==42 || id==43) && grid==id-42)) allowed.add(grid);
        if(loot!=null && loot.acceptsAnySlot(incoming)) allowed.add(LootSession.AREA);
        items.add(new PlacementPlanner.Item(id,region,rectangle,size.w(),size.h(),rotated,movable,allowed));
        stacks.put(id,original.copy());
    }

    /** Physical container slots can have independent permissions; find a full matching first. */
    private static Map<Integer,Integer> bindSlots(LootSession loot,Map<Integer,ItemStack> items,List<Integer> available) {
        var result=new LinkedHashMap<Integer,Integer>();
        return bindSlots(loot,new ArrayList<>(items.keySet()),items,new HashSet<>(available),result)?result:null;
    }
    private static boolean bindSlots(LootSession loot,List<Integer> remaining,Map<Integer,ItemStack> items,
                                     Set<Integer> available,Map<Integer,Integer> result) {
        if(remaining.isEmpty()) return true;
        int next=-1; List<Integer> choices=null;
        for(int id:remaining) {
            var candidates=available.stream().filter(slot->loot.acceptsSlot(slot,items.get(id)))
                    .sorted(Comparator.comparingInt((Integer slot)->slot==id?0:1).thenComparingInt(slot->-slot)).toList();
            if(candidates.isEmpty()) return false;
            if(choices==null || candidates.size()<choices.size()) { next=id; choices=candidates; }
        }
        int index=remaining.indexOf(next); remaining.remove(index);
        for(int slot:choices) {
            available.remove(slot); result.put(next,slot);
            if(bindSlots(loot,remaining,items,available,result)) return true;
            available.add(slot); result.remove(next);
        }
        remaining.add(index,next); return false;
    }
}
