package dev.draginventory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class WeaponSlotsTest {
    @Test void scrollCyclesOnlyTheFourWeaponSlots() {
        assertEquals(3, WeaponSlots.next(0, 1, WeaponSlots.FULL));
        assertEquals(1, WeaponSlots.next(0, -1, WeaponSlots.FULL));
        assertEquals(3, WeaponSlots.next(4, 1, WeaponSlots.FULL));
        assertEquals(0, WeaponSlots.next(4, -1, WeaponSlots.FULL));
        assertEquals(8, WeaponSlots.next(8, 0, WeaponSlots.FULL));
    }

    @Test void candidateOrderMatchesHudTable() {
        assertArrayEquals(new int[] {1, 2, 3}, WeaponSlots.candidates(0, WeaponSlots.FULL));
        assertArrayEquals(new int[] {0, 2, 3}, WeaponSlots.candidates(1, WeaponSlots.FULL));
        assertArrayEquals(new int[] {0, 1, 3}, WeaponSlots.candidates(2, WeaponSlots.FULL));
        assertArrayEquals(new int[] {0, 1, 2}, WeaponSlots.candidates(3, WeaponSlots.FULL));
        assertArrayEquals(new int[0], WeaponSlots.candidates(4, WeaponSlots.FULL));
    }

    @Test void rolesUseFixedPositions() {
        assertEquals("primary", WeaponSlots.role(0));
        assertEquals("primary", WeaponSlots.role(1));
        assertEquals("pistol", WeaponSlots.role(2));
        assertEquals("melee", WeaponSlots.role(3));
        assertEquals("other", WeaponSlots.role(8));
        assertTrue(WeaponSlots.isWeaponSlot(3));
        assertFalse(WeaponSlots.isWeaponSlot(4));
    }

    @Test void scrollingSkipsGapsInBothDirectionsAndFromAnEmptySelectedSlot() {
        int occupied = 0b1101; // Physical slot 2 is empty.
        assertEquals(2, WeaponSlots.next(0, -1, occupied));
        assertEquals(0, WeaponSlots.next(2, 1, occupied));
        assertEquals(3, WeaponSlots.next(0, 1, occupied));
        assertEquals(0, WeaponSlots.next(3, -1, occupied));
        assertEquals(2, WeaponSlots.next(1, -1, occupied));
        assertEquals(0, WeaponSlots.next(1, 1, occupied));
        assertEquals(2, WeaponSlots.next(8, -1, 0b1100));
        assertEquals(3, WeaponSlots.next(4, 1, 0b1100));
    }

    @Test void oneOrNoWeaponNeverSelectsAnEmptySlot() {
        assertEquals(2, WeaponSlots.next(2, 1, 0b0100));
        assertEquals(2, WeaponSlots.next(2, -1, 0b0100));
        assertEquals(2, WeaponSlots.next(8, -1, 0b0100));
        for (int selected = 0; selected < 9; selected++) {
            assertEquals(selected, WeaponSlots.next(selected, 1, 0));
            assertEquals(selected, WeaponSlots.next(selected, -1, 0));
        }
    }

    @Test void hudAndBindingsCompactAcrossAllEmptySlots() {
        int occupied = 0b1101;
        assertArrayEquals(new int[] {2, 3}, WeaponSlots.candidates(0, occupied));
        assertArrayEquals(new int[] {0, 3}, WeaponSlots.candidates(2, occupied));
        assertEquals(0, WeaponSlots.slotForBinding(0, occupied));
        assertEquals(2, WeaponSlots.slotForBinding(1, occupied));
        assertEquals(3, WeaponSlots.slotForBinding(2, occupied));
        assertEquals(-1, WeaponSlots.slotForBinding(3, occupied));
        assertEquals(1, WeaponSlots.bindingIndex(2, occupied));
        assertEquals(2, WeaponSlots.bindingIndex(3, occupied));
        assertEquals(-1, WeaponSlots.bindingIndex(1, occupied));
        assertEquals(3, WeaponSlots.slotForBinding(0, 0b1000));
        assertEquals(0, WeaponSlots.bindingIndex(3, 0b1000));
        assertArrayEquals(new int[0], WeaponSlots.candidates(3, 0b1000));
        assertEquals(-1, WeaponSlots.slotForBinding(0, 0));
    }

    @Test void everyOccupancyPatternCyclesInInventoryOrderAndMapsKeysBackToItsWeapons() {
        for (int mask = 1; mask <= WeaponSlots.FULL; mask++) {
            final int occupied = mask;
            int[] expected = java.util.stream.IntStream.range(0, 4)
                    .filter(slot -> (occupied & (1 << slot)) != 0).toArray();
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[(i + 1) % expected.length], WeaponSlots.next(expected[i], -1, mask));
                assertEquals(expected[(i + expected.length - 1) % expected.length], WeaponSlots.next(expected[i], 1, mask));
                assertEquals(expected[i], WeaponSlots.slotForBinding(i, mask));
                assertEquals(i, WeaponSlots.bindingIndex(expected[i], mask));
            }
            assertEquals(-1, WeaponSlots.slotForBinding(expected.length, mask));
        }
    }
}
