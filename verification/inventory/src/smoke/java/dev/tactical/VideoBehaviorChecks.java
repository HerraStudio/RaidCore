package dev.tactical;

import com.sgr792.gwo.GwoMod;
import com.sgr792.gwo.content.WeaponContentRegistry;
import com.sgr792.gwo.item.GunData;
import dev.tactical.loot.LootSession;
import java.util.HashSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.LoggerFactory;

/** Native server fixtures use BagMenu.action, the same validation/commit path as real packets. */
public final class VideoBehaviorChecks {
    private record Fixture(ServerPlayer player, BagState state, SimpleContainer container, LootSession loot, BagMenu menu) {}
    private VideoBehaviorChecks() {}

    public static void verify(ServerPlayer player) {
        multiItemReplacement(player);
        atomicRejection(player);
        stacksAndFixedSlots(player);
        fullMatchingStacks(player);
        weaponFixedSlots(player);
        loadedEquipment(player);
        lootReplacement(player);
        lootStackLimit(player);
        hiddenAndPhysicalSlots(player);
        LoggerFactory.getLogger("VideoBehaviorChecks").info("BAG_VIDEO_SERVER_PASS: native GWO multi-item replacement with displaced auto-rotation, atomic no-fit/half rejection, fixed/grid bidirectional permissions, loaded capacity protection, component-identical merge, loot/bag and same-loot replacement, reveal preservation, physical slot limit and hidden rejection, standard 99-arrow deposit cap to 64 with 35-source remainder, count/ammo/component preservation");
    }

    private static BagState reset(ServerPlayer player) {
        player.getInventory().clearContent();
        var state=BagState.of(player); state.entries.clear(); state.nextId=100;
        for(int i=0;i<state.gear.length;i++) state.gear[i]=ItemStack.EMPTY;
        state.gear[2]=new ItemStack(Tactical.BACKPACK.get()); state.save(player);
        return state;
    }
    private static ItemStack named(ItemStack stack,String name) {
        stack.set(DataComponents.CUSTOM_NAME,Component.literal(name)); return stack;
    }
    private static ItemStack gun() {
        var content=WeaponContentRegistry.ids().stream().filter(id->id.getPath().contains("ak103")).findFirst().orElseThrow();
        var stack=GwoMod.weaponStack(content); GunData.initialize(stack,content,WeaponContentRegistry.get(content)); GunData.setAmmo(stack,7);
        require(Rules.size(stack).equals(new Rules.Size(5,2)),"Native video fixture is not a 5x2 GWO weapon"); return stack;
    }
    private static BagState.Entry add(BagState state,int x,int y,ItemStack stack) {
        var entry=new BagState.Entry(state.nextId++,1,x,y,false,stack.copy()); state.entries.add(entry); return entry;
    }
    private static BagMenu menu(ServerPlayer player) { var menu=new BagMenu(93,player.getInventory()); menu.broadcastChanges(); return menu; }
    private static void move(BagMenu menu,int source,int area,int x,int y,boolean rotation,boolean half) {
        menu.broadcastChanges(); menu.action(new Packets.Action(menu.containerId,menu.revision,1,source,area,x,y,rotation,half));
    }
    private static void rejected(Fixture fixture,int source,int area,int x,int y,boolean rotated,boolean half,String context) {
        fixture.menu.broadcastChanges(); var before=snapshot(fixture);
        move(fixture.menu,source,area,x,y,rotated,half);
        require(before.equals(snapshot(fixture)),context+" changed item data, layout, counts, or search visibility");
    }
    private static Fixture plain(ServerPlayer player,BagState state) { return new Fixture(player,state,null,null,menu(player)); }
    private static CompoundTag snapshot(Fixture fixture) {
        var result=fixture.menu.snapshot();
        if(fixture.container!=null) {
            var physical=new ListTag();
            for(int slot=0;slot<fixture.container.getContainerSize();slot++) physical.add(fixture.container.getItem(slot).saveOptional(fixture.player.registryAccess()));
            result.put("physical",physical);
        }
        return result;
    }
    private static void validGrid(BagState state) {
        var ids=new HashSet<Integer>();
        for(var entry:state.entries) {
            require(ids.add(entry.id),"Replacement duplicated a grid ID");
            if(entry.area<2) require(state.fits(entry.area,entry.x,entry.y,entry.rotated,entry.stack,entry.id),"Replacement left overlapping or out-of-bounds items");
        }
    }
    private static void same(ItemStack actual,ItemStack expected,String context) {
        require(actual.getCount()==expected.getCount() && ItemStack.isSameItemSameComponents(actual,expected),context+" lost count/components/ammo");
    }
    private static BagState.Entry find(BagState state,ItemStack stack) {
        return state.entries.stream().filter(entry->ItemStack.isSameItemSameComponents(entry.stack,stack)).findFirst().orElseThrow();
    }
    private static void multiItemReplacement(ServerPlayer player) {
        var state=reset(player); var weapon=gun();
        var source=add(state,0,0,weapon); var armor=named(new ItemStack(Items.IRON_CHESTPLATE),"Video board replacement");
        var board=add(state,4,2,armor); var ration=named(new ItemStack(Items.BREAD,13),"Video phone replacement");
        var bread=add(state,1,2,ration); var gem=named(new ItemStack(Items.DIAMOND,9),"Video card replacement"); var diamond=add(state,2,3,gem);
        move(menu(player),source.id,1,1,2,false,false);
        require(state.entries.size()==4 && state.entry(source.id).rect().equals(new Grid.Rect(1,2,5,2)),"Native multi-item incoming root/size changed");
        same(state.entry(source.id).stack,weapon,"Incoming GWO gun"); same(state.entry(board.id).stack,armor,"Displaced armor");
        same(state.entry(bread.id).stack,ration,"Displaced bread"); same(state.entry(diamond.id).stack,gem,"Displaced diamond");
        require(state.entry(board.id).rotated,"Displaced 2x3 armor did not rotate to fit the source's 5x2 region"); validGrid(state);
    }
    private static void atomicRejection(ServerPlayer player) {
        var state=reset(player); var source=add(state,0,0,gun()); add(state,3,2,new ItemStack(Tactical.BACKPACK.get()));
        for(int y=0;y<6;y++) for(int x=0;x<6;x++) {
            final int cx=x,cy=y;
            if(x==1 && y==2 || state.entries.stream().anyMatch(entry->cx>=entry.x && cy>=entry.y && cx<entry.x+entry.rect().w() && cy<entry.y+entry.rect().h())) continue;
            add(state,x,y,named(new ItemStack(Items.DIAMOND),"Pinned "+x+","+y));
        }
        var fixture=plain(player,state);
        rejected(fixture,source.id,1,1,2,false,false,"Unpackable 3x3 replacement"); validGrid(state);
        state=reset(player); var first=add(state,0,0,named(new ItemStack(Items.BREAD,13),"Half source"));
        add(state,2,0,named(new ItemStack(Items.DIAMOND,9),"Half target")); fixture=plain(player,state);
        rejected(fixture,first.id,1,2,0,false,true,"Actual partial-stack replacement");
    }
    private static void stacksAndFixedSlots(ServerPlayer player) {
        var state=reset(player); var bread=named(new ItemStack(Items.BREAD,13),"Same component ration");
        var source=add(state,0,0,bread); var target=add(state,2,0,bread.copyWithCount(60)); var menu=menu(player);
        move(menu,source.id,1,2,0,false,false);
        same(state.entry(source.id).stack,bread.copyWithCount(9),"Merge remainder"); same(state.entry(target.id).stack,bread.copyWithCount(64),"Merge target");
        var gem=named(new ItemStack(Items.DIAMOND,9),"Pocket target"); player.getInventory().setItem(4,gem.copy());
        move(menu,source.id,-1,4,0,false,false); same(player.getInventory().getItem(4),bread.copyWithCount(9),"Grid to pocket replacement");
        same(find(state,gem).stack,gem,"Pocket target return to source region");
        var replacement=find(state,gem); move(menu,4,1,replacement.x,replacement.y,false,false);
        same(player.getInventory().getItem(4),gem,"Pocket to grid replacement"); validGrid(state);
    }
    private static void weaponFixedSlots(ServerPlayer player) {
        var state=reset(player); var first=named(gun(),"Equipped GWO source"); var second=named(gun(),"Stored GWO target");
        player.getInventory().setItem(0,first.copy()); var stored=add(state,0,0,second); var fixture=plain(player,state);
        move(fixture.menu,0,1,0,0,false,false); same(player.getInventory().getItem(0),second,"Gun target returned to weapon slot"); same(find(state,first).stack,first,"Equipped gun entered grid");
        move(fixture.menu,find(state,first).id,-1,0,0,false,false); same(player.getInventory().getItem(0),first,"Gun grid to weapon replacement"); same(find(state,second).stack,second,"Gun weapon to grid replacement");
        rejected(fixture,find(state,second).id,-1,2,0,false,false,"Rifle entered sidearm slot");
        state=reset(player); setCapacity(state.gear[2],5,2); stored=add(state,0,0,second); player.getInventory().setItem(4,new ItemStack(Items.BREAD,13)); fixture=plain(player,state);
        rejected(fixture,4,1,0,0,false,false,"Replacement with no legal return to the pocket or full 5x2 bag"); validGrid(state);
    }
    private static void fullMatchingStacks(ServerPlayer player) {
        var state=reset(player); var full=named(new ItemStack(Items.BREAD,64),"Full identical ration");
        var source=add(state,0,0,full); var target=add(state,2,0,full); var fixture=plain(player,state);
        rejected(fixture,source.id,1,2,0,false,true,"Half-stack replaced a full identical stack");
        move(fixture.menu,source.id,1,2,0,false,false);
        require(state.entry(source.id).x==2 && state.entry(target.id).x==0,"Full identical stack replacement was blocked by zero merge room");
        same(state.entry(source.id).stack,full,"Full identical source"); same(state.entry(target.id).stack,full,"Full identical target"); validGrid(state);
        state=reset(player); var weapon=gun(); source=add(state,0,0,weapon); target=add(state,0,4,weapon); fixture=plain(player,state);
        move(fixture.menu,source.id,1,0,4,false,false);
        require(state.entry(source.id).y==4 && state.entry(target.id).y==0,"Two identical max-one GWO guns did not replace their roots");
        same(state.entry(source.id).stack,weapon,"Identical GWO first gun"); same(state.entry(target.id).stack,weapon,"Identical GWO second gun"); validGrid(state);
    }
    private static void setCapacity(ItemStack gear,int columns,int rows) {
        var data=new CompoundTag(); var size=new CompoundTag(); size.putInt("columns",columns); size.putInt("rows",rows); data.put("tactical_inventory",size);
        gear.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
    }
    private static void loadedEquipment(ServerPlayer player) {
        var state=reset(player); var edge=add(state,5,5,new ItemStack(Items.DIAMOND));
        var smaller=new ItemStack(Tactical.BACKPACK.get()); setCapacity(smaller,4,3);
        var spare=new BagState.Entry(state.nextId++,2,0,0,false,smaller); state.entries.add(spare); var fixture=plain(player,state);
        rejected(fixture,spare.id,-1,43,0,false,false,"Undersized loaded backpack replacement");
        rejected(fixture,43,-1,40,0,false,false,"Loaded backpack removal");
        edge.x=0; edge.y=0;
        rejected(fixture,spare.id,-1,43,0,false,true,"Half-mode bypassed loaded equipment's specialized replacement rule");
        move(fixture.menu,spare.id,-1,43,0,false,false);
        require(state.capacity(1).equals(new Rules.Size(4,3)),"Legal loaded equipment capacity replacement failed"); validGrid(state);
        state=reset(player); add(state,5,5,new ItemStack(Items.DIAMOND));
        var identical=new BagState.Entry(state.nextId++,2,0,0,false,state.gear[2].copy()); state.entries.add(identical); fixture=plain(player,state);
        require(!state.canRemove(43) && state.swapGear(player,identical.id,43,false),"Identical loaded gear fixture did not require the specialized capacity-safe swap branch");
        move(fixture.menu,identical.id,-1,43,0,false,false);
        require(state.entries.size()==2 && state.entry(identical.id)!=null && state.capacity(1).equals(new Rules.Size(6,6)),"Identical loaded backpack replacement lost spare gear or contents"); validGrid(state);
    }
    private static Fixture loot(ServerPlayer player,BagState state,ItemStack... stacks) {
        var container=new SimpleContainer(27); for(int i=0;i<stacks.length;i++) container.setItem(i,stacks[i].copy());
        var original=ChestMenu.threeRows(94,player.getInventory(),container); var loot=new LootSession(original,Component.literal("Video replacement checks"));
        var menu=new BagMenu(94,player.getInventory(),loot); menu.broadcastChanges(); return new Fixture(player,state,container,loot,menu);
    }
    private static void reveal(Fixture fixture) {
        long now=fixture.player.level().getGameTime(); for(int i=0;i<=27;i++) fixture.loot.tick(now+i*100L); fixture.menu.broadcastChanges();
    }
    private static LootSession.LayoutEntry lootFind(Fixture fixture,ItemStack expected) {
        return fixture.loot.layout().stream().filter(entry->entry.revealed() && ItemStack.isSameItemSameComponents(entry.stack(),expected)).findFirst().orElseThrow();
    }
    private static void lootValid(Fixture fixture) {
        var entries=fixture.loot.layout(); require(entries.size()<=27,"Replacement exceeded physical loot slots");
        for(var entry:entries) {
            require(entry.revealed() && fixture.loot.revealed(entry.id()),"Replacement reset search/reveal status");
            require(Grid.fits(1,6,fixture.loot.rows(),entry.rectangle(),entries.stream().filter(other->other.id()!=entry.id()).map(LootSession.LayoutEntry::rectangle).toList()),"Loot replacement overlaps or escapes bounds");
        }
        validGrid(fixture.state);
    }
    private static void lootReplacement(ServerPlayer player) {
        var state=reset(player); var armor=named(new ItemStack(Items.IRON_CHESTPLATE),"Loot board"); add(state,0,0,armor); var weapon=gun();
        var fixture=loot(player,state,weapon);
        try {
            reveal(fixture); move(fixture.menu,LootSession.id(0),1,0,0,false,false);
            same(find(state,weapon).stack,weapon,"Loot to bag incoming gun"); same(lootFind(fixture,armor).stack(),armor,"Displaced bag armor entered loot"); lootValid(fixture);
            var target=lootFind(fixture,armor); move(fixture.menu,find(state,weapon).id,LootSession.AREA,target.rectangle().x(),target.rectangle().y(),false,false);
            same(lootFind(fixture,weapon).stack(),weapon,"Bag to loot incoming gun"); same(find(state,armor).stack,armor,"Displaced loot armor returned to bag"); lootValid(fixture);
        } finally { fixture.menu.removed(player); }
        state=reset(player); var ration=named(new ItemStack(Items.BREAD,13),"Revealed ration"); fixture=loot(player,state,weapon,armor,ration);
        try {
            reveal(fixture); var target=lootFind(fixture,armor);
            move(fixture.menu,LootSession.id(0),LootSession.AREA,1,target.rectangle().y(),false,false);
            require(lootFind(fixture,weapon).rectangle().equals(new Grid.Rect(1,2,5,2)),"Same-loot multi-item replacement changed incoming root");
            same(lootFind(fixture,weapon).stack(),weapon,"Same-loot weapon"); same(lootFind(fixture,armor).stack(),armor,"Same-loot armor"); same(lootFind(fixture,ration).stack(),ration,"Same-loot ration");
            move(fixture.menu,lootFind(fixture,weapon).id(),LootSession.AREA,4,0,true,false);
            var rotated=lootFind(fixture,weapon); require(rotated.rotated() && rotated.rectangle().equals(new Grid.Rect(4,0,2,5)),"Loot auto/manual orientation was not retained in metadata");
            lootValid(fixture);
        } finally { fixture.menu.removed(player); }
    }
    private static void hiddenAndPhysicalSlots(ServerPlayer player) {
        var state=reset(player); var source=add(state,0,0,new ItemStack(Items.DIAMOND,9)); var fixture=loot(player,state,new ItemStack(Items.BREAD,13));
        try { rejected(fixture,source.id,LootSession.AREA,0,0,false,false,"Unknown loot displaced by an incoming item"); }
        finally { fixture.menu.removed(player); }
        state=reset(player); source=add(state,0,0,new ItemStack(Items.DIAMOND,9)); var stacks=new ItemStack[27];
        for(int slot=0;slot<27;slot++) stacks[slot]=named(new ItemStack(Items.BREAD,slot+1),"Physical slot "+slot);
        fixture=loot(player,state,stacks);
        try {
            rejected(fixture,source.id,LootSession.AREA,5,7,false,false,"Full 27-slot hidden chest accepted a 28th stack into visual free space");
            reveal(fixture); rejected(fixture,source.id,LootSession.AREA,5,7,false,false,"Full 27-slot revealed chest accepted a 28th stack");
            move(fixture.menu,LootSession.id(0),LootSession.AREA,1,0,false,false);
            same(lootFind(fixture,stacks[0]).stack(),stacks[0],"Full physical chest swap first stack"); same(lootFind(fixture,stacks[1]).stack(),stacks[1],"Full physical chest swap second stack");
            require(lootFind(fixture,stacks[0]).rectangle().x()==1 && lootFind(fixture,stacks[1]).rectangle().x()==0 && fixture.loot.layout().size()==27,"Same-loot full physical slot replacement failed"); lootValid(fixture);
        } finally { fixture.menu.removed(player); }
    }
    private static void lootStackLimit(ServerPlayer player) {
        var state=reset(player); var arrows=named(new ItemStack(Items.ARROW,99),"Preserve the ninety-nine arrows");
        var source=add(state,2,3,arrows); var originalRoot=source.rect();
        var counter=named(new ItemStack(Items.DIAMOND,11),"Unrelated physical slot zero"); var fixture=loot(player,state,counter);
        try {
            reveal(fixture);
            int advertised=fixture.loot.snapshot(player.registryAccess()).getInt("limit");
            int physical=fixture.container.getMaxStackSize();
            require(advertised==physical,"Standard chest advertised stack limit "+advertised+", expected runtime physical limit "+physical);
            require(fixture.loot.maxCount(arrows)==64,"Standard chest arrow-specific maximum is "+fixture.loot.maxCount(arrows)+", expected 64");
            move(fixture.menu,source.id,LootSession.AREA,5,7,false,false);
            require(state.entries.size()==1 && state.entry(source.id)!=null && state.entry(source.id).rect().equals(originalRoot),
                    "Capped loot deposit removed or shifted the partial source footprint");
            same(state.entry(source.id).stack,arrows.copyWithCount(35),"Capped deposit source remainder");
            var deposited=lootFind(fixture,arrows);
            same(deposited.stack(),arrows.copyWithCount(64),"Standard chest capped deposit");
            require(deposited.id()==LootSession.id(1) && deposited.rectangle().equals(new Grid.Rect(5,7,1,1)),
                    "Capped deposit used the unrelated physical slot or changed its requested root");
            same(fixture.container.getItem(0),counter,"Unrelated physical loot slot zero");
            require(state.entry(source.id).stack.getCount()+deposited.stack().getCount()==99,"Standard stack cap lost or duplicated arrows");
            lootValid(fixture);
        } finally { fixture.menu.removed(player); }
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
