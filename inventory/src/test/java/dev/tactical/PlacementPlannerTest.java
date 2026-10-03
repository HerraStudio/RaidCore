package dev.tactical;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlacementPlannerTest {
    private static PlacementPlanner.Region grid(int width,int height) {
        return new PlacementPlanner.Region(1,width,height,false,false);
    }
    private static PlacementPlanner.Item item(int id,int region,int x,int y,int width,int height,Set<Integer> allowed) {
        return new PlacementPlanner.Item(id,region,new Grid.Rect(x,y,width,height),width,height,false,true,allowed);
    }
    private static PlacementPlanner.Item item(int id,int x,int y,int width,int height) {
        return item(id,1,x,y,width,height,Set.of(1));
    }
    private static PlacementPlanner.Model model(PlacementPlanner.Region region,PlacementPlanner.Item... items) {
        return new PlacementPlanner.Model(List.of(region),List.of(items));
    }
    private static void valid(PlacementPlanner.Model model,PlacementPlanner.Plan plan,int source,boolean entire) {
        assertNotNull(plan);
        var moving=new HashSet<Integer>();
        for(var placement:plan.placements()) assertTrue(moving.add(placement.item()));
        var occupied=new HashMap<Integer,List<Grid.Rect>>();
        for(var item:model.items()) if(!moving.contains(item.id()) || item.id()==source && !entire)
            occupied.computeIfAbsent(item.region(),key->new ArrayList<>()).add(item.rectangle());
        for(var placement:plan.placements()) {
            var item=model.items().stream().filter(entry->entry.id()==placement.item()).findFirst().orElseThrow();
            var region=model.regions().stream().filter(entry->entry.id()==placement.region()).findFirst().orElseThrow();
            var rectangle=new Grid.Rect(placement.x(),placement.y(),region.fixedSlot()?1:placement.rotated()?item.height():item.width(),
                    region.fixedSlot()?1:placement.rotated()?item.width():item.height());
            assertTrue(item.allowedRegions().contains(region.id()));
            var previous=occupied.computeIfAbsent(region.id(),key->new ArrayList<>());
            assertTrue(Grid.fits(region.rig()?0:1,region.width(),region.height(),rectangle,previous));
            previous.add(rectangle);
            assertTrue(previous.size()<=region.itemLimit());
        }
    }

    @Test void replacementMovesEveryCoveredItemIntoRemainingVacatedCells() {
        var model=model(grid(9,2),item(100,4,0,5,2),item(101,2,0,2,2),item(102,0,0,1,2),
                item(103,1,1,1,1),item(104,1,0,1,1));
        var plan=PlacementPlanner.plan(model,100,1,0,0,false,true);
        valid(model,plan,100,true); assertEquals(5,plan.placements().size());
        assertEquals(new PlacementPlanner.Placement(100,1,0,0,false),plan.placements().getFirst());
        for(var placement:plan.placements().subList(1,5)) assertTrue(placement.x()>=5);
        assertEquals(new PlacementPlanner.Placement(101,1,6,0,false),plan.placements().stream().filter(placement->placement.item()==101).findFirst().orElseThrow());
        assertEquals(new PlacementPlanner.Placement(103,1,5,1,false),plan.placements().stream().filter(placement->placement.item()==103).findFirst().orElseThrow());
        assertEquals(new PlacementPlanner.Placement(102,1,8,0,false),plan.placements().stream().filter(placement->placement.item()==102).findFirst().orElseThrow());
    }

    @Test void displacedGunShiftsLeftInsteadOfUsingOnlyOldSourceRoot() {
        var model=model(grid(9,2),item(100,2,0,2,2),item(101,4,0,5,2),item(102,0,0,1,2));
        var plan=PlacementPlanner.plan(model,100,1,6,0,false,true);
        valid(model,plan,100,true);
        assertEquals(new PlacementPlanner.Placement(101,1,1,0,false),plan.placements().get(1));
        assertFalse(plan.placements().stream().anyMatch(placement->placement.item()==102));
    }

    @Test void identicalUnstackableGunFootprintsCanExchangeTheirRoots() {
        var model=model(grid(6,4),item(100,0,0,5,2),item(101,0,2,5,2));
        var plan=PlacementPlanner.plan(model,100,1,0,2,false,true);
        valid(model,plan,100,true);
        assertEquals(new PlacementPlanner.Placement(100,1,0,2,false),plan.placements().getFirst());
        assertEquals(new PlacementPlanner.Placement(101,1,0,0,false),plan.placements().get(1));
    }

    @Test void coveredLargeItemCannotDisappearWhenPackingFails() {
        var model=model(grid(9,2),item(100,2,0,2,2),item(101,4,0,5,2),item(102,0,0,1,2));
        assertNull(PlacementPlanner.plan(model,100,1,4,0,false,true));
        assertEquals(new Grid.Rect(4,0,5,2),model.items().get(1).rectangle());
    }

    @Test void displacedItemCanRotateIntoNarrowSourceRegion() {
        var rig=new PlacementPlanner.Region(0,1,2,true,false);
        var model=new PlacementPlanner.Model(List.of(rig,grid(2,1)),List.of(
                item(100,0,0,0,1,2,Set.of(0,1)),item(101,1,0,0,2,1,Set.of(0,1))));
        var plan=PlacementPlanner.plan(model,100,1,0,0,true,true);
        valid(model,plan,100,true);
        assertEquals(new PlacementPlanner.Placement(101,0,0,0,true),plan.placements().get(1));
    }

    @Test void hiddenLootIsAnImmovableObstacle() {
        var hidden=new PlacementPlanner.Item(-2,1,new Grid.Rect(0,0,1,1),1,1,false,false,Set.of());
        var model=model(grid(2,1),item(100,1,0,1,1),hidden);
        assertNull(PlacementPlanner.plan(model,100,1,0,0,false,true));
    }

    @Test void partialStackCannotReplaceOrReuseItsOwnOccupiedFootprint() {
        var model=model(grid(3,1),item(100,0,0,1,1),item(101,1,0,1,1));
        assertNull(PlacementPlanner.plan(model,100,1,1,0,false,false));
        assertNull(PlacementPlanner.plan(model,100,1,0,0,false,false));
        valid(model,PlacementPlanner.plan(model,100,1,2,0,false,false),100,false);
    }

    @Test void fixedSlotSwapRequiresBothDirectionsToAcceptTheirItems() {
        var fixed=new PlacementPlanner.Region(-14,1,1,false,true);
        var incoming=item(4,-14,0,0,1,1,Set.of(-14,1));
        var target=item(100,1,0,0,1,1,Set.of(-14,1));
        var model=new PlacementPlanner.Model(List.of(fixed,grid(1,1)),List.of(incoming,target));
        valid(model,PlacementPlanner.plan(model,4,1,0,0,false,true),4,true);
        var forbidden=item(100,1,0,0,1,1,Set.of(1));
        assertNull(PlacementPlanner.plan(new PlacementPlanner.Model(model.regions(),List.of(incoming,forbidden)),4,1,0,0,false,true));
    }

    @Test void physicalLootSlotLimitForcesDisplacedItemBackToSource() {
        var fixed=new PlacementPlanner.Region(-14,1,1,false,true);
        var loot=new PlacementPlanner.Region(3,6,8,false,false,1);
        var incoming=item(4,-14,0,0,1,1,Set.of(-14,3));
        var target=item(-2,3,0,0,1,1,Set.of(-14,3));
        var model=new PlacementPlanner.Model(List.of(fixed,loot),List.of(incoming,target));
        var plan=PlacementPlanner.plan(model,4,3,0,0,false,true);
        valid(model,plan,4,true);
        assertEquals(-14,plan.placements().get(1).region());
        assertNull(PlacementPlanner.plan(new PlacementPlanner.Model(model.regions(),List.of(incoming,
                item(-2,3,0,0,1,1,Set.of(3)))),4,3,0,0,false,true));
    }

    @Test void emptyGeometryDoesNotMeanThereIsAnAvailableLootSlot() {
        var loot=new PlacementPlanner.Region(3,6,8,false,false,1);
        var incoming=item(100,1,0,0,1,1,Set.of(1,3));
        var target=item(-2,3,0,0,1,1,Set.of(3));
        var model=new PlacementPlanner.Model(List.of(grid(1,1),loot),List.of(incoming,target));
        assertNull(PlacementPlanner.plan(model,100,3,5,7,false,true));
        assertNull(PlacementPlanner.plan(new PlacementPlanner.Model(List.of(loot),List.of(target)),-2,3,5,7,false,false));
    }

    @Test void doesNotRepackUncoveredItemsOrUseAnUnrelatedRegion() {
        var incoming=item(100,0,0,0,1,1,Set.of(0,1,3));
        var target=item(101,1,0,0,2,2,Set.of(1,3));
        var model=new PlacementPlanner.Model(List.of(new PlacementPlanner.Region(0,1,1,true,false),grid(2,2),
                new PlacementPlanner.Region(3,6,8,false,false)),List.of(incoming,target));
        assertNull(PlacementPlanner.plan(model,100,1,0,0,false,true));
    }

    @Test void plannerIsDeterministicAndDoesNotMutateItsInput() {
        var model=model(grid(4,2),item(100,2,0,2,2),item(101,0,0,1,2),item(102,1,0,1,2));
        var before=model.items();
        var first=PlacementPlanner.plan(model,100,1,0,0,false,true);
        valid(model,first,100,true);
        for(int index=0;index<10;index++) assertEquals(first,PlacementPlanner.plan(model,100,1,0,0,false,true));
        assertEquals(before,model.items());
    }

    @Test void extremeRootsAndInvalidRigPouchesAreRejected() {
        var model=model(grid(6,6),item(100,0,0,5,2));
        assertNull(PlacementPlanner.plan(model,100,1,Integer.MAX_VALUE,0,false,true));
        assertNull(PlacementPlanner.plan(model,100,1,-1,0,false,true));
        var rig=new PlacementPlanner.Region(0,4,5,true,false);
        var narrow=item(100,0,0,0,1,2,Set.of(0));
        assertNull(PlacementPlanner.plan(model(rig,narrow),100,0,0,1,false,true));
        assertNull(PlacementPlanner.plan(model(rig,narrow),100,0,0,4,false,true));
    }

    @Test void actualFixedSlotAmountRetainsTheSourceForOverstackedItems() {
        assertEquals(1,BagPlacement.amount(7,false,-1,0));
        assertEquals(1,BagPlacement.amount(7,true,-1,39));
        assertEquals(4,BagPlacement.amount(7,true,1,0));
        assertEquals(7,BagPlacement.amount(7,false,-1,4));
        assertEquals(Integer.MAX_VALUE/2+1,BagPlacement.amount(Integer.MAX_VALUE,true,1,0));
    }

    @Test void smallLayoutsMatchIndependentExhaustivePacking() {
        var random=new Random(0x656121L);
        for(int fixture=0;fixture<300;fixture++) {
            var items=new ArrayList<PlacementPlanner.Item>();
            var rectangles=new ArrayList<Grid.Rect>();
            for(int id=100;id<105;id++) {
                int width=1+random.nextInt(2),height=1+random.nextInt(2);
                var roots=new ArrayList<Grid.Rect>();
                for(int y=0;y<3;y++) for(int x=0;x<4;x++) {
                    var rectangle=new Grid.Rect(x,y,width,height);
                    if(Grid.fits(1,4,3,rectangle,rectangles)) roots.add(rectangle);
                }
                if(roots.isEmpty()) continue;
                var root=roots.get(random.nextInt(roots.size())); rectangles.add(root);
                items.add(new PlacementPlanner.Item(id,1,root,width,height,false,id==100 || random.nextInt(8)!=0,Set.of(1)));
            }
            if(items.isEmpty() || items.getFirst().id()!=100) continue;
            int x=random.nextInt(4),y=random.nextInt(3); boolean rotated=random.nextBoolean();
            var model=new PlacementPlanner.Model(List.of(grid(4,3)),items);
            boolean expected=exhaustive(model,100,x,y,rotated);
            var actual=PlacementPlanner.plan(model,100,1,x,y,rotated,true);
            assertEquals(expected,actual!=null,"Fixture "+fixture+" "+model.items()+" target "+x+","+y+","+rotated);
            if(actual!=null) valid(model,actual,100,true);
        }
    }

    private static boolean exhaustive(PlacementPlanner.Model model,int source,int x,int y,boolean rotated) {
        var incoming=model.items().stream().filter(item->item.id()==source).findFirst().orElseThrow();
        var rectangle=new Grid.Rect(x,y,rotated?incoming.height():incoming.width(),rotated?incoming.width():incoming.height());
        if(!Grid.fits(1,4,3,rectangle,List.of())) return false;
        var displaced=new ArrayList<PlacementPlanner.Item>(); var occupied=new ArrayList<Grid.Rect>();
        occupied.add(rectangle);
        for(var item:model.items()) {
            if(item.id()==source) continue;
            if(rectangle.overlaps(item.rectangle())) {
                if(!item.movable()) return false;
                displaced.add(item);
            } else occupied.add(item.rectangle());
        }
        return exhaustivePlace(displaced,0,occupied);
    }
    private static boolean exhaustivePlace(List<PlacementPlanner.Item> items,int index,List<Grid.Rect> occupied) {
        if(index==items.size()) return true;
        var item=items.get(index);
        for(int direction=0;direction<2;direction++) {
            int width=direction==0?item.width():item.height(),height=direction==0?item.height():item.width();
            for(int y=0;y<3;y++) for(int x=0;x<4;x++) {
                var rectangle=new Grid.Rect(x,y,width,height);
                if(!Grid.fits(1,4,3,rectangle,occupied)) continue;
                occupied.add(rectangle);
                if(exhaustivePlace(items,index+1,occupied)) return true;
                occupied.removeLast();
            }
        }
        return false;
    }
}
