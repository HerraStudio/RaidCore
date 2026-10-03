package dev.tactical;

import com.sgr792.gwo.GwoMod;
import com.sgr792.gwo.content.WeaponContentRegistry;
import com.sgr792.gwo.item.GunData;
import dev.tactical.loot.LootSession;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.LoggerFactory;

public final class LootChecks {
    private record Fixture(ServerPlayer player, SimpleContainer container, LootSession session, BagMenu menu) {}

    private LootChecks() {}

    public static void verify(ServerPlayer player) {
        require(player != null && player.isAlive() && !player.isSpectator(), "Loot checks require a live owner");
        verifyHiddenLootAndSearchOrder(player);
        verifySearchReplacement(player);
        verifyTransfers(player);
        verifyRejectedPlacements(player);
        verifyFullBagRetrieval(player);
        verifySharedContainer(player);
        verifyClose(player);
        LoggerFactory.getLogger("LootChecks").info("LOOT_SERVER_CHECKS_PASS");
    }

    private static void verifyHiddenLootAndSearchOrder(ServerPlayer player) {
        reset(player);
        var fixture = open(player, 80, gun(), new ItemStack(Items.IRON_CHESTPLATE),
                new ItemStack(Items.IRON_SWORD), new ItemStack(Items.BREAD, 13));
        try {
            var snapshot = fixture.menu.snapshot().getCompound("loot");
            require(snapshot.getInt("slots") == 27 && snapshot.getInt("rows") == 8, "Wrong chest dimensions");
            var items = snapshot.getList("items", Tag.TAG_COMPOUND);
            require(items.size() == 4 && snapshot.getInt("active") == -1, "Unexpected initial search state");
            for (int index = 0; index < items.size(); index++) {
                var item = items.getCompound(index);
                require(item.getAllKeys().equals(Set.of("id", "x", "y", "w", "h", "revealed", "rot")),
                        "Hidden snapshot contains stack data");
                require(!item.getBoolean("revealed") && !fixture.session.revealed(item.getInt("id"))
                        && fixture.session.get(item.getInt("id")).isEmpty(), "Hidden loot is accessible");
            }
            requireRectangle(fixture, 0, 0, 0, 5, 2);
            requireRectangle(fixture, 1, 0, 2, 2, 3);
            requireRectangle(fixture, 2, 5, 0, 1, 3);
            requireRectangle(fixture, 3, 2, 2, 1, 1);

            requireRejected(fixture, request(fixture.menu, 1, LootSession.id(3), -1, 4, 0, false, false),
                    "Unsearched loot moved to a pocket");
            requireRejected(fixture, request(fixture.menu, 1, LootSession.id(0), 1, 0, 0, false, false),
                    "Unsearched gun moved to the backpack");
            requireRejected(fixture, request(fixture.menu, 2, LootSession.id(3), 0, 0, 0, false, false),
                    "Unsearched loot was automatically retrieved");
            requireRejected(fixture, request(fixture.menu, 3, LootSession.id(3), 0, 0, 0, false, false),
                    "Unsearched loot was dropped");

            int[] order = {0, 2, 1, 3};
            int[] durations = {38, 24, 30, 20};
            long started = player.level().getGameTime();
            for (int index = 0; index < order.length; index++) {
                int slot = order[index];
                requireSearch(fixture, slot, started, durations[index]);
                fixture.session.tick(started + durations[index] - 1);
                for (int pending = index; pending < order.length; pending++) {
                    require(!fixture.session.revealed(LootSession.id(order[pending])), "Loot revealed before its deadline");
                    require(!lootItem(fixture, order[pending]).contains("stack"), "Searching snapshot leaked a stack");
                }
                started += durations[index];
                fixture.session.tick(started);
                require(fixture.session.revealed(LootSession.id(slot)), "Search did not finish at its deadline");
                var revealed = ItemStack.parseOptional(player.registryAccess(), lootItem(fixture, slot).getCompound("stack"));
                requireStack(revealed, fixture.container.getItem(slot), fixture.container.getItem(slot).getCount(),
                        "Revealed snapshot changed stack data");
            }
            require(fixture.session.snapshot(player.registryAccess()).getInt("active") == -1, "Completed search remained active");
        } finally {
            fixture.menu.removed(player);
        }
    }

    private static void verifySearchReplacement(ServerPlayer player) {
        reset(player);
        var fixture = open(player, 81, new ItemStack(Items.BREAD, 9));
        try {
            long started = player.level().getGameTime();
            fixture.session.tick(started);
            fixture.session.tick(started + 19);
            fixture.container.setItem(0, new ItemStack(Items.IRON_SWORD));
            fixture.session.tick(started + 20);
            requireSearch(fixture, 0, started + 20, 24);
            requireRectangle(fixture, 0, 0, 0, 1, 3);
            require(!fixture.session.revealed(LootSession.id(0)) && !lootItem(fixture, 0).contains("stack"),
                    "Replacement inherited the previous search");
            fixture.session.tick(started + 43);
            require(!fixture.session.revealed(LootSession.id(0)), "Replacement revealed too early");

            var damaged = new ItemStack(Items.IRON_SWORD);
            damaged.setDamageValue(7);
            fixture.container.setItem(0, damaged.copy());
            fixture.session.tick(started + 44);
            requireSearch(fixture, 0, started + 44, 24);
            require(!lootItem(fixture, 0).contains("stack"), "Component replacement inherited search progress");
            fixture.session.tick(started + 67);
            require(!fixture.session.revealed(LootSession.id(0)), "Component replacement revealed too early");
            fixture.session.tick(started + 68);
            requireStack(fixture.session.get(LootSession.id(0)), damaged, 1, "Replacement search lost components");

            fixture.container.setItem(0, new ItemStack(Items.DIAMOND, 5));
            fixture.session.tick(started + 69);
            requireSearch(fixture, 0, started + 69, 20);
            require(!fixture.session.revealed(LootSession.id(0)) && !lootItem(fixture, 0).contains("stack"),
                    "Replacement inherited revealed status");
        } finally {
            fixture.menu.removed(player);
        }
    }

    private static void verifyTransfers(ServerPlayer player) {
        var state = reset(player);
        var ration = new ItemStack(Items.BREAD, 13);
        ration.set(DataComponents.CUSTOM_NAME, Component.literal("Loot check ration"));
        var weapon = gun();
        var fixture = open(player, 82, ration, ration.copyWithCount(60), weapon);
        try {
            revealAll(fixture);
            var stale = request(fixture.menu, 1, LootSession.id(0), -1, 5, 0, false, true);
            act(fixture, 1, LootSession.id(1), -1, 4, 0, false, false);
            requireStack(player.getInventory().getItem(4), ration, 60, "Loot-to-pocket transfer failed");
            require(fixture.container.getItem(1).isEmpty(), "Pocket transfer left its source behind");
            require(fixture.menu.revision > stale.revision(), "Transfer did not invalidate the old revision");
            requireRejected(fixture, stale, "Stale action changed contents");

            act(fixture, 1, LootSession.id(0), -1, 5, 0, false, true);
            requireStack(player.getInventory().getItem(5), ration, 7, "Odd half-stack did not round up");
            requireStack(fixture.container.getItem(0), ration, 6, "Half-stack source remainder is wrong");
            act(fixture, 1, LootSession.id(0), -1, 4, 0, false, false);
            requireStack(player.getInventory().getItem(4), ration, 64, "Pocket merge exceeded or missed its limit");
            requireStack(fixture.container.getItem(0), ration, 2, "Merge lost its leftover items");

            var target = lootItem(fixture, 0);
            act(fixture, 1, 5, LootSession.AREA, target.getInt("x"), target.getInt("y"), false, false);
            require(player.getInventory().getItem(5).isEmpty(), "Deposit left its pocket source behind");
            requireStack(fixture.container.getItem(0), ration, 9, "Deposit did not merge into revealed loot");
            act(fixture, 1, 4, LootSession.AREA, 5, 7, false, true);
            requireStack(player.getInventory().getItem(4), ration, 32, "Half-deposit source remainder is wrong");
            requireStack(fixture.container.getItem(1), ration, 32, "Half-deposit did not create a chest stack");
            requireRectangle(fixture, 1, 5, 7, 1, 1);
            require(fixture.session.revealed(LootSession.id(1)), "Deposited stack became hidden");
            require(count(fixture, ration) == 73, "Split or merge changed the total item count");

            act(fixture, 1, LootSession.id(2), 1, 0, 0, false, false);
            var entry = state.entries.stream().filter(candidate -> Rules.gun(candidate.stack)).findFirst()
                    .orElseThrow(() -> new AssertionError("Gun did not enter the backpack"));
            require(entry.rect().equals(new Grid.Rect(0, 0, 5, 2)), "Backpack gun footprint is wrong");
            requireStack(entry.stack, weapon, 1, "Loot-to-bag transfer changed GWO components");
            require(fixture.container.getItem(2).isEmpty(), "Gun transfer duplicated its chest source");
            int source = entry.id;
            act(fixture, 1, source, 1, 4, 0, true, false);
            var rotated = state.entries.stream().filter(candidate -> Rules.gun(candidate.stack)).findFirst()
                    .orElseThrow(() -> new AssertionError("Rotation lost the gun"));
            require(rotated.rotated && rotated.rect().equals(new Grid.Rect(4, 0, 2, 5)), "Gun rotation failed");
            requireStack(rotated.stack, weapon, 1, "Rotation changed GWO components");
            act(fixture, 1, rotated.id, LootSession.AREA, 0, 3, true, false);
            require(state.entries.isEmpty(), "Bag-to-loot transfer left its grid source behind");
            requireStack(fixture.container.getItem(2), weapon, 1, "Bag-to-loot transfer changed GWO components");
            requireRectangle(fixture, 2, 0, 3, 2, 5);
            require(fixture.session.revealed(LootSession.id(2)) && count(fixture, weapon) == 1,
                    "Gun deposit hid or duplicated the weapon");
        } finally {
            fixture.menu.removed(player);
        }
    }

    private static void verifyRejectedPlacements(ServerPlayer player) {
        var state = reset(player);
        var armor = new BagState.Entry(state.nextId++, 1, 0, 0, false, new ItemStack(Items.IRON_CHESTPLATE));
        state.entries.add(armor);
        player.getInventory().setItem(4, new ItemStack(Items.DIAMOND, 9));
        player.getInventory().setItem(5, new ItemStack(Items.BREAD, 64));
        var fixture = open(player, 83, gun(), new ItemStack(Items.BREAD, 11));
        try {
            revealAll(fixture);
            var before = fixture.menu.snapshot();
            requireRejected(fixture, request(fixture.menu, 1, LootSession.id(0), -1, 6, 0, false, false),
                    "Gun entered a pocket");
            requireRejected(fixture, request(fixture.menu, 1, LootSession.id(0), 1, 5, 0, false, false),
                    "Out-of-bounds bag placement changed items");
            requireRejected(fixture, request(fixture.menu, 1, LootSession.id(1), 1, 1, 0, false, true),
                    "Partial loot stack replaced occupied armor in the bag");
            requireRejected(fixture, request(fixture.menu, 1, LootSession.id(0), 0, 0, 0, false, false),
                    "Locked rig accepted loot");
            requireRejected(fixture, request(fixture.menu, 1, LootSession.id(1), -1, 4, 0, false, true),
                    "Partial loot stack replaced incompatible pocket contents");
            requireRejected(fixture, request(fixture.menu, 1, LootSession.id(1), -1, 5, 0, false, true),
                    "Partial stack replaced a full identical pocket stack");
            requireRejected(fixture, request(fixture.menu, 1, LootSession.id(1), LootSession.AREA, 0, 0, false, true),
                    "Partial loot stack replaced an occupied loot gun");
            requireRejected(fixture, request(fixture.menu, 1, 4, LootSession.AREA, 6, 0, false, false),
                    "Out-of-bounds loot column changed items");
            requireRejected(fixture, request(fixture.menu, 1, 4, LootSession.AREA, 0, -1, false, false),
                    "Negative loot row changed items");
            require(before.equals(fixture.menu.snapshot()), "Rejected placements changed layout or visibility");
        } finally {
            fixture.menu.removed(player);
        }
    }

    private static void verifyFullBagRetrieval(ServerPlayer player) {
        var state = reset(player);
        for (int slot = 4; slot <= 8; slot++) player.getInventory().setItem(slot, new ItemStack(Items.DIAMOND, 64));
        player.getInventory().setItem(4, new ItemStack(Items.BREAD, 63));
        var capacity = state.capacity(1);
        for (int row = 0; row < capacity.h(); row++) {
            for (int column = 0; column < capacity.w(); column++) {
                state.entries.add(new BagState.Entry(state.nextId++, 1, column, row, false, new ItemStack(Items.DIAMOND, 64)));
            }
        }
        require(!state.fits(1, 0, 0, false, new ItemStack(Items.BREAD), -1), "Full bag fixture has a free cell");
        var fixture = open(player, 84, new ItemStack(Items.BREAD, 11));
        try {
            revealAll(fixture);
            int diamonds = count(fixture, new ItemStack(Items.DIAMOND));
            act(fixture, 2, LootSession.id(0), 0, 0, 0, false, false);
            requireStack(player.getInventory().getItem(4), new ItemStack(Items.BREAD), 64, "Auto-retrieve missed available merge space");
            requireStack(fixture.container.getItem(0), new ItemStack(Items.BREAD), 10, "Auto-retrieve lost chest leftovers");
            require(count(fixture, new ItemStack(Items.BREAD)) == 74, "Auto-retrieve changed the bread total");
            require(count(fixture, new ItemStack(Items.DIAMOND)) == diamonds, "Auto-retrieve displaced bag contents");
            requireRejected(fixture, request(fixture.menu, 2, LootSession.id(0), 0, 0, 0, false, false),
                    "Auto-retrieve into a full bag changed contents");
        } finally {
            fixture.menu.removed(player);
        }
    }

    private static void verifySharedContainer(ServerPlayer player) {
        reset(player);
        var shared = new SimpleContainer(27);
        var bread = new ItemStack(Items.BREAD, 11);
        shared.setItem(0, bread.copy());
        var first = open(player, 85, shared);
        var second = open(player, 86, shared);
        try {
            require(first.session != second.session && first.menu != second.menu, "Shared fixture reused its session or menu");
            revealAll(first);
            require(!second.session.revealed(LootSession.id(0)) && second.session.get(LootSession.id(0)).isEmpty(),
                    "Independent session inherited another search");
            revealAll(second);
            var queuedSecond = request(second.menu, 1, LootSession.id(0), -1, 5, 0, false, false);
            act(first, 1, LootSession.id(0), -1, 4, 0, false, true);
            requireStack(player.getInventory().getItem(4), bread, 6, "First viewer failed to split shared loot");
            requireStack(shared.getItem(0), bread, 5, "First viewer left the wrong shared remainder");
            requireRejected(second, queuedSecond, "Second viewer accepted a stale shared count");
            require(second.menu.revision > queuedSecond.revision(), "Shared change did not advance the second revision");

            var queuedFirst = request(first.menu, 1, LootSession.id(0), -1, 6, 0, false, false);
            act(second, 1, LootSession.id(0), -1, 5, 0, false, false);
            requireStack(player.getInventory().getItem(5), bread, 5, "Second viewer did not receive the actual remainder");
            require(shared.getItem(0).isEmpty(), "Shared loot remained after retrieval");
            requireRejected(first, queuedFirst, "First viewer duplicated the removed shared stack");
            requireRejected(first, request(first.menu, 2, LootSession.id(0), 0, 0, 0, false, false),
                    "Fresh action retrieved an already removed shared stack");
            require(player.getInventory().getItem(6).isEmpty() && count(first, bread) == 11, "Shared sessions duplicated or lost loot");
        } finally {
            first.menu.removed(player);
            second.menu.removed(player);
        }
    }

    private static void verifyClose(ServerPlayer player) {
        reset(player);
        var fixture = open(player, 87, new ItemStack(Items.BREAD, 15), gun());
        try {
            revealAll(fixture);
            act(fixture, 1, LootSession.id(0), -1, 4, 0, false, true);
            requireStack(fixture.container.getItem(0), new ItemStack(Items.BREAD), 7, "Close fixture lost its remainder");
            var lateMove = request(fixture.menu, 1, LootSession.id(0), -1, 5, 0, false, false);
            var lateRetrieve = request(fixture.menu, 2, LootSession.id(1), 0, 0, 0, false, false);
            var lateDeposit = request(fixture.menu, 1, 4, LootSession.AREA, 5, 7, false, false);
            var lateDrop = request(fixture.menu, 3, LootSession.id(1), 0, 0, 0, false, false);
            var before = contents(fixture);
            fixture.menu.removed(player);
            require(!fixture.menu.stillValid(player) && !fixture.session.stillValid(player), "Closed session remained valid");
            require(before.equals(contents(fixture)), "Closing changed chest or player contents");
            requireRejected(fixture, lateMove, "Closed menu moved loot");
            requireRejected(fixture, lateRetrieve, "Closed menu automatically retrieved loot");
            requireRejected(fixture, lateDeposit, "Closed menu accepted a deposit");
            requireRejected(fixture, lateDrop, "Closed menu dropped loot");
            fixture.menu.removed(player);
            require(before.equals(contents(fixture)), "Repeated close changed contents");
        } finally {
            fixture.menu.removed(player);
        }
    }

    private static BagState reset(ServerPlayer player) {
        var state = BagState.of(player);
        player.getInventory().clearContent();
        state.entries.clear();
        state.nextId = 100;
        for (int index = 0; index < state.gear.length; index++) state.gear[index] = ItemStack.EMPTY;
        state.gear[2] = new ItemStack(Tactical.BACKPACK.get());
        state.save(player);
        return state;
    }

    private static ItemStack gun() {
        var content = WeaponContentRegistry.ids().stream().filter(id -> id.getPath().contains("ak103")).findFirst()
                .orElseThrow(() -> new AssertionError("Missing GWO AK103 smoke fixture"));
        var stack = GwoMod.weaponStack(content);
        GunData.initialize(stack, content, WeaponContentRegistry.get(content));
        GunData.setAmmo(stack, 7);
        require(Rules.gun(stack) && Rules.size(stack).equals(new Rules.Size(5, 2)), "GWO fixture is not a 5x2 gun");
        return stack;
    }

    private static Fixture open(ServerPlayer player, int menuId, ItemStack... stacks) {
        var container = new SimpleContainer(27);
        for (int slot = 0; slot < stacks.length; slot++) container.setItem(slot, stacks[slot].copy());
        return open(player, menuId, container);
    }

    private static Fixture open(ServerPlayer player, int menuId, SimpleContainer container) {
        var original = ChestMenu.threeRows(menuId, player.getInventory(), container);
        var session = new LootSession(original, Component.literal("Loot server checks"));
        var menu = new BagMenu(menuId, player.getInventory(), session);
        require(menu.stillValid(player) && session.contains(container), "Loot fixture is not attached to its chest");
        return new Fixture(player, container, session, menu);
    }

    private static void revealAll(Fixture fixture) {
        long started = fixture.player.level().getGameTime();
        for (int step = 0; step <= fixture.container.getContainerSize(); step++) fixture.session.tick(started + step * 100L);
        for (int slot = 0; slot < fixture.container.getContainerSize(); slot++) {
            if (!fixture.container.getItem(slot).isEmpty()) {
                require(fixture.session.revealed(LootSession.id(slot)), "Reveal-all left a hidden stack");
            }
        }
        require(fixture.session.snapshot(fixture.player.registryAccess()).getInt("active") == -1, "Reveal-all left an active search");
        fixture.menu.broadcastChanges();
    }

    private static Packets.Action request(BagMenu menu, int operation, int source, int area, int x, int y, boolean rotated, boolean half) {
        menu.broadcastChanges();
        return new Packets.Action(menu.containerId, menu.revision, operation, source, area, x, y, rotated, half);
    }

    private static void act(Fixture fixture, int operation, int source, int area, int x, int y, boolean rotated, boolean half) {
        fixture.menu.action(request(fixture.menu, operation, source, area, x, y, rotated, half));
    }

    private static void requireRejected(Fixture fixture, Packets.Action action, String message) {
        var before = contents(fixture);
        fixture.menu.action(action);
        require(before.equals(contents(fixture)), message);
    }

    private static CompoundTag contents(Fixture fixture) {
        var data = fixture.menu.snapshot();
        data.remove("loot");
        var stacks = new ListTag();
        for (int slot = 0; slot < fixture.container.getContainerSize(); slot++) {
            stacks.add(fixture.container.getItem(slot).saveOptional(fixture.player.registryAccess()));
        }
        data.put("container", stacks);
        return data;
    }

    private static int count(Fixture fixture, ItemStack expected) {
        int total = 0;
        for (int slot = 0; slot < fixture.player.getInventory().getContainerSize(); slot++) {
            var stack = fixture.player.getInventory().getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, expected)) total += stack.getCount();
        }
        var state = BagState.of(fixture.player);
        for (var stack : state.gear) if (ItemStack.isSameItemSameComponents(stack, expected)) total += stack.getCount();
        for (var entry : state.entries) if (ItemStack.isSameItemSameComponents(entry.stack, expected)) total += entry.stack.getCount();
        for (int slot = 0; slot < fixture.container.getContainerSize(); slot++) {
            var stack = fixture.container.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, expected)) total += stack.getCount();
        }
        return total;
    }

    private static CompoundTag lootItem(Fixture fixture, int slot) {
        var items = fixture.session.snapshot(fixture.player.registryAccess()).getList("items", Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size(); index++) {
            var item = items.getCompound(index);
            if (item.getInt("id") == LootSession.id(slot)) return item;
        }
        throw new AssertionError("Missing loot entry for slot " + slot);
    }

    private static void requireRectangle(Fixture fixture, int slot, int x, int y, int width, int height) {
        var item = lootItem(fixture, slot);
        require(item.getInt("x") == x && item.getInt("y") == y && item.getInt("w") == width && item.getInt("h") == height,
                "Unexpected loot rectangle for slot " + slot);
    }

    private static void requireSearch(Fixture fixture, int slot, long started, int duration) {
        var snapshot = fixture.session.snapshot(fixture.player.registryAccess());
        require(snapshot.getInt("active") == LootSession.id(slot), "Search did not follow row-major order");
        require(snapshot.getLong("started") == started && snapshot.getInt("duration") == duration,
                "Search start or duration is wrong for slot " + slot);
    }

    private static void requireStack(ItemStack actual, ItemStack expected, int amount, String message) {
        require(actual.getCount() == amount && ItemStack.isSameItemSameComponents(actual, expected), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
