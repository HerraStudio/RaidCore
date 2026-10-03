package dev.tactical;

import dev.tactical.loot.LootSession;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class BagMenu extends AbstractContainerMenu {
    public final Player player;
    public int revision;
    private CompoundTag last;
    private final LootSession loot;
    public BagMenu(int id,Inventory inv) { this(id,inv,null); }
    public BagMenu(int id,Inventory inv,LootSession loot) { super(Tactical.MENU.get(),id); player=inv.player; this.loot=loot; }
    public LootSession loot() { return loot; }
    @Override public boolean stillValid(Player p) { return p==player && p.isAlive() && !p.isSpectator() && (loot==null || loot.stillValid(p)); }
    @Override public void removed(Player owner) { super.removed(owner); if(loot!=null) loot.close(owner); }
    @Override public ItemStack quickMoveStack(Player p,int i) { return ItemStack.EMPTY; }
    public CompoundTag snapshot() {
        var tag=BagState.of(player).write(player.registryAccess()); var inv=new ListTag();
        for(int i=0;i<=40;i++) inv.add(player.getInventory().getItem(i).saveOptional(player.registryAccess()));
        tag.put("inventory",inv);
        tag.putInt("profileRules",dev.tactical.profile.ItemProfiles.current().revision());
        if(loot!=null) tag.put("loot",loot.snapshot(player.registryAccess()));
        return tag;
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if(player instanceof ServerPlayer server) {
            if(loot!=null && stillValid(player)) loot.tick(player.level().getGameTime());
            var tag=snapshot();
            if(!tag.equals(last)) { revision++; last=tag.copy(); PacketDistributor.sendToPlayer(server,new Packets.State(containerId,revision,tag)); }
        }
    }
    public void action(Packets.Action action) {
        if(action.menu()!=containerId || !stillValid(player)) return;
        broadcastChanges();
        if(action.menu()!=containerId || action.revision()!=revision || !stillValid(player)) return;
        var state=BagState.of(player); int src=action.source();
        boolean fromLoot=LootSession.isLootId(src);
        var stack=fromLoot?(loot==null?ItemStack.EMPTY:loot.get(src)):state.get(player,src);
        if(stack.isEmpty() || !state.canRemove(src)) return;
        // No client-supplied stacks or counts are trusted; all operations are atomic here.
        if(action.op()==2) {
            if(loot!=null) {
                var remainder=stack.copy();
                if(fromLoot) state.insert(player,remainder); else loot.insert(remainder);
                setSource(state,src,remainder);
                finish(state); return;
            }
            var moved=stack.copy(); state.set(player,src,ItemStack.EMPTY);
            state.insert(player,moved);
            if(!moved.isEmpty()) {
                // Preserve the original grid position if auto-transfer had no room.
                if(src>=100) {
                    var original=BagState.read(last,player.registryAccess()).entry(src);
                    if(original!=null) { original.stack=moved; state.entries.add(original); }
                } else state.set(player,src,moved);
            }
        } else if(action.op()==3) {
            var dropped=stack.copy(); setSource(state,src,ItemStack.EMPTY); player.drop(dropped,true);
        } else if(action.op()==1) {
            int amount=action.half() ? stack.getCount()/2+stack.getCount()%2 : stack.getCount();
            var part=stack.copyWithCount(amount);
            if(action.area()==LootSession.AREA) {
                if(loot==null) return;
                var target=loot.layout().stream().filter(entry->entry.id()!=src && entry.revealed()
                        && action.x()>=entry.rectangle().x() && action.y()>=entry.rectangle().y()
                        && action.x()<entry.rectangle().x()+entry.rectangle().w() && action.y()<entry.rectangle().y()+entry.rectangle().h()).findFirst().orElse(null);
                if(target!=null && ItemStack.isSameItemSameComponents(target.stack(),part) && loot.hasMergeRoom(target.id())) {
                    int moved=loot.place(part,src,action.x(),action.y(),action.rotated(),amount==stack.getCount());
                    if(moved<=0) return;
                    stack.shrink(moved); if(stack.isEmpty()) setSource(state,src,ItemStack.EMPTY);
                } else if(!BagPlacement.move(state,player,loot,src,action.area(),action.x(),action.y(),action.rotated(),action.half())) return;
                finish(state); return;
            }
            if(action.area()<0) {
                int dst=action.x();
                if(dst==src || dst<0 || dst>43 || !Rules.accepts(dst,part)) return;
                var target=state.get(player,dst);
                if((dst==42 || dst==43) && !target.isEmpty()) {
                    if(action.half() || !state.swapGear(player,src,dst,true)) return;
                    state.save(player); player.inventoryMenu.broadcastChanges(); broadcastChanges(); return;
                }
                if(!target.isEmpty() && ItemStack.isSameItemSameComponents(target,part) && target.getCount()<target.getMaxStackSize()) {
                    int before=part.getCount(); BagState.merge(target,part); amount=before-part.getCount();
                    if(amount==0) return;
                    stack.shrink(amount); if(stack.isEmpty()) setSource(state,src,ItemStack.EMPTY);
                } else if(!BagPlacement.move(state,player,loot,src,-1,dst,0,action.rotated(),action.half())) return;
            } else {
                int area=action.area(), x=action.x(),y=action.y();
                if(area<0 || area>1 || !state.unlocked(area)) return;
                // Never put an equipped container into the space supplied by itself.
                if((src==42 || src==43) && area==src-42) return;
                var target=state.entries.stream().filter(e->e.id!=src && e.area==area && x>=e.x && y>=e.y
                        && x<e.x+e.rect().w() && y<e.y+e.rect().h()).findFirst().orElse(null);
                if(target!=null && ItemStack.isSameItemSameComponents(target.stack,part) && target.stack.getCount()<target.stack.getMaxStackSize()) {
                    int before=part.getCount(); BagState.merge(target.stack,part); int moved=before-part.getCount();
                    if(moved==0) return; stack.shrink(moved); if(stack.isEmpty()) setSource(state,src,ItemStack.EMPTY);
                } else {
                    if(!BagPlacement.move(state,player,loot,src,area,x,y,action.rotated(),action.half())) return;
                }
            }
        } else return;
        finish(state);
    }
    private void setSource(BagState state,int source,ItemStack stack) {
        if(LootSession.isLootId(source)) loot.set(source,stack); else state.set(player,source,stack);
    }
    private void finish(BagState state) {
        if(loot!=null) loot.changed();
        state.save(player); player.inventoryMenu.broadcastChanges(); broadcastChanges();
    }
}
